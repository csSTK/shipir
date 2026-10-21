package dev.bethge.shipir;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resolves the real application.yml against a clean environment (no system env vars), so the
 * datasource defaults and overrides are checked without starting a Spring context or a database.
 */
class DatasourcePropertiesTest {

    @Test
    void usesLocalDefaultsWhenOnlyPasswordIsSet() throws IOException {
        MockEnvironment environment = environmentWith(Map.of("DB_PASSWORD", "secret"));

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/shipir");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("shipir");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void overridesHostPortNameAndUserFromEnvironment() throws IOException {
        MockEnvironment environment = environmentWith(Map.of(
                "DB_PASSWORD", "secret",
                "DB_HOST", "db.internal",
                "DB_PORT", "5433",
                "DB_NAME", "shipir_test",
                "DB_USER", "alice"));

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.internal:5433/shipir_test");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("alice");
    }

    @Test
    void failsWhenPasswordIsNotProvided() throws IOException {
        MockEnvironment environment = environmentWith(Map.of());

        assertThatThrownBy(() -> environment.getProperty("spring.datasource.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    /** Overrides win over application.yml; no system environment variables are read. */
    private static MockEnvironment environmentWith(Map<String, String> overrides) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("overrides", new HashMap<>(overrides)));
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
