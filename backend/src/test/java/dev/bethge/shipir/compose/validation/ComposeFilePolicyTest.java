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
 * compose inlines env_file contents into the resolved config, so the paths are only visible in the raw YAML. Layout:
 * {@code <root>/hostA/web/compose.yaml} is the deployment, {@code <root>/hostA/other/.env} belongs to someone else.
 */
class ComposeFilePolicyTest {

    @TempDir
    Path root;

    private Path dir;
    private Path compose;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(root.resolve("hostA/web"));
        compose = dir.resolve("compose.yaml");
        Files.createDirectories(root.resolve("hostA/other"));
        Files.writeString(root.resolve("hostA/other/.env"), "SECRET=1\n");
    }

    private List<String> check(String yaml) throws IOException {
        Files.writeString(compose, yaml);
        return ComposeFilePolicy.violations(compose, dir, true);
    }

    private static String envFile(String value) {
        return "services:\n  x:\n    image: alpine\n    env_file: " + value + "\n";
    }

    @ParameterizedTest
    @ValueSource(strings = {"/proc/self/environ", "/etc/passwd", "../other/.env", "./../other/.env", "sub/../../other/.env",
            "'${HOME}/.env'", "'~/.env'", "'https://example.com/x.env'"})
    void rejectsEnvFileOutsideTheDeploymentDirectory(String path) throws IOException {
        assertThat(check(envFile(path))).isNotEmpty();
    }

    @Test
    void rejectsOtherDeploymentsDotEnvInEveryEnvFileForm() throws IOException {
        assertThat(check("services:\n  x:\n    image: a\n    env_file: [ok.env, ../other/.env]\n")).hasSize(1);
        assertThat(check("services:\n  x:\n    image: a\n    env_file:\n      - path: ../other/.env\n        required: false\n"))
                .hasSize(1);
        assertThat(check("services:\n  x:\n    image: a\n    env_file:\n      path: ../other/.env\n")).hasSize(1);
    }

    @Test
    void rejectsEnvFileHiddenBehindAnchorAndMergeKey() throws IOException {
        String yaml = "x-common: &common\n  env_file: /proc/self/environ\nservices:\n  x:\n    <<: *common\n    image: a\n";
        assertThat(check(yaml)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "x-p: &p /proc/self/environ\nservices:\n  x:\n    image: a\n    env_file: *p\n",
            "x-l: &l [/proc/self/environ]\nservices:\n  x:\n    image: a\n    env_file: *l\n",
            "x-l: &l [{path: /proc/self/environ}]\nservices:\n  x:\n    image: a\n    env_file: *l\n",
            "x-p: &p /proc/self/environ\nservices:\n  x:\n    image: a\n    env_file: [{path: *p}]\n",
            "x-p: &p /proc/self/environ\nservices:\n  x:\n    image: a\n    env_file: [ok.env, *p]\n",
            "x-i: &i [../other/compose.yaml]\ninclude: *i\nservices: {}\n",
            "x-p: &p ../other/compose.yaml\ninclude:\n  - path: *p\nservices: {}\n",
            "x-f: &f ../other/compose.yaml\nservices:\n  x:\n    extends:\n      file: *f\n      service: y\n",
            "x-f: &f /etc/passwd\nsecrets:\n  s:\n    file: *f\nservices: {}\n",
            "x-f: &f /etc/passwd\nconfigs:\n  c:\n    file: *f\nservices: {}\n",
            "x-s: &s {s: {file: /etc/passwd}}\nsecrets: *s\nservices: {}\n",
            "x-b: &b {file: /etc/passwd}\nsecrets:\n  s:\n    <<: *b\nservices: {}\n",
            "x-b: &b {include: [../other/compose.yaml]}\n<<: *b\nservices: {}\n",
            "x-i: &i {include: [../other/compose.yaml]}\n<<: [*i]\nservices: {}\n",
            "x-s: &s {secrets: {a: {file: /etc/passwd}}}\n<<: [*s]\nservices: {}\n",
            "x-a: &a {restart: always}\nx-s: &s {secrets: {a: {file: /etc/passwd}}}\n<<: [*a, *s]\nservices: {}\n",
            "x-a: &y {include: [/etc/passwd]}\n<<: {<<: *y}\nservices: {}\n",
            "x-a: &y {include: [/etc/passwd]}\n<<: [{<<: *y}]\nservices: {}\n",
            "<<: {include: [/etc/passwd]}\nservices: {}\n",
            "<<: [{secrets: {a: {file: /etc/passwd}}}]\nservices: {}\n",
            "secrets:\n  s:\n    <<: {file: /etc/passwd}\nservices: {}\n"})
    void rejectsAliasesWhereAFilePathIsExpected(String yaml) throws IOException {
        assertThat(check(yaml)).isNotEmpty();
    }

    @Test
    void aliasCheckThatCannotRunNeverPassesSilently() throws IOException {
        assertThat(ComposeFilePolicy.aliasViolations("services: [unclosed\n")).isEmpty();
        assertThat(ComposeFilePolicy.aliasViolations("services: {}\n")).hasValue(List.of());
        assertThat(ComposeFilePolicy.aliasViolations("a: &x 1\nb: *x\n")).hasValue(List.of());
    }

    @Test
    void aliasesElsewhereAndMergeKeysOfMappingsStayAllowed() throws IOException {
        assertThat(check("x-img: &img alpine\nservices:\n  x:\n    image: *img\n  y:\n    image: *img\n")).isEmpty();
        assertThat(check("x-c: &c {restart: always}\nservices:\n  x:\n    <<: *c\n    image: a\n")).isEmpty();
    }

    @Test
    void allowsEnvFileInsideTheDeploymentDirectory() throws IOException {
        Files.writeString(dir.resolve("app.env"), "A=1\n");
        assertThat(check(envFile("app.env"))).isEmpty();
        assertThat(check(envFile("./sub/missing.env"))).isEmpty();
        assertThat(check(envFile("[.env, " + dir.resolve("app.env") + "]"))).isEmpty();
    }

    @Test
    void rejectsSymlinkedEnvFileLeavingTheDirectory() throws IOException {
        Files.createSymbolicLink(dir.resolve("link.env"), root.resolve("hostA/other/.env"));
        assertThat(check(envFile("link.env"))).hasSize(1);
        Files.createSymbolicLink(dir.resolve("linkdir"), root.resolve("hostA/other"));
        assertThat(check(envFile("linkdir/.env"))).hasSize(1);
    }

    @Test
    void rejectsIncludeAndExtendsFilesOutsideTheDirectory() throws IOException {
        assertThat(check("include:\n  - ../other/compose.yaml\nservices: {}\n")).isNotEmpty();
        assertThat(check("include:\n  - path: ../other/compose.yaml\n    env_file: ../other/.env\nservices: {}\n")).hasSize(2);
        assertThat(check("include:\n  - path: [a.yaml, /etc/x.yaml]\nservices: {}\n")).isNotEmpty();
        assertThat(check("include:\n  - path: a.yaml\n    project_directory: ../other\nservices: {}\n")).isNotEmpty();
        assertThat(check("services:\n  x:\n    extends:\n      file: ../other/compose.yaml\n      service: y\n")).isNotEmpty();
        assertThat(check("include:\n  - git://github.com/x/y.git\nservices: {}\n")).isNotEmpty();
    }

    @Test
    void allowsExtendsWithinTheSameFileAndLocalIncludes() throws IOException {
        Files.writeString(dir.resolve("part.yaml"), "services:\n  y:\n    image: a\n");
        assertThat(check("include:\n  - part.yaml\nservices:\n  x:\n    extends:\n      service: y\n")).isEmpty();
    }

    @Test
    void followsIncludedFilesAndChecksTheirPathsRelativeToThem() throws IOException {
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/inc.yaml"), "services:\n  y:\n    image: a\n    env_file: ../../other/.env\n");
        assertThat(check("include:\n  - sub/inc.yaml\nservices: {}\n")).isNotEmpty();
        Files.writeString(dir.resolve("sub/inc.yaml"), "services:\n  y:\n    image: a\n    env_file: ../app.env\n");
        assertThat(check("include:\n  - sub/inc.yaml\nservices: {}\n")).isEmpty();
    }

    @Test
    void survivesIncludeCycles() throws IOException {
        Files.writeString(dir.resolve("a.yaml"), "include:\n  - compose.yaml\nservices: {}\n");
        assertThat(check("include:\n  - a.yaml\nservices: {}\n")).isEmpty();
    }

    @Test
    void relativeEnvFileOfAGitCheckoutResolvesAgainstTheComposeFilesDirectory() throws IOException {
        Path repo = Files.createDirectories(dir.resolve("repo/deploy"));
        Path file = repo.resolve("compose.yaml");
        Files.writeString(file, envFile("../../../other/.env"));
        assertThat(ComposeFilePolicy.violations(file, dir, true)).isNotEmpty();
        Files.writeString(file, envFile("../common.env"));
        assertThat(ComposeFilePolicy.violations(file, dir, true)).isEmpty();
    }

    @Test
    void unparsableYamlFailsClosedOnlyWhenAsked() throws IOException {
        Files.writeString(compose, "services: [unclosed\n");
        assertThat(ComposeFilePolicy.violations(compose, dir, true)).isNotEmpty();
        assertThat(ComposeFilePolicy.violations(compose, dir, false)).isEmpty();
    }

    @Test
    void jsonOutputRejectsSecretAndConfigFilesOutsideTheDirectory() throws IOException {
        String json = "{\"name\":\"p\",\"secrets\":{\"s\":{\"file\":\"" + root.resolve("hostA/other/.env") + "\"}},"
                + "\"configs\":{\"c\":{\"file\":\"/etc/passwd\"}}}";
        List<String> violations = ComposeSecurityPolicy.violationsOf(
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(json), dir);
        assertThat(violations).hasSize(2);
        String ok = "{\"name\":\"p\",\"secrets\":{\"s\":{\"file\":\"" + dir.resolve("s.txt") + "\"}}}";
        assertThat(ComposeSecurityPolicy.violationsOf(
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(ok), dir)).isEmpty();
    }
}
