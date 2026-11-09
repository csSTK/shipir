package dev.bethge.shipir.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** application.yml declares shipir.security.trusted-proxies and its default trusts nobody. */
class TrustedProxiesDefaultsTest {

    private static AuthThrottleProperties bind(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yaml.forEach(env.getPropertySources()::addLast);
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return Binder.get(env).bind("shipir.security", AuthThrottleProperties.class).get();
    }

    @Test
    void defaultTrustsNoProxy() throws IOException {
        assertThat(bind(Map.of()).trustedProxies()).isEmpty();
    }

    @Test
    void commaSeparatedEnvValueBinds() throws IOException {
        assertThat(bind(Map.of("SHIPIR_SECURITY_TRUSTED_PROXIES", "172.28.77.0/24, 10.0.0.1")).trustedProxies())
                .containsExactly("172.28.77.0/24", "10.0.0.1");
    }
}
