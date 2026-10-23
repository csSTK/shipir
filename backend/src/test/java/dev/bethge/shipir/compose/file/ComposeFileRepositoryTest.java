package dev.bethge.shipir.compose.file;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.compose.DeploymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeFileRepositoryTest {

    private static final UUID HOST = UUID.randomUUID();

    @TempDir
    Path base;

    ComposeFileRepository repo;

    @BeforeEach
    void setUp() {
        repo = new ComposeFileRepository(new DeploymentProperties(base));
    }

    @Test
    void writesAndReadsComposeAndEnv() {
        repo.write(HOST, "web", "services: {}\n", "A=1\n");

        assertThat(repo.readCompose(HOST, "web")).contains("services: {}\n");
        assertThat(repo.readEnv(HOST, "web")).contains("A=1\n");
        assertThat(base.resolve(HOST.toString()).resolve("web").resolve("compose.yaml")).exists();
    }

    @Test
    void readsEmptyForUnknownDeployment() {
        assertThat(repo.readCompose(HOST, "nope")).isEmpty();
        assertThat(repo.readEnv(HOST, "nope")).isEmpty();
    }

    @Test
    void overwriteReplacesContentAndNullEnvRemovesEnvFile() {
        repo.write(HOST, "web", "v1", "A=1");
        repo.write(HOST, "web", "v2", "A=2");
        assertThat(repo.readCompose(HOST, "web")).contains("v2");
        assertThat(repo.readEnv(HOST, "web")).contains("A=2");

        repo.write(HOST, "web", "v3", null);
        assertThat(repo.readEnv(HOST, "web")).isEmpty();
    }

    @Test
    void deleteRemovesDeploymentDirectoryIncludingRepoCheckout() throws IOException {
        repo.write(HOST, "web", "x", "A=1");
        Path checkout = repo.repoDir(HOST, "web");
        Files.createDirectories(checkout.resolve(".git"));
        Files.writeString(checkout.resolve("compose.yaml"), "y");

        repo.delete(HOST, "web");

        assertThat(repo.deploymentDir(HOST, "web")).doesNotExist();
        assertThat(repo.readCompose(HOST, "web")).isEmpty();
        repo.delete(HOST, "web");
    }

    @Test
    void deleteDoesNotFollowSymlinksIntoOutsideDirectories(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("keep.txt"), "keep");
        repo.write(HOST, "web", "x", null);
        Files.createSymbolicLink(repo.deploymentDir(HOST, "web").resolve("link"), outside);

        repo.delete(HOST, "web");

        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void writeLeavesNoTempFilesBehind() throws IOException {
        repo.write(HOST, "web", "v1", "A=1");
        Path dir = repo.deploymentDir(HOST, "web");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("compose.yaml", ".env");
        }
    }

    @Test
    void failedWriteKeepsPreviousContentIntact() throws IOException {
        repo.write(HOST, "web", "v1", null);
        Path compose = repo.deploymentDir(HOST, "web").resolve("compose.yaml");
        // a directory named like the target makes the final move fail after the temp file was written
        Files.delete(compose);
        Files.createDirectory(compose);
        Files.writeString(compose.resolve("blocker"), "x");

        assertThatThrownBy(() -> repo.write(HOST, "web", "v2", null)).isInstanceOf(java.io.UncheckedIOException.class);

        try (Stream<Path> files = Files.list(repo.deploymentDir(HOST, "web"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("compose.yaml");
        }
    }

    @Test
    void createsDirectoriesWith0700AndFilesWith0600() throws IOException {
        repo.write(HOST, "web", "x", "SECRET=1");
        Path dir = repo.deploymentDir(HOST, "web");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParent()))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(".env")))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("compose.yaml")))).isEqualTo("rw-------");
    }

    @Test
    void createsMissingBaseDirectory(@TempDir Path parent) {
        Path missing = parent.resolve("nested/deployments");
        new ComposeFileRepository(new DeploymentProperties(missing)).write(HOST, "web", "x", null);

        assertThat(missing.resolve(HOST.toString()).resolve("web").resolve("compose.yaml")).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil", "..", "a/b", "/etc", "..%2fevil", "a\\b", "UPPER", "", " ", ".hidden", "web\0"})
    void rejectsMaliciousDeploymentNames(String name) {
        assertThatThrownBy(() -> repo.write(HOST, name, "x", "y")).isInstanceOf(PathValidationException.class);
        assertThatThrownBy(() -> repo.readCompose(HOST, name)).isInstanceOf(PathValidationException.class);
        assertThatThrownBy(() -> repo.readEnv(HOST, name)).isInstanceOf(PathValidationException.class);
        assertThatThrownBy(() -> repo.delete(HOST, name)).isInstanceOf(PathValidationException.class);
        assertThatThrownBy(() -> repo.repoDir(HOST, name)).isInstanceOf(PathValidationException.class);
    }

    @Test
    void rejectsDeploymentDirectoryThatIsASymlinkOutsideBase(@TempDir Path outside) throws IOException {
        Files.createDirectories(base.resolve(HOST.toString()));
        Files.createSymbolicLink(base.resolve(HOST.toString()).resolve("web"), outside);

        assertThatThrownBy(() -> repo.write(HOST, "web", "x", "y")).isInstanceOf(PathValidationException.class);
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    void repoDirIsSubdirectoryOfDeploymentDir() {
        assertThat(repo.repoDir(HOST, "web")).isEqualTo(repo.deploymentDir(HOST, "web").resolve("repo"));
    }

    @Test
    void neverLogsEnvContent() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            repo.write(HOST, "web", "x", "DB_PASSWORD=topsecret-value");
            repo.readEnv(HOST, "web");
            assertThatThrownBy(() -> repo.write(HOST, "../x", "x", "DB_PASSWORD=topsecret-value"))
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("topsecret"));
            repo.delete(HOST, "web");
        } finally {
            root.detachAppender(appender);
        }
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("topsecret"));
    }
}
