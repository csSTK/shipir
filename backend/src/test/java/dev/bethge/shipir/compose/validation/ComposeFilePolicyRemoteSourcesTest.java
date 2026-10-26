package dev.bethge.shipir.compose.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compose (v5.1.3) reads {@code label_file}, runs {@code provider} binaries and fetches remote {@code include}
 * sources (git/oci/github.com shorthand) on the Shipir host. Verified with {@code docker compose config}.
 */
class ComposeFilePolicyRemoteSourcesTest {

    @TempDir
    Path root;

    private Path dir;
    private Path compose;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(root.resolve("hostA/web"));
        compose = dir.resolve("compose.yaml");
        Files.createDirectories(root.resolve("hostA/other"));
        Files.writeString(root.resolve("hostA/other/l.env"), "A=1\n");
        Files.writeString(dir.resolve("l.env"), "A=1\n");
    }

    private List<String> check(String yaml) throws IOException {
        Files.writeString(compose, yaml);
        return ComposeFilePolicy.violations(compose, dir, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "../other/l.env", "'${HOME}/l.env'", "'~/l.env'"})
    void rejectsLabelFileOutsideTheDeploymentDirectory(String path) throws IOException {
        assertThat(check("services:\n  x:\n    image: a\n    label_file: " + path + "\n")).isNotEmpty();
    }

    @Test
    void rejectsLabelFileListAndAllowsLocalOne() throws IOException {
        assertThat(check("services:\n  x:\n    image: a\n    label_file: [l.env, ../other/l.env]\n")).hasSize(1);
        assertThat(check("services:\n  x:\n    image: a\n    label_file: l.env\n")).isEmpty();
    }

    @Test
    void rejectsProviderServicesBecauseTheyRunABinaryOnTheShipirHost() throws IOException {
        assertThat(check("services:\n  p:\n    provider:\n      type: /bin/echo\n")).isNotEmpty();
        assertThat(check("services:\n  p:\n    provider:\n      type: model\n      options: {a: b}\n")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"github.com/docker/awesome-compose", "oci://docker.io/x/y:1", "git@github.com:x/y.git",
            "https://github.com/x/y.git", "GitHub.com/x/y", "./github.com/x/y"})
    void rejectsRemoteIncludeForms(String source) throws IOException {
        assertThat(check("include:\n  - " + source + "\nservices: {}\n")).isNotEmpty();
        assertThat(check("include:\n  - path: " + source + "\nservices: {}\n")).isNotEmpty();
        assertThat(check("include:\n  - path: [a.yaml, " + source + "]\nservices: {}\n")).isNotEmpty();
    }

    @Test
    void stillAllowsLocalIncludes() throws IOException {
        Files.writeString(dir.resolve("a.yaml"), "services: {}\n");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.yaml"), "services: {}\n");
        assertThat(check("include:\n  - a.yaml\n  - ./sub/b.yaml\nservices: {}\n")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"github.com/docker/awesome-compose", "oci://docker.io/x/y:1", "git@github.com:x/y.git",
            "GitHub.com/x/y", "./github.com/x/y"})
    void rejectsRemoteExtendsFileForms(String source) throws IOException {
        assertThat(check("services:\n  x:\n    extends:\n      file: " + source + "\n      service: s\n")).isNotEmpty();
        assertThat(check("services:\n  x:\n    extends:\n      file: [" + source + "]\n      service: s\n")).isNotEmpty();
    }

    @Test
    void stillAllowsLocalExtendsFile() throws IOException {
        Files.writeString(dir.resolve("base.yaml"), "services:\n  s:\n    image: a\n");
        assertThat(check("services:\n  x:\n    extends:\n      file: ./base.yaml\n      service: s\n")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"conf.d/a.yaml", "./conf.d/a.yaml", "my.app/base.yaml"})
    void allowsLocalPathsWithADotInTheFirstDirectory(String path) throws IOException {
        Files.createDirectories(dir.resolve(path).getParent());
        Files.writeString(dir.resolve(path), "services:\n  s:\n    image: a\n");
        assertThat(check("include:\n  - " + path + "\nservices: {}\n")).isEmpty();
        assertThat(check("include:\n  - path: " + path + "\nservices: {}\n")).isEmpty();
        assertThat(check("services:\n  x:\n    extends:\n      file: " + path + "\n      service: s\n")).isEmpty();
    }
}
