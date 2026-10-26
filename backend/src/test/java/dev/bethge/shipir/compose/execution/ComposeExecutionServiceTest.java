package dev.bethge.shipir.compose.execution;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses a fake "docker compose" script, so no Docker daemon is needed. */
class ComposeExecutionServiceTest {

    @TempDir
    Path tmp;

    private final UUID hostId = UUID.randomUUID();
    private ComposeFileRepository files;
    private Path script;
    private Path tempRoot;
    private RegistryAuthSource auths = List::of;

    @BeforeEach
    void setUp() throws IOException {
        files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        files.write(hostId, "web", "services: {}\n", null);
        tempRoot = Files.createDirectory(tmp.resolve("temp-root"));
        script = tmp.resolve("fake-compose.sh");
        writeScript("""
                #!/bin/sh
                echo "ARGS:$*"
                echo "PWD:$(pwd)"
                echo "HOST:$DOCKER_HOST"
                echo "VERIFY:$DOCKER_TLS_VERIFY"
                echo "CERT:$DOCKER_CERT_PATH"
                if [ -n "$DOCKER_CERT_PATH" ]; then ls "$DOCKER_CERT_PATH" | tr '\\n' ' '; echo; fi
                echo "oops" >&2
                exit ${FAKE_EXIT:-0}
                """);
    }

    private void writeScript(String content) throws IOException {
        Files.writeString(script, content);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    }

    private ComposeExecutionService service(Duration timeout) {
        return new ComposeExecutionService(files, new ComposeProperties(timeout, java.util.List.of()), auths, List.of(script.toString()),
                tempRoot);
    }

    private static DockerEndpoint.Local local() {
        return new DockerEndpoint.Local("unix:///var/run/docker.sock");
    }

    private static DockerEndpoint.Tcp tls() {
        return new DockerEndpoint.Tcp("10.0.0.5", 2376, new DockerEndpoint.TlsMaterial("CA", "CERT", "KEY"));
    }

    private ComposeProcessResult run(ComposeCommand cmd, DockerEndpoint endpoint, List<String> lines) {
        return service(Duration.ofSeconds(20)).execute(hostId, "web", cmd, endpoint, lines::add);
    }

    private static String line(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void buildsArgumentListForUp() throws IOException {
        List<String> lines = new ArrayList<>();
        ComposeProcessResult result = run(ComposeCommand.UP, local(), lines);
        Path dir = files.deploymentDir(hostId, "web");
        assertThat(result.exitCode()).isZero();
        assertThat(result.success()).isTrue();
        assertThat(line(lines, "ARGS:")).isEqualTo("ARGS:-p web --project-directory " + dir + " -f "
                + dir.resolve("compose.yaml") + " up -d --remove-orphans");
        assertThat(line(lines, "PWD:")).isEqualTo("PWD:" + dir.toRealPath());
    }

    @Test
    void buildsArgumentsForOtherCommands() {
        assertThat(argsOf(ComposeCommand.DOWN)).endsWith(" down");
        assertThat(argsOf(ComposeCommand.PULL)).endsWith(" pull");
        assertThat(argsOf(ComposeCommand.CONFIG)).endsWith(" config --quiet");
    }

    private String argsWith(ComposeCommand cmd, ComposeOptions options) {
        List<String> lines = new ArrayList<>();
        service(Duration.ofSeconds(20)).execute(hostId, "web", cmd, local(), lines::add, null, null, options);
        return line(lines, "ARGS:");
    }

    @Test
    void profilesAreGlobalFlagsBeforeTheSubcommand() {
        Path dir = files.deploymentDir(hostId, "web");
        assertThat(argsWith(ComposeCommand.UP, ComposeOptions.ofProfiles(List.of("debug", "tools"))))
                .isEqualTo("ARGS:-p web --project-directory " + dir + " -f " + dir.resolve("compose.yaml")
                        + " --profile debug --profile tools up -d --remove-orphans");
    }

    @Test
    void servicesFollowTheSubcommandArguments() {
        Path dir = files.deploymentDir(hostId, "web");
        assertThat(argsWith(ComposeCommand.RESTART, ComposeOptions.NONE.withServices(List.of("api"))))
                .isEqualTo("ARGS:-p web --project-directory " + dir + " -f " + dir.resolve("compose.yaml") + " restart api");
        assertThat(argsWith(ComposeCommand.UP_SERVICE, ComposeOptions.ofProfiles(List.of("p")).withServices(List.of("api"))))
                .endsWith(" --profile p up -d api");
    }

    @Test
    void allProfilesLookupUsesQuotedStarAsOneArgument() {
        assertThat(argsWith(ComposeCommand.CONFIG_SERVICES, ComposeOptions.ofProfiles(List.of("*"))))
                .endsWith(" --profile * config --services");
    }

    @Test
    void noOptionsLeavesTheArgumentListUnchanged() {
        assertThat(argsWith(ComposeCommand.PULL, ComposeOptions.NONE)).endsWith(" pull").doesNotContain("--profile");
    }

    @Test
    void flagLikeOrInjectingProfileAndServiceNamesAreRejectedBeforeProcessStart() {
        for (String bad : List.of("--privileged", "-v", "a;b", "a b", "$(id)", "../x", "a/b", "", " ", "-", "a\nb", "*x")) {
            assertThatThrownBy(() -> ComposeOptions.ofProfiles(List.of(bad))).as("profile " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ComposeOptions.NONE.withServices(List.of(bad))).as("service " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ComposeOptions.NONE.withServices(List.of("*"))).isInstanceOf(IllegalArgumentException.class);
    }

    private String argsOf(ComposeCommand cmd) {
        List<String> lines = new ArrayList<>();
        run(cmd, local(), lines);
        return line(lines, "ARGS:");
    }

    @Test
    void shellMetacharactersInDeploymentNameAreRejectedBeforeProcessStart() {
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(5), java.util.List.of()), List::of,
                List.of(tmp.resolve("does-not-exist").toString()));
        for (String bad : List.of("web; rm -rf /", "../x", "a b", "$(id)", "Web", "", "a/b")) {
            assertThatThrownBy(() -> service.execute(hostId, bad, ComposeCommand.UP, local(), l -> { }))
                    .isInstanceOf(PathValidationException.class);
        }
    }

    @Test
    void localEndpointSetsDockerHostSocketOnly() {
        List<String> lines = new ArrayList<>();
        run(ComposeCommand.UP, local(), lines);
        assertThat(line(lines, "HOST:")).isEqualTo("HOST:unix:///var/run/docker.sock");
        assertThat(line(lines, "VERIFY:")).isEqualTo("VERIFY:");
        assertThat(line(lines, "CERT:")).isEqualTo("CERT:");
    }

    @Test
    void tcpEndpointSetsTlsEnvAndWritesCertsThenRemovesThem() {
        List<String> lines = new ArrayList<>();
        run(ComposeCommand.UP, tls(), lines);
        assertThat(line(lines, "HOST:")).isEqualTo("HOST:tcp://10.0.0.5:2376");
        assertThat(line(lines, "VERIFY:")).isEqualTo("VERIFY:1");
        Path certDir = Path.of(line(lines, "CERT:").substring(5));
        assertThat(lines).contains("ca.pem cert.pem key.pem ");
        assertThat(certDir).doesNotExist();
    }

    @Test
    void tcpWithoutTlsHasNoCertPath() {
        List<String> lines = new ArrayList<>();
        run(ComposeCommand.UP, new DockerEndpoint.Tcp("h", 2375, null), lines);
        assertThat(line(lines, "HOST:")).isEqualTo("HOST:tcp://h:2375");
        assertThat(line(lines, "VERIFY:")).isEqualTo("VERIFY:");
        assertThat(line(lines, "CERT:")).isEqualTo("CERT:");
    }

    @Test
    void certDirRemovedAfterNonZeroExit() throws IOException {
        writeScript("#!/bin/sh\necho \"CERT:$DOCKER_CERT_PATH\"\nexit 3\n");
        List<String> lines = new ArrayList<>();
        ComposeProcessResult result = run(ComposeCommand.UP, tls(), lines);
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.success()).isFalse();
        assertThat(Path.of(line(lines, "CERT:").substring(5))).doesNotExist();
    }

    @Test
    void certDirRemovedWhenProcessCannotStart() {
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(5), java.util.List.of()), List::of,
                List.of(tmp.resolve("missing").toString()));
        assertThatThrownBy(() -> service.execute(hostId, "web", ComposeCommand.UP, tls(), l -> { }))
                .isInstanceOf(ComposeExecutionException.class);
    }

    @Test
    void stdoutAndStderrAreCapturedLineByLine() {
        List<String> lines = new ArrayList<>();
        ComposeProcessResult result = run(ComposeCommand.UP, local(), lines);
        assertThat(lines).contains("oops");
        assertThat(result.output()).isEqualTo(lines);
    }

    @Test
    void timeoutKillsProcessTreeAndCleansUp() throws IOException {
        Path pidFile = tmp.resolve("child.pid");
        writeScript("#!/bin/sh\necho \"CERT:$DOCKER_CERT_PATH\"\nsleep 60 &\necho $! > " + pidFile + "\nwait\n");
        List<String> lines = new ArrayList<>();
        long start = System.nanoTime();
        ComposeProcessResult result = service(Duration.ofSeconds(3))
                .execute(hostId, "web", ComposeCommand.UP, tls(), lines::add);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(Path.of(line(lines, "CERT:").substring(5))).doesNotExist();
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void composeFileInCheckoutUsesItsDirectoryAsProjectDirectoryAndShipirEnvFile() throws IOException {
        files.write(hostId, "web", "services: {}\n", "A=1\n");
        Path deploymentDir = files.deploymentDir(hostId, "web");
        Files.createDirectories(files.repoDir(hostId, "web"));
        Path file = files.repoComposeFile(hostId, "web", "app/compose.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "services: {}\n");
        List<String> lines = new ArrayList<>();

        service(Duration.ofSeconds(20)).execute(hostId, "web", ComposeCommand.UP, local(), lines::add, file);

        assertThat(line(lines, "ARGS:")).isEqualTo("ARGS:-p web --env-file " + deploymentDir.resolve(".env")
                + " --project-directory " + file.getParent() + " -f " + file + " up -d --remove-orphans");
        assertThat(line(lines, "PWD:")).isEqualTo("PWD:" + file.getParent().toRealPath());
    }

    @Test
    void checkoutWithoutShipirEnvPassesNoEnvFileSoRepoDotEnvStaysInEffect() throws IOException {
        Files.createDirectories(files.repoDir(hostId, "web"));
        Path file = files.repoComposeFile(hostId, "web", "compose.yaml");
        Files.writeString(file, "services: {}\n");
        List<String> lines = new ArrayList<>();

        service(Duration.ofSeconds(20)).execute(hostId, "web", ComposeCommand.CONFIG, local(), lines::add, file);

        assertThat(line(lines, "ARGS:")).doesNotContain("--env-file");
    }

    @Test
    void composeFilesOutsideTheDeploymentDirectoryAreRejected() throws IOException {
        Path deploymentDir = files.deploymentDir(hostId, "web");
        Path outside = Files.writeString(tmp.resolve("outside.yaml"), "services: {}\n");
        Path repo = files.repoDir(hostId, "web");
        Files.createDirectories(repo);
        Path link = Files.createSymbolicLink(repo.resolve("link.yaml"), outside);
        var service = service(Duration.ofSeconds(5));

        for (Path bad : List.of(outside, deploymentDir.resolve("../other/compose.yaml"), link)) {
            assertThatThrownBy(() -> service.execute(hostId, "web", ComposeCommand.UP, local(), l -> { }, bad))
                    .isInstanceOf(ComposeExecutionException.class);
        }
    }

    @Test
    void overrideFileIsMergedAsSecondComposeFile() throws IOException {
        files.write(hostId, "web", "services: {}\n", null);
        Path override = files.writeRollbackOverride(hostId, "web", "services:\n  web:\n    image: x\n");
        Path dir = files.deploymentDir(hostId, "web");
        List<String> lines = new ArrayList<>();

        service(Duration.ofSeconds(20)).execute(hostId, "web", ComposeCommand.UP, local(), lines::add, null, override);

        assertThat(line(lines, "ARGS:")).isEqualTo("ARGS:-p web --project-directory " + dir + " -f "
                + dir.resolve("compose.yaml") + " -f " + override + " up -d --remove-orphans");
    }

    @Test
    void overrideFilesOutsideTheDeploymentDirectoryAreRejected() throws IOException {
        files.write(hostId, "web", "services: {}\n", null);
        Path outside = Files.writeString(tmp.resolve("outside.yaml"), "services: {}\n");
        var service = service(Duration.ofSeconds(5));

        for (Path bad : List.of(outside, files.deploymentDir(hostId, "web").resolve("../other/o.yaml"))) {
            assertThatThrownBy(() -> service.execute(hostId, "web", ComposeCommand.UP, local(), l -> { }, null, bad))
                    .isInstanceOf(ComposeExecutionException.class);
        }
    }

    @Test
    void executeProjectRunsOnlyProjectNameAndCommandWithoutComposeFile() {
        ComposeProcessResult result = service(Duration.ofSeconds(10))
                .executeProject("legacy", ComposeCommand.DOWN, local(), line -> { });

        assertThat(result.output()).contains("ARGS:-p legacy down");
        assertThat(service(Duration.ofSeconds(10)).executeProject("legacy", ComposeCommand.DOWN_VOLUMES, local(), line -> { })
                .output()).contains("ARGS:-p legacy down -v");
    }

    @Test
    void executeProjectRejectsInvalidNamesBeforeStartingAnything() {
        for (String name : List.of("../x", "A", "a b", "-x", "x;y", "")) {
            assertThatThrownBy(() -> service(Duration.ofSeconds(10))
                    .executeProject(name, ComposeCommand.DOWN, local(), line -> { }))
                    .isInstanceOf(PathValidationException.class);
        }
    }

    private static final String CONFIG_SCRIPT = """
            #!/bin/sh
            echo "CONF:$DOCKER_CONFIG"
            if [ -n "$DOCKER_CONFIG" ]; then
              echo "DIRMODE:$(stat -c %a "$DOCKER_CONFIG" 2>/dev/null || stat -f %Lp "$DOCKER_CONFIG")"
              echo "FILEMODE:$(stat -c %a "$DOCKER_CONFIG/config.json" 2>/dev/null || stat -f %Lp "$DOCKER_CONFIG/config.json")"
              echo "JSON:$(cat "$DOCKER_CONFIG/config.json")"
            fi
            exit ${FAKE_EXIT:-0}
            """;

    private void withAuths(RegistryAuthSource.Auth... list) throws IOException {
        auths = () -> List.of(list);
        writeScript(CONFIG_SCRIPT);
    }

    @Test
    void registryAuthsAreWrittenToTemporaryDockerConfigAndRemoved() throws IOException {
        withAuths(new RegistryAuthSource.Auth("ghcr.io", "robot", "s3cret"),
                new RegistryAuthSource.Auth("docker.io", "hubuser", "hubpass"));
        List<String> lines = new ArrayList<>();
        run(ComposeCommand.UP, local(), lines);
        Path dir = Path.of(line(lines, "CONF:").substring(5));
        assertThat(line(lines, "DIRMODE:")).isEqualTo("DIRMODE:700");
        assertThat(line(lines, "FILEMODE:")).isEqualTo("FILEMODE:600");
        String ghcr = java.util.Base64.getEncoder().encodeToString("robot:s3cret".getBytes());
        String hub = java.util.Base64.getEncoder().encodeToString("hubuser:hubpass".getBytes());
        assertThat(line(lines, "JSON:").replace(" ", ""))
                .contains("\"ghcr.io\":{\"auth\":\"" + ghcr + "\"}")
                .contains("\"https://index.docker.io/v1/\":{\"auth\":\"" + hub + "\"}")
                .doesNotContain("\"docker.io\"");
        assertThat(dir).doesNotExist();
    }

    @Test
    void registryConfigKeepsComposePluginFindable() throws IOException {
        withAuths(new RegistryAuthSource.Auth("ghcr.io", "robot", "s3cret"));
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(20), java.util.List.of()), auths,
                List.of(script.toString()), () -> List.of("/opt/plugins", "/usr/lib/docker/cli-plugins"));
        List<String> lines = new ArrayList<>();
        service.execute(hostId, "web", ComposeCommand.UP, local(), lines::add);
        assertThat(line(lines, "JSON:").replace(" ", ""))
                .contains("\"cliPluginsExtraDirs\":[\"/opt/plugins\",\"/usr/lib/docker/cli-plugins\"]");
    }

    @Test
    void registryConfigDirRemovedAfterFailureAndWhenProcessCannotStart() throws IOException {
        withAuths(new RegistryAuthSource.Auth("ghcr.io", "robot", "s3cret"));
        writeScript(CONFIG_SCRIPT.replace("exit ${FAKE_EXIT:-0}", "exit 3"));
        List<String> lines = new ArrayList<>();
        assertThat(run(ComposeCommand.UP, local(), lines).exitCode()).isEqualTo(3);
        assertThat(Path.of(line(lines, "CONF:").substring(5))).doesNotExist();

        var missing = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(5), java.util.List.of()), auths,
                List.of(tmp.resolve("missing").toString()), tempRoot);
        assertThatThrownBy(() -> missing.execute(hostId, "web", ComposeCommand.UP, local(), l -> { }))
                .isInstanceOf(ComposeExecutionException.class);
        assertThat(tempRootEntries()).isEmpty();
    }

    @Test
    void registryConfigDirRemovedAfterTimeout() throws IOException {
        withAuths(new RegistryAuthSource.Auth("ghcr.io", "robot", "s3cret"));
        writeScript("#!/bin/sh\necho \"CONF:$DOCKER_CONFIG\"\nsleep 30\n");
        List<String> lines = new ArrayList<>();
        ComposeProcessResult result = service(Duration.ofSeconds(4))
                .execute(hostId, "web", ComposeCommand.UP, local(), lines::add);
        assertThat(result.timedOut()).isTrue();
        assertThat(Path.of(line(lines, "CONF:").substring(5))).doesNotExist();
    }

    @Test
    void noRegistryAuthsLeavesDockerConfigUntouched() throws IOException {
        writeScript(CONFIG_SCRIPT);
        List<String> lines = new ArrayList<>();
        run(ComposeCommand.UP, local(), lines);
        assertThat(line(lines, "CONF:")).isEqualTo("CONF:" + System.getenv().getOrDefault("DOCKER_CONFIG", ""));
        assertThat(tempRootEntries()).isEmpty();
    }

    @Test
    void executeProjectAlsoGetsRegistryAuths() throws IOException {
        withAuths(new RegistryAuthSource.Auth("ghcr.io", "robot", "s3cret"));
        List<String> lines = new ArrayList<>();
        service(Duration.ofSeconds(20)).executeProject("web", ComposeCommand.DOWN, local(), lines::add);
        assertThat(line(lines, "JSON:")).contains("ghcr.io");
        assertThat(Path.of(line(lines, "CONF:").substring(5))).doesNotExist();
    }

    @Test
    void registryAuthToStringHidesSecret() {
        assertThat(new RegistryAuthSource.Auth("h", "u", "s3cret").toString()).doesNotContain("s3cret");
    }

    private static final java.util.Map<String, String> BACKEND_ENV = java.util.Map.ofEntries(
            java.util.Map.entry("PATH", "/usr/bin:/bin"), java.util.Map.entry("HOME", "/home/shipir"),
            java.util.Map.entry("LANG", "C.UTF-8"), java.util.Map.entry("LC_ALL", "C.UTF-8"),
            java.util.Map.entry("TZ", "UTC"), java.util.Map.entry("DOCKER_CONFIG", "/home/shipir/.docker"),
            java.util.Map.entry("DOCKER_HOST", "tcp://backend-env:1"), java.util.Map.entry("COMPOSE_FILE", "/etc/evil.yaml"),
            java.util.Map.entry("DB_PASSWORD", "db-secret"), java.util.Map.entry("SHIPIR_ENCRYPTION_KEY", "key-secret"),
            java.util.Map.entry("SHIPIR_INITIAL_ADMIN_PASSWORD", "admin-secret"), java.util.Map.entry("AWS_SECRET_ACCESS_KEY", "aws-secret"));

    private List<String> envDump(boolean project) throws IOException {
        writeScript("#!/bin/sh\nenv\n");
        List<String> lines = new ArrayList<>();
        ComposeExecutionService svc = service(Duration.ofSeconds(20)).withParentEnvironment(BACKEND_ENV);
        if (project) {
            svc.executeProject("web", ComposeCommand.DOWN, local(), lines::add);
        } else {
            svc.execute(hostId, "web", ComposeCommand.UP, local(), lines::add);
        }
        return lines;
    }

    @Test
    void childProcessGetsAllowListedEnvironmentOnly() throws IOException {
        List<String> lines = envDump(false);
        assertThat(String.join("\n", lines)).doesNotContain("db-secret", "key-secret", "admin-secret", "aws-secret",
                "SHIPIR_", "DB_PASSWORD", "backend-env", "COMPOSE_FILE");
        assertThat(lines).contains("PATH=/usr/bin:/bin", "HOME=/home/shipir", "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TZ=UTC",
                "DOCKER_HOST=unix:///var/run/docker.sock", "DOCKER_CONFIG=/home/shipir/.docker");
    }

    @Test
    void executeProjectGetsAllowListedEnvironmentOnly() throws IOException {
        assertThat(String.join("\n", envDump(true))).doesNotContain("db-secret", "key-secret", "admin-secret", "aws-secret");
    }

    @Test
    void allowListedEnvironmentKeepsRegistryAuthDockerConfig() throws IOException {
        withAuths(new RegistryAuthSource.Auth("ghcr.io", "robot", "s3cret"));
        List<String> lines = envDump(false);
        assertThat(lines).anyMatch(l -> l.startsWith("DOCKER_CONFIG=" + tempRoot));
        assertThat(String.join("\n", lines)).doesNotContain("db-secret");
    }

    @Test
    void composeInterpolationOfBackendSecretsResolvesEmpty() throws IOException {
        writeScript("#!/bin/sh\necho \"KEY:${SHIPIR_ENCRYPTION_KEY}\"\necho \"DB:${DB_PASSWORD}\"\n");
        List<String> lines = new ArrayList<>();
        service(Duration.ofSeconds(20)).withParentEnvironment(BACKEND_ENV)
                .execute(hostId, "web", ComposeCommand.UP, local(), lines::add);
        assertThat(lines).containsExactly("KEY:", "DB:");
    }

    private List<Path> tempRootEntries() throws IOException {
        try (var s = Files.list(tempRoot)) {
            return s.toList();
        }
    }
}
