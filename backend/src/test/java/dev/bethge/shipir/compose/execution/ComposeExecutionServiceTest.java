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

    @BeforeEach
    void setUp() throws IOException {
        files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        files.write(hostId, "web", "services: {}\n", null);
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
        return new ComposeExecutionService(files, new ComposeProperties(timeout), List.of(script.toString()));
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

    private String argsOf(ComposeCommand cmd) {
        List<String> lines = new ArrayList<>();
        run(cmd, local(), lines);
        return line(lines, "ARGS:");
    }

    @Test
    void shellMetacharactersInDeploymentNameAreRejectedBeforeProcessStart() {
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(5)),
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
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(5)),
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
}
