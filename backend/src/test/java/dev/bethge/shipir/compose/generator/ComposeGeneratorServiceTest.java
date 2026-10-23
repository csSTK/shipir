package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.compose.generator.model.ComposeSpec;
import dev.bethge.shipir.compose.generator.model.EnvVarSpec;
import dev.bethge.shipir.compose.generator.model.NetworkSpec;
import dev.bethge.shipir.compose.generator.model.PortSpec;
import dev.bethge.shipir.compose.generator.model.ServiceSpec;
import dev.bethge.shipir.compose.generator.model.VolumeMountSpec;
import dev.bethge.shipir.compose.generator.model.VolumeSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeGeneratorServiceTest {

    private final ComposeGeneratorService generator = new ComposeGeneratorService();

    static ServiceSpec web() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("com.example.tier", "frontend");
        labels.put("price", "$5");
        return new ServiceSpec("web", "nginx:1.27", List.of("nginx", "-g", "daemon off;"), "unless-stopped",
                List.of(new PortSpec(8080, 80, "tcp"), new PortSpec(null, 53, "udp")),
                List.of(new EnvVarSpec("APP_ENV", "production", null), new EnvVarSpec("DB_PASSWORD", null, "DB_PASSWORD")),
                List.of(new VolumeMountSpec("./html", "/usr/share/nginx/html", true),
                        new VolumeMountSpec("data/files", "/var/cache", false),
                        new VolumeMountSpec("cache", "/cache", false)),
                List.of("front"), labels, List.of("db"));
    }

    static ServiceSpec db() {
        return new ServiceSpec("db", "postgres:16", null, "always", null,
                List.of(new EnvVarSpec("POSTGRES_PASSWORD", null, "DB_PASSWORD")),
                List.of(new VolumeMountSpec("pgdata", "/var/lib/postgresql/data", false)),
                List.of("front"), null, null);
    }

    static ComposeSpec example() {
        return new ComposeSpec(List.of(web(), db()),
                List.of(new NetworkSpec("front", "bridge", false), new NetworkSpec("shared", null, true)),
                List.of(new VolumeSpec("pgdata", null, false), new VolumeSpec("cache", "local", false)));
    }

    @Test
    void goldenYamlForMultiServiceExample() {
        assertThat(generator.generate(example())).isEqualTo("""
                services:
                  web:
                    image: nginx:1.27
                    command:
                      - nginx
                      - -g
                      - daemon off;
                    restart: unless-stopped
                    ports:
                      - 8080:80/tcp
                      - 53/udp
                    environment:
                      APP_ENV: production
                      DB_PASSWORD: "${DB_PASSWORD}"
                    volumes:
                      - ./html:/usr/share/nginx/html:ro
                      - ./data/files:/var/cache
                      - cache:/cache
                    networks:
                      - front
                    labels:
                      com.example.tier: frontend
                      price: $$5
                    depends_on:
                      - db
                  db:
                    image: postgres:16
                    restart: always
                    environment:
                      POSTGRES_PASSWORD: "${DB_PASSWORD}"
                    volumes:
                      - pgdata:/var/lib/postgresql/data
                    networks:
                      - front
                networks:
                  front:
                    driver: bridge
                  shared:
                    external: true
                volumes:
                  pgdata: {}
                  cache:
                    driver: local
                """);
    }

    @Test
    void outputIsStableAcrossRuns() {
        assertThat(generator.generate(example())).isEqualTo(generator.generate(example()));
    }

    @Test
    void minimalServiceOnly() {
        ComposeSpec spec = new ComposeSpec(
                List.of(new ServiceSpec("app", "alpine", null, null, null, null, null, null, null, null)), null, null);
        assertThat(generator.generate(spec)).isEqualTo("services:\n  app:\n    image: alpine\n");
    }

    @Test
    void literalDollarInEnvironmentIsEscaped() {
        ServiceSpec s = new ServiceSpec("app", "alpine", null, null, null,
                List.of(new EnvVarSpec("TOKEN", "a$b", null)), null, null, null, null);
        assertThat(generator.generate(new ComposeSpec(List.of(s), null, null))).contains("TOKEN: a$$b");
    }

    private ComposeSpec withService(UnaryOperator<ServiceSpec> change) {
        return new ComposeSpec(List.of(change.apply(new ServiceSpec("app", "alpine", null, null, null, null, null,
                null, null, null))), null, null);
    }

    private void assertRejected(ComposeSpec spec, String fragment) {
        assertThatThrownBy(() -> generator.generate(spec))
                .isInstanceOfSatisfying(InvalidComposeSpecException.class,
                        e -> assertThat(e.violations()).anyMatch(v -> v.contains(fragment)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 99999})
    void invalidContainerPortRejected(int port) {
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null,
                List.of(new PortSpec(null, port, "tcp")), null, null, null, null, null)), "container port");
    }

    @Test
    void invalidHostPortAndProtocolRejected() {
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null,
                List.of(new PortSpec(70000, 80, "tcp")), null, null, null, null, null)), "host port");
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null,
                List.of(new PortSpec(80, 80, "sctp")), null, null, null, null, null)), "protocol");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Web", "-web", "we b", "web/../x", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidServiceNameRejected(String name) {
        ComposeSpec spec = new ComposeSpec(
                List.of(new ServiceSpec(name, "alpine", null, null, null, null, null, null, null, null)), null, null);
        assertRejected(spec, "invalid name");
    }

    @Test
    void invalidRestartRejected() {
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, "sometimes", null, null, null,
                null, null, null)), "restart");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc", "/", "../secrets", "data/../../x", "a/%2e%2e/b", "C:/Windows", "data\\x", "./a:b"})
    void absoluteAndTraversingBindPathsRejected(String source) {
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null, null,
                List.of(new VolumeMountSpec(source, "/mnt", false)), null, null, null)), "volumes");
    }

    @Test
    void undeclaredNetworkVolumeAndDependencyRejected() {
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null, null,
                List.of(new VolumeMountSpec("nope", "/mnt", false)), null, null, null)), "named volume");
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null, null, null,
                List.of("nope"), null, null)), "network");
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null, null, null, null,
                null, List.of("nope"))), "unknown service");
    }

    @Test
    void invalidEnvironmentRejected() {
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null,
                List.of(new EnvVarSpec("1BAD", "x", null)), null, null, null, null)), "variable name");
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null,
                List.of(new EnvVarSpec("A", "x", "B")), null, null, null, null)), "exactly one");
        assertRejected(withService(s -> new ServiceSpec(s.name(), s.image(), null, null, null,
                List.of(new EnvVarSpec("A", null, null)), null, null, null, null)), "exactly one");
    }

    @Test
    void dependencyCycleRejected() {
        ServiceSpec a = new ServiceSpec("a", "x", null, null, null, null, null, null, null, List.of("b"));
        ServiceSpec b = new ServiceSpec("b", "x", null, null, null, null, null, null, null, List.of("a"));
        assertRejected(new ComposeSpec(List.of(a, b), null, null), "cycle");
    }

    @Test
    void duplicateServiceAndExternalWithDriverRejected() {
        ServiceSpec a = new ServiceSpec("a", "x", null, null, null, null, null, null, null, null);
        assertRejected(new ComposeSpec(List.of(a, a), null, null), "duplicate");
        assertRejected(new ComposeSpec(List.of(a), List.of(new NetworkSpec("n", "bridge", true)), null), "external");
    }

    @Test
    void emptyServicesRejected() {
        assertRejected(new ComposeSpec(List.of(), null, null), "at least one service");
    }

    @Test
    void allViolationsReportedTogether() {
        ServiceSpec bad = new ServiceSpec("app", "alpine", null, "x", List.of(new PortSpec(null, 0, null)), null,
                null, null, null, null);
        assertThatThrownBy(() -> generator.generate(new ComposeSpec(List.of(bad), null, null)))
                .isInstanceOfSatisfying(InvalidComposeSpecException.class,
                        e -> assertThat(e.violations()).hasSize(2));
    }
}
