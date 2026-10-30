package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.proxy.ProxyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraefikCustomConfigValidatorTest {

    private final TraefikCustomConfigValidator validator = new TraefikCustomConfigValidator();

    private void validate(String content) {
        validator.validate(UUID.randomUUID(), content, List.of());
    }

    private void assertInvalid(String content, String messagePart) {
        assertThatThrownBy(() -> validate(content)).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo("INVALID_PROXY_CONFIG");
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    @Test
    void emptyAndCommentOnlyContentIsValid() {
        assertThatCode(() -> validate("")).doesNotThrowAnyException();
        assertThatCode(() -> validate("# nothing here\n")).doesNotThrowAnyException();
    }

    @Test
    void allowsHttpTcpUdpAndTlsSections() {
        assertThatCode(() -> validate("""
                http:
                  middlewares:
                    sec:
                      headers:
                        frameDeny: true
                tcp:
                  routers: {}
                udp:
                  routers: {}
                tls:
                  options: {}
                """)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"entryPoints", "providers", "api", "log", "certificatesResolvers", "experimental"})
    void staticConfigurationSectionsAreRejected(String key) {
        assertInvalid(key + ":\n  x: 1\n", "\"" + key + "\"");
    }

    @Test
    void invalidYamlIsRejectedWithTheParserMessage() {
        assertInvalid("http: [unclosed\n", "not valid YAML");
    }

    @Test
    void rootMustBeAMapping() {
        assertInvalid("- http\n- tcp\n", "mapping");
        assertInvalid("just a string\n", "mapping");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertInvalid("http: {}\nhttp: {}\n", "not valid YAML");
    }

    @Test
    void goTemplatesAreRejectedBecauseTraefikEvaluatesThem() {
        assertInvalid("http:\n  middlewares:\n    x:\n      headers:\n        customRequestHeaders:\n"
                + "          X: '{{ env \"SECRET\" }}'\n", "{{");
    }

    @Test
    void oversizedContentIsRejected() {
        String big = "http:\n  # " + "a".repeat(CustomConfigValidator.MAX_BYTES) + "\n";
        assertInvalid(big, "too large");
    }

    @Test
    void yamlAliasBombsAreRejected() {
        StringBuilder sb = new StringBuilder("tls:\n  a: &a [x,x,x,x,x,x,x,x,x]\n");
        String prev = "a";
        for (char c = 'b'; c <= 'j'; c++) {
            sb.append("  ").append(c).append(": &").append(c).append(" [*").append(prev).append(",*").append(prev)
                    .append(",*").append(prev).append(",*").append(prev).append("]\n");
            prev = String.valueOf(c);
        }
        assertInvalid(sb.toString(), "alias");
    }
}
