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
import java.util.function.Supplier;
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
    private static final String DOCKER_HUB_CONFIG_KEY = "https://index.docker.io/v1/";

    private final ComposeFileRepository files;
    private final ComposeProperties properties;
    private final RegistryAuthSource registryAuths;
    private final List<String> executable;
    private final Supplier<List<String>> pluginDirs;
    private final Path tempRoot;

    @Autowired
    public ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties,
                                   RegistryAuthSource registryAuths) {
        this(files, properties, registryAuths, List.of("docker", "compose"));
    }

    /** Test seam: replaces {@code docker compose} with another executable. */
    ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties,
                            RegistryAuthSource registryAuths, List<String> executable) {
        this(files, properties, registryAuths, executable, defaultPluginDirs(), null);
    }

    /** Test seam: also replaces the CLI plugin dir lookup. */
    ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties,
                            RegistryAuthSource registryAuths, List<String> executable,
                            Supplier<List<String>> pluginDirs) {
        this(files, properties, registryAuths, executable, pluginDirs, null);
    }

    /** Test seam: also places the temporary TLS/registry-config dirs under {@code tempRoot} (null = system temp). */
    ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties,
                            RegistryAuthSource registryAuths, List<String> executable, Path tempRoot) {
        this(files, properties, registryAuths, executable, defaultPluginDirs(), tempRoot);
    }

    private ComposeExecutionService(ComposeFileRepository files, ComposeProperties properties,
                                    RegistryAuthSource registryAuths, List<String> executable,
                                    Supplier<List<String>> pluginDirs, Path tempRoot) {
        this.pluginDirs = pluginDirs;
        this.tempRoot = tempRoot;
        this.files = files;
        this.properties = properties;
        this.registryAuths = registryAuths;
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
        return execute(hostId, deploymentName, command, endpoint, onLine, composeFile, null);
    }

    /**
     * Like the variant with {@code composeFile}, plus a second compose file merged over the first ({@code -f main -f
     * override}); used to pin images when rolling back an image update. Like the main file it must lie inside the
     * deployment directory (symlinks resolved), else {@code ComposeExecutionException}.
     *
     * @param overrideFile {@code null} for none
     */
    public ComposeProcessResult execute(UUID hostId, String deploymentName, ComposeCommand command,
                                        DockerEndpoint endpoint, Consumer<String> onLine, Path composeFile,
                                        Path overrideFile) {
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
        if (overrideFile != null) {
            cmd.addAll(List.of("-f", requireInside(deploymentDir, overrideFile).toString()));
        }
        cmd.addAll(command.args());

        Path certDir = null;
        Path configDir = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (Files.isDirectory(dir)) {
                builder.directory(dir.toFile());
            }
            Map<String, String> env = builder.environment();
            DOCKER_ENV.forEach(env::remove);
            certDir = applyEndpoint(env, endpoint);
            configDir = applyRegistryAuths(env);
            return run(builder, onLine);
        } finally {
            deleteRecursively(certDir);
            deleteRecursively(configDir);
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
        Path configDir = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            DOCKER_ENV.forEach(env::remove);
            certDir = applyEndpoint(env, endpoint);
            configDir = applyRegistryAuths(env);
            return run(builder, onLine);
        } finally {
            deleteRecursively(certDir);
            deleteRecursively(configDir);
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
    private Path applyEndpoint(Map<String, String> env, DockerEndpoint endpoint) {
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
                    certDir = createTempDir("shipir-tls-");
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

    private static Supplier<List<String>> defaultPluginDirs() {
        return () -> CliPluginDirs.resolve(
                System.getenv(), Path.of(System.getProperty("user.home")), CliPluginDirs.SYSTEM_DIRS);
    }

    private Path createTempDir(String prefix) throws IOException {
        return tempRoot == null
                ? Files.createTempDirectory(prefix, DIR_PERMISSIONS)
                : Files.createTempDirectory(tempRoot, prefix, DIR_PERMISSIONS);
    }

    /**
     * Writes a Docker {@code config.json} with all stored registry logins into a 0700 temp dir and points
     * {@code DOCKER_CONFIG} at it (plus {@code cliPluginsExtraDirs} so the compose plugin stays findable); returns the dir, or null (environment untouched) when there are no logins.
     */
    private Path applyRegistryAuths(Map<String, String> env) {
        List<RegistryAuthSource.Auth> auths = registryAuths.auths();
        if (auths == null || auths.isEmpty()) {
            return null;
        }
        Path configDir = null;
        try {
            configDir = createTempDir("shipir-docker-config-");
            writeFile(configDir.resolve("config.json"), configJson(auths, pluginDirs.get()));
        } catch (IOException e) {
            deleteRecursively(configDir);
            throw new ComposeExecutionException("Could not prepare registry credentials", e);
        }
        env.put("DOCKER_CONFIG", configDir.toString());
        return configDir;
    }

    private static String configJson(List<RegistryAuthSource.Auth> auths, List<String> pluginDirs) {
        var entries = new java.util.LinkedHashMap<String, Object>();
        for (RegistryAuthSource.Auth a : auths) {
            String key = "docker.io".equals(a.registryHost()) ? DOCKER_HUB_CONFIG_KEY : a.registryHost();
            String token = java.util.Base64.getEncoder()
                    .encodeToString((a.username() + ":" + a.secret()).getBytes(StandardCharsets.UTF_8));
            entries.put(key, Map.of("auth", token));
        }
        var config = new java.util.LinkedHashMap<String, Object>();
        config.put("auths", entries);
        if (!pluginDirs.isEmpty()) {
            config.put("cliPluginsExtraDirs", pluginDirs);
        }
        try {
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(config);
        } catch (RuntimeException e) {
            throw new ComposeExecutionException("Could not prepare registry credentials", null);
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
