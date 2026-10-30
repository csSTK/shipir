package dev.bethge.shipir.proxy;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class ProxyRepositoryIntegrationTest {

    @Autowired DockerHostRepository hosts;
    @Autowired ProxyInstanceRepository instances;
    @Autowired ProxyRouteRepository routes;
    @Autowired ProxyCertificateRepository certificates;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    /** Forces the next read to hit the database instead of the first-level cache. */
    private void evict() {
        em.flush();
        em.clear();
    }

    private UUID newHost(String name) {
        String username = "u-" + UUID.randomUUID().toString().substring(0, 12);
        UUID owner = jdbc.queryForObject(
                "INSERT INTO users (username, password_hash, role) VALUES (?, 'x', 'ADMIN') RETURNING id", UUID.class, username);
        return hosts.saveAndFlush(DockerHost.tcp(name, name + ".internal", 2376, new byte[]{1}, new byte[]{1}, new byte[]{1}, owner)).getId();
    }

    private ProxyRoute route(UUID host, String hostname) {
        return ProxyRoute.create(host, hostname, List.of(), "web", 8080, BackendScheme.HTTP, null, null,
                CertMode.ACME_HTTP, null, true, null, null);
    }

    @Test
    void routeRoundTripKeepsArraysAndJson() {
        UUID host = newHost("h1");
        ProxyRoute r = ProxyRoute.create(host, "app.example.com", List.of("www.example.com", "x.example.com"), "web", 8080,
                BackendScheme.H2C, "/api", "websecure", CertMode.ACME_HTTP, null, false,
                Map.of("alice", "$2a$10$" + "a".repeat(53)), Map.of("X-Frame-Options", "DENY"));
        routes.saveAndFlush(r);
        UUID id = r.getId();
        // Plain JDBC proves the stored values; the lower-case scheme matches the V40 check.
        assertThat(jdbc.queryForObject("SELECT backend_scheme FROM proxy_routes WHERE id = ?", String.class, id)).isEqualTo("h2c");

        evict();
        ProxyRoute loaded = routes.findById(id).orElseThrow();
        assertThat(loaded.getAliases()).containsExactly("www.example.com", "x.example.com");
        assertThat(loaded.getBackendScheme()).isEqualTo(BackendScheme.H2C);
        assertThat(loaded.getResponseHeaders()).containsEntry("X-Frame-Options", "DENY");
        assertThat(loaded.getBasicAuthUsers()).containsKey("alice");
        assertThat(loaded.isRedirectHttps()).isFalse();
    }

    @Test
    void hostnameIsUniquePerHostButNotAcrossHosts() {
        UUID a = newHost("ha");
        UUID b = newHost("hb");
        routes.saveAndFlush(route(a, "app.example.com"));
        routes.saveAndFlush(route(b, "app.example.com"));
        assertThatThrownBy(() -> routes.saveAndFlush(route(a, "app.example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void takenNamesListHostnamesAndAliasesOfAHost() {
        UUID a = newHost("ha");
        UUID b = newHost("hb");
        ProxyRoute r1 = ProxyRoute.create(a, "one.example.com", List.of("www.one.example.com"), "web", 80, BackendScheme.HTTP,
                null, null, CertMode.ACME_HTTP, null, true, null, null);
        routes.saveAndFlush(r1);
        routes.saveAndFlush(route(a, "two.example.com"));
        routes.saveAndFlush(route(b, "other.example.com"));

        evict();
        assertThat(routes.findByHostId(a)).hasSize(2);
        assertThat(routes.findByHostId(a).stream().flatMap(x -> x.allNames().stream()).toList())
                .containsExactlyInAnyOrder("one.example.com", "www.one.example.com", "two.example.com");
    }

    @Test
    void oneProxyInstancePerHost() {
        UUID a = newHost("ha");
        instances.saveAndFlush(ProxyInstance.create(a, ProxyType.TRAEFIK, "a@example.com", CertMode.ACME_HTTP));
        evict();
        assertThat(instances.findByHostId(a)).isPresent();
        assertThatThrownBy(() -> instances.saveAndFlush(ProxyInstance.create(a, ProxyType.CADDY, null, CertMode.INTERNAL)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void certificateNameIsUniquePerHostAndDomainsRoundTrip() {
        UUID a = newHost("ha");
        UUID b = newHost("hb");
        Instant end = Instant.now().plusSeconds(3600);
        ProxyCertificate c = certificates.saveAndFlush(ProxyCertificate.create(a, "wild", "PEM", new byte[]{1, 2},
                List.of("*.example.com", "example.com"), end));
        certificates.saveAndFlush(ProxyCertificate.create(b, "wild", "PEM", new byte[]{1}, List.of("x.com"), end));
        evict();
        assertThat(certificates.findById(c.getId()).orElseThrow().getDomains()).containsExactly("*.example.com", "example.com");
        assertThat(certificates.findByHostId(a)).hasSize(1);
        assertThatThrownBy(() -> certificates.saveAndFlush(ProxyCertificate.create(a, "wild", "PEM", new byte[]{1}, List.of("y.com"), end)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
