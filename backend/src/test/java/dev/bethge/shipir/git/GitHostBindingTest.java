package dev.bethge.shipir.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHostBindingTest {

    private static boolean matches(String credentialHost, String url) {
        return GitHostBinding.matches(credentialHost, URI.create(url));
    }

    @Test
    void normalizeLowercasesTrimsAndDropsTrailingDot() {
        assertThat(GitHostBinding.normalize("  GitHub.COM. ")).isEqualTo("github.com");
        assertThat(GitHostBinding.normalize("git.example.org:8443")).isEqualTo("git.example.org:8443");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "https://github.com", "github.com/path", "user@github.com", "github.com:", "github.com:0",
            "github.com:99999", "a..b", "-bad.com", "bad-.com", "git hub.com", "github.com\\evil", "*.github.com", "[::1]"})
    void normalizeRejectsAnythingButAHostWithOptionalPort(String input) {
        assertThatThrownBy(() -> GitHostBinding.normalize(input)).isInstanceOf(GitException.class)
                .extracting(e -> ((GitException) e).code()).isEqualTo("INVALID_CREDENTIAL_HOST");
    }

    @Test
    void matchesExactHostIgnoringCaseAndTrailingDot() {
        assertThat(matches("github.com", "https://github.com/o/r.git")).isTrue();
        assertThat(matches("github.com", "https://GITHUB.com/o/r.git")).isTrue();
        assertThat(matches("github.com", "https://github.com./o/r.git")).isTrue();
    }

    @Test
    void doesNotMatchSubdomainsParentsOrLookalikes() {
        assertThat(matches("github.com", "https://evil.example/o/r.git")).isFalse();
        assertThat(matches("github.com", "https://api.github.com/o/r.git")).isFalse();
        assertThat(matches("github.com", "https://github.com.evil.example/o/r.git")).isFalse();
        assertThat(matches("github.com", "https://evilgithub.com/o/r.git")).isFalse();
        assertThat(matches("github.com", "https://evil.example/github.com/r.git")).isFalse();
        assertThat(matches("github.com", "https://evil.example/r.git?h=github.com#github.com")).isFalse();
    }

    @Test
    void userinfoTricksUseTheRealHost() {
        assertThat(matches("github.com", "https://github.com@evil.example/r.git")).isFalse();
        assertThat(matches("github.com", "https://github.com:443@evil.example/r.git")).isFalse();
        assertThat(matches("evil.example", "https://github.com@evil.example/r.git")).isTrue();
    }

    @Test
    void portOnlyMattersWhenTheCredentialNamesOne() {
        assertThat(matches("github.com", "https://github.com:8443/r.git")).isTrue();
        assertThat(matches("git.example.org:8443", "https://git.example.org:8443/r.git")).isTrue();
        assertThat(matches("git.example.org:8443", "https://git.example.org/r.git")).isFalse();
        assertThat(matches("git.example.org:8443", "https://git.example.org:9443/r.git")).isFalse();
        assertThat(matches("git.example.org:443", "https://git.example.org/r.git")).isTrue();
    }

    @Test
    void missingBindingOrHostNeverMatches() {
        assertThat(GitHostBinding.matches(null, URI.create("https://github.com/r.git"))).isFalse();
        assertThat(GitHostBinding.matches("", URI.create("https://github.com/r.git"))).isFalse();
        assertThat(matches("github.com", "mailto:someone@github.com")).isFalse();
    }
}
