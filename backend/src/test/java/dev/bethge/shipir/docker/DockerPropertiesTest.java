package dev.bethge.shipir.docker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds the real application.yml (no context) to check the shipir.docker.* defaults. */
class DockerPropertiesTest {

    @Test
    void applicationYmlDefaultsMatchDocumentedValues() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        DockerProperties properties = new Binder(ConfigurationPropertySources.get(environment))
                .bind("shipir.docker", DockerProperties.class).get();

        assertThat(properties.localSocket()).isEqualTo("unix:///var/run/docker.sock");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
