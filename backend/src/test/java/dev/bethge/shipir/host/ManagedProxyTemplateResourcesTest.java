package dev.bethge.shipir.host;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ManagedProxyTemplateResourcesTest {

    private static final ManagedProxyTemplateResources.Resources EMPTY =
            new ManagedProxyTemplateResources.Resources(Set.of(), Set.of());

    @Test
    void malformedYamlYieldsEmptyResources() {
        assertThat(ManagedProxyTemplateResources.parse("services: [unclosed")).isEqualTo(EMPTY);
        assertThat(ManagedProxyTemplateResources.parse("services:\n  web:\n    image: [\n")).isEqualTo(EMPTY);
    }

    @Test
    void unparseableYamlIsLoggedAsWarningWithoutContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedProxyTemplateResources.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ManagedProxyTemplateResources.parse("services: [unclosed-secret-token-xyz");
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat))
                .doesNotContain("unclosed-secret-token-xyz");
    }

    @Test
    void nonMappingDocumentYieldsEmptyResources() {
        assertThat(ManagedProxyTemplateResources.parse("- just\n- a list\n")).isEqualTo(EMPTY);
        assertThat(ManagedProxyTemplateResources.parse("plain scalar")).isEqualTo(EMPTY);
        assertThat(ManagedProxyTemplateResources.parse("")).isEqualTo(EMPTY);
    }

    @Test
    void aliasBombIsRejectedWithoutHanging() {
        String bomb = billionLaughs(9, 9);

        ManagedProxyTemplateResources.Resources result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> ManagedProxyTemplateResources.parse(bomb));

        assertThat(result).isEqualTo(EMPTY);
    }

    @Test
    void composeWithoutServicesYieldsNoImages() {
        ManagedProxyTemplateResources.Resources result = ManagedProxyTemplateResources.parse(
                "name: proxy\nnetworks:\n  front: {}\n");

        assertThat(result.images()).isEmpty();
        assertThat(result.networks()).containsExactly("front");
    }

    @Test
    void documentWithoutServicesOrNetworksYieldsEmptyResources() {
        assertThat(ManagedProxyTemplateResources.parse("name: proxy\nversion: '3'\n")).isEqualTo(EMPTY);
    }

    @Test
    void networkNameFieldWinsOverKey() {
        ManagedProxyTemplateResources.Resources result = ManagedProxyTemplateResources.parse("""
                networks:
                  front:
                    name: shipir-proxy
                """);

        assertThat(result.networks()).containsExactly("shipir-proxy");
    }

    @Test
    void networkKeyIsUsedWhenNoNameIsGiven() {
        ManagedProxyTemplateResources.Resources result = ManagedProxyTemplateResources.parse("""
                networks:
                  shipir-proxy:
                    external: true
                  internal:
                  front:
                    external: true
                """);

        assertThat(result.networks()).containsExactlyInAnyOrder("shipir-proxy", "internal", "front");
    }

    @Test
    void networkNameFieldIgnoresNonStringName() {
        ManagedProxyTemplateResources.Resources result = ManagedProxyTemplateResources.parse("""
                networks:
                  front:
                    name: 42
                """);

        assertThat(result.networks()).containsExactly("front");
    }

    @Test
    void readsImagesOfServicesAndSkipsServicesWithoutImage() {
        ManagedProxyTemplateResources.Resources result = ManagedProxyTemplateResources.parse("""
                services:
                  proxy:
                    image: traefik:v3.5.3
                  builder:
                    build: .
                  caddy:
                    image: caddy:2.10.2-alpine
                """);

        assertThat(result.images()).containsExactlyInAnyOrder("traefik:v3.5.3", "caddy:2.10.2-alpine");
    }

    @Test
    void discoveryReadsEveryManagedTemplateOnTheClasspath() {
        ManagedProxyTemplateResources.Resources all = ManagedProxyTemplateResources.load(
                "classpath*:templates/proxy/managed-*.yaml");

        assertThat(all.images()).contains("traefik:v3.5.3", "caddy:2.10.2-alpine");
        assertThat(all.networks()).contains("shipir-proxy");
    }

    @Test
    void everyManagedTemplateOnTheClasspathIsCovered() throws IOException {
        Resource[] templates = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:templates/proxy/managed-*.yaml");
        assertThat(templates).hasSizeGreaterThanOrEqualTo(2);

        ManagedProxyTemplateResources.Resources bundled = ManagedProxyTemplateResources.bundled();
        for (Resource template : templates) {
            String yaml;
            try (InputStream in = template.getInputStream()) {
                yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            ManagedProxyTemplateResources.Resources declared = ManagedProxyTemplateResources.parse(yaml);
            assertThat(declared.images()).as(template.getFilename()).isNotEmpty();
            assertThat(bundled.images()).as(template.getFilename()).containsAll(declared.images());
            assertThat(bundled.networks()).as(template.getFilename()).containsAll(declared.networks());
        }
    }

    @Test
    void missingTemplatesFailWithAnIllegalStateExceptionNotAnInitializerError() {
        assertThatThrownBy(() -> ManagedProxyTemplateResources.load("classpath*:templates/proxy/missing-*.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-*.yaml");
    }

    /** Nested alias expansion: each level references the previous one {@code fanout} times. */
    private static String billionLaughs(int levels, int fanout) {
        StringBuilder sb = new StringBuilder("services:\n  web:\n    image: nginx:1.27\n    environment:\n");
        sb.append("      l0: &a0 [\"lol\"");
        for (int i = 1; i < fanout; i++) {
            sb.append(", \"lol\"");
        }
        sb.append("]\n");
        for (int level = 1; level <= levels; level++) {
            sb.append("      l").append(level).append(": &a").append(level).append(" [");
            for (int i = 0; i < fanout; i++) {
                sb.append(i == 0 ? "" : ", ").append("*a").append(level - 1);
            }
            sb.append("]\n");
        }
        return sb.toString();
    }
}
