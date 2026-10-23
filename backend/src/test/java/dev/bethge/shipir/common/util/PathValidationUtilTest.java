package dev.bethge.shipir.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PathValidationUtilTest {

    @TempDir
    Path tmp;

    Path base;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(tmp.resolve("base"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../x", "a/../../x", "..", "a/..", "/etc/passwd", "C:/Windows", "%2e%2e/x", "%2E%2E%2Fx", "a%2fb",
        "%252e%252e/x", "a\0b", "..\\x", "a\\b"
    })
    @NullAndEmptySource
    void rejectsMaliciousInput(String input) {
        assertThatThrownBy(() -> PathValidationUtil.resolve(base, input))
            .isInstanceOf(PathValidationException.class);
    }

    @Test
    void acceptsValidNestedPaths() {
        assertThat(PathValidationUtil.resolve(base, "stack/compose.yaml"))
            .isEqualTo(base.toAbsolutePath().normalize().resolve("stack/compose.yaml"));
        assertThat(PathValidationUtil.resolve(base, "a/./b/c.env").toString()).endsWith("a/b/c.env");
    }

    @Test
    void rejectsSymlinkPointingOutsideBase() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(base.resolve("link"), outside);
        assertThatThrownBy(() -> PathValidationUtil.resolve(base, "link"))
            .isInstanceOf(PathValidationException.class);
        assertThatThrownBy(() -> PathValidationUtil.resolve(base, "link/not-yet-created/file.yaml"))
            .isInstanceOf(PathValidationException.class);
    }

    @Test
    void allowsSymlinkPointingInsideBase() throws IOException {
        Path target = Files.createDirectories(base.resolve("real"));
        Files.createSymbolicLink(base.resolve("alias"), target);
        assertThat(PathValidationUtil.resolve(base, "alias/file.yaml").toString()).endsWith("alias/file.yaml");
    }

    @Test
    void allowsBaseDirReachedViaSymlink() throws IOException {
        Path linkedBase = Files.createSymbolicLink(tmp.resolve("linked-base"), base);
        assertThat(PathValidationUtil.resolve(linkedBase, "s/f.yaml").toString()).endsWith("s/f.yaml");
    }

    @Test
    void messageDoesNotEchoInput() {
        assertThatThrownBy(() -> PathValidationUtil.resolve(base, "../secret-marker"))
            .hasMessageNotContaining("secret-marker");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "web", "my-stack_1", "0abc", "a-b_c"})
    void acceptsValidStackNames(String name) {
        assertThat(PathValidationUtil.validateStackName(name)).isEqualTo(name);
    }

    @Test
    void acceptsMaxLengthStackName() {
        assertThat(PathValidationUtil.validateStackName("a".repeat(63))).hasSize(63);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Web", "my.stack", "a/b", "a\\b", "-abc", "_abc", "a b", ".", "..", "a\0", "ä"})
    @NullAndEmptySource
    void rejectsInvalidStackNames(String name) {
        assertThatThrownBy(() -> PathValidationUtil.validateStackName(name))
            .isInstanceOf(PathValidationException.class);
    }

    @Test
    void rejectsTooLongStackName() {
        assertThatThrownBy(() -> PathValidationUtil.validateStackName("a".repeat(64)))
            .isInstanceOf(PathValidationException.class);
    }
}
