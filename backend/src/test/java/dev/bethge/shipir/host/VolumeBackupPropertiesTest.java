package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backup/restore cap is its own property ({@code shipir.volume-backup.max-bytes}); the servlet multipart limit
 * only has to be at least as large, otherwise a restore upload would be cut off before the service can answer 413.
 */
class VolumeBackupPropertiesTest {

    private static Binder shippedConfig() throws Exception {
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return new Binder(ConfigurationPropertySources.get(env));
    }

    @Test
    void capIsItsOwnPropertyWithA100MbDefault() {
        var props = new Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("shipir.volume-backup", VolumeBackupProperties.class);
        assertThat(props.maxBytes()).isEqualTo(DataSize.ofMegabytes(100));
    }

    @Test
    void capIsBoundIndependentlyOfTheMultipartLimit() {
        var source = new MapPropertySource("t", Map.of(
                "shipir.volume-backup.max-bytes", "7MB",
                "spring.servlet.multipart.max-file-size", "201MB"));
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(source);
        var props = new Binder(ConfigurationPropertySources.get(env))
                .bindOrCreate("shipir.volume-backup", VolumeBackupProperties.class);
        assertThat(props.maxBytes()).isEqualTo(DataSize.ofMegabytes(7));
    }

    @Test
    void shippedMultipartLimitsAllowTheBackupCap() throws Exception {
        var binder = shippedConfig();
        var cap = binder.bindOrCreate("shipir.volume-backup", VolumeBackupProperties.class).maxBytes();
        var maxFile = binder.bind("spring.servlet.multipart.max-file-size", DataSize.class).get();
        var maxRequest = binder.bind("spring.servlet.multipart.max-request-size", DataSize.class).get();
        assertThat(maxFile).isGreaterThanOrEqualTo(cap);
        assertThat(maxRequest).isGreaterThanOrEqualTo(cap);
    }
}
