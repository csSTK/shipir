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
 * Runs {@code docker compose} for a stack as a subprocess: argument list only (no shell), the stack name is
 * validated by {@link ComposeFileRepository#stackDir} before anything starts. The target daemon is selected through
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
     * Runs {@code command} for the stack and blocks until it ends or times out.
     *
     * @param onLine receives every output line as it arrives (called on the calling thread)
     * @throws dev.bethge.shipir.common.util.PathValidationException for an invalid stack name
     * @throws ComposeExecutionException if the process cannot be started
     */
    public ComposeProcessResult execute(UUID hostId, String stackName, ComposeCommand command,
                                        DockerEndpoint endpoint, Consumer<String> onLine) {
        Path dir = files.stackDir(hostId, stackName);
        List<String> cmd = new ArrayList<>(executable);
        cmd.addAll(List.of("-p", stackName, "--project-directory", dir.toString(),
                "-f", dir.resolve(ComposeFileRepository.COMPOSE_FILE).toString()));
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
