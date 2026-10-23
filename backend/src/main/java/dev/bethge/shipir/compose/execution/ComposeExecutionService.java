package dev.bethge.shipir.compose.execution;

import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs {@code docker compose} for a deployment as a subprocess: argument list only (no shell), the deployment name is
 * validated by {@link ComposeFileRepository#deploymentDir} before anything starts. The target daemon is selected through
 * {@code DOCKER_HOST} (and, for TLS, {@code DOCKER_CERT_PATH} pointing to a 0700 temp dir that is always deleted).
 * Output (stdout and stderr merged) is delivered line by line; on timeout the whole process tree is killed.
 */
@Service
@EnableConfigurationProperties(ComposeProperties.class)
public class ComposeExecutionService {

    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final List<String> DOCKER_ENV =
            List.of("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "DOCKER_CONTEXT");

    private final ComposeFileRepository files;
    private final ComposeProperties properties;
    private final List<String> executable;

    @Autowired
    public ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties) {
        this(files, properties, List.of("docker", "compose"));
    }

    /** Test seam: replaces {@code docker compose} with another executable. */
    ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties, List<String> executable) {
        this.files = files;
        this.properties = properties;
        this.executable = List.copyOf(executable);
    }

    /**
     * Runs {@code command} for the deployment (compose file {@code <deploymentDir>/compose.yaml}) and blocks until it ends or
     * times out.
     *
     * @param onLine receives every output line as it arrives (called on the calling thread)
     * @throws dev.bethge.shipir.common.util.PathValidationException for an invalid deployment name
     * @throws ComposeExecutionException if the process cannot be started
     */
    public ComposeProcessResult execute(UUID hostId, String deploymentName, ComposeCommand command,
                                        DockerEndpoint endpoint, Consumer<String> onLine) {
        return execute(hostId, deploymentName, command, endpoint, onLine, null);
    }

    /**
     * Like {@link #execute(UUID, String, ComposeCommand, DockerEndpoint, Consumer)}, but for a compose file that lives
     * inside the deployment directory (Git checkout in {@code repo/}): {@code --project-directory} is that file's directory
     * and {@code -f} the file itself, so relative build contexts, bind mounts and {@code env_file} resolve against the
     * checkout. The Shipir-managed {@code <deploymentDir>/.env} is passed with {@code --env-file} when it exists; this
     * replaces compose's default lookup of {@code .env} in the project directory, so a {@code .env} tracked in the
     * repository is never read nor overwritten (Shipir's variables win and nothing in the checkout is modified).
     *
     * @param composeFile {@code null} for the default {@code <deploymentDir>/compose.yaml}; otherwise must lie inside the
     *                    deployment directory (symlinks resolved), else {@code ComposeExecutionException}
     */
    public ComposeProcessResult execute(UUID hostId, String deploymentName, ComposeCommand command,
                                        DockerEndpoint endpoint, Consumer<String> onLine, Path composeFile) {
        Path deploymentDir = files.deploymentDir(hostId, deploymentName);
        Path dir = deploymentDir;
        Path file = deploymentDir.resolve(ComposeFileRepository.COMPOSE_FILE);
        List<String> cmd = new ArrayList<>(executable);
        cmd.addAll(List.of("-p", deploymentName));
        if (composeFile != null) {
            file = requireInside(deploymentDir, composeFile);
            dir = file.getParent();
            Path envFile = deploymentDir.resolve(ComposeFileRepository.ENV_FILE);
            if (Files.isRegularFile(envFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                cmd.addAll(List.of("--env-file", envFile.toString()));
            }
        }
        cmd.addAll(List.of("--project-directory", dir.toString(), "-f", file.toString()));
        cmd.addAll(command.args());

        Path certDir = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (Files.isDirectory(dir)) {
                builder.directory(dir.toFile());
            }
            Map<String, String> env = builder.environment();
            DOCKER_ENV.forEach(env::remove);
            certDir = applyEndpoint(env, endpoint);
            return run(builder, onLine);
        } finally {
            deleteRecursively(certDir);
        }
    }

    /**
     * Runs {@code docker compose -p <project> <command>} without any compose file, for a live project Shipir does not
     * manage (Compose finds the containers by their project label). The name is validated like a deployment name.
     *
     * @throws dev.bethge.shipir.common.util.PathValidationException for an invalid project name
     * @throws ComposeExecutionException if the process cannot be started
     */
    public ComposeProcessResult executeProject(String projectName, ComposeCommand command, DockerEndpoint endpoint,
                                               Consumer<String> onLine) {
        dev.bethge.shipir.common.util.PathValidationUtil.validateDeploymentName(projectName);
        List<String> cmd = new ArrayList<>(executable);
        cmd.addAll(List.of("-p", projectName));
        cmd.addAll(command.args());
        Path certDir = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            DOCKER_ENV.forEach(env::remove);
            certDir = applyEndpoint(env, endpoint);
            return run(builder, onLine);
        } finally {
            deleteRecursively(certDir);
        }
    }

    /** The compose file must be inside the deployment directory, also after resolving symlinks. */
    private static Path requireInside(Path deploymentDir, Path composeFile) {
        Path normalized = composeFile.toAbsolutePath().normalize();
        try {
            Path realDeployment = deploymentDir.toRealPath();
            Path existing = normalized;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (!normalized.startsWith(deploymentDir) || existing == null || !existing.toRealPath().startsWith(realDeployment)) {
                throw new ComposeExecutionException("Compose file is outside the deployment directory", null);
            }
        } catch (IOException e) {
            throw new ComposeExecutionException("Compose file is outside the deployment directory", e);
        }
        return normalized;
    }

    private ComposeProcessResult run(ProcessBuilder builder, Consumer<String> onLine) {
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ComposeExecutionException("Could not start docker compose", e);
        }
        boolean[] timedOut = {false};
        Thread watchdog = Thread.startVirtualThread(() -> {
            try {
                if (!process.waitFor(properties.timeout().toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    timedOut[0] = true;
                    killTree(process);
                }
            } catch (InterruptedException ignored) {
                // run finished normally
            }
        });
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
                onLine.accept(line);
            }
            int exit = process.waitFor();
            return new ComposeProcessResult(exit, timedOut[0], output);
        } catch (IOException e) {
            if (timedOut[0]) {
                return new ComposeProcessResult(-1, true, output);
            }
            throw new ComposeExecutionException("Could not read docker compose output", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new ComposeExecutionException("Interrupted while running docker compose", e);
        } finally {
            watchdog.interrupt();
            if (process.isAlive()) {
                killTree(process);
            }
        }
    }

    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Sets the DOCKER_* variables; returns the cert temp dir if one was created. */
    private static Path applyEndpoint(Map<String, String> env, DockerEndpoint endpoint) {
        switch (endpoint) {
            case DockerEndpoint.Local local -> {
                String socket = local.socketPath();
                env.put("DOCKER_HOST", socket.contains("://") ? socket : "unix://" + socket);
                return null;
            }
            case DockerEndpoint.Tcp tcp -> {
                env.put("DOCKER_HOST", "tcp://" + tcp.host() + ":" + tcp.port());
                if (tcp.tls() == null) {
                    return null;
                }
                Path certDir = null;
                try {
                    certDir = Files.createTempDirectory("shipir-tls-", DIR_PERMISSIONS);
                    writeFile(certDir.resolve("ca.pem"), tcp.tls().caPem());
                    writeFile(certDir.resolve("cert.pem"), tcp.tls().certPem());
                    writeFile(certDir.resolve("key.pem"), tcp.tls().keyPem());
                } catch (IOException e) {
                    deleteRecursively(certDir);
                    throw new ComposeExecutionException("Could not prepare TLS material", e);
                }
                env.put("DOCKER_TLS_VERIFY", "1");
                env.put("DOCKER_CERT_PATH", certDir.toString());
                return certDir;
            }
        }
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createFile(file, FILE_PERMISSIONS);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
