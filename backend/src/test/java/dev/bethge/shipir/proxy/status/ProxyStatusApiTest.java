package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.LogLine;
import dev.bethge.shipir.proxy.ProxySyncService;
import dev.bethge.shipir.proxy.cert.TestCerts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Certificate status API against real Postgres; Docker (containers, files, logs) is stubbed. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, ProxyStatusApiTest.ClockConfig.class})
@TestPropertySource(properties = {"shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "shipir.proxy.status.log-timeout=300ms", "shipir.proxy.status.cache-ttl=3s", "shipir.proxy.status.refresh-floor=2s"})
class ProxyStatusApiTest {

    static class MutableClock extends java.time.Clock {
        volatile Instant now = Instant.now();

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static final String PASSWORD = "A-test-Passw0rd-for-proxies!";

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ContainerFileService files;
    @MockitoBean
    ContainerLogService logs;
    @MockitoSpyBean
    ProxySyncService sync;

    UUID hostId;
    UUID deploymentId;
    TestCerts.Pair shopCert;
    TestCerts.Pair wildCert;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM proxy_routes");
        jdbc.update("DELETE FROM proxy_certificates");
        jdbc.update("DELETE FROM proxy_instances");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Status host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        deploymentId = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, hostId);
        jdbc.update("INSERT INTO proxy_instances (host_id, type, acme_email, default_cert_mode) "
                + "VALUES (?, 'TRAEFIK', 'ops@example.com', 'ACME_HTTP')", hostId);
        org.mockito.Mockito.reset(containers, files, logs, sync);
        doReturn("proxy1").when(sync).proxyContainerId(any(), any());
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("web1", "shop-web-1", "img", "running", "Up", null, Instant.now(), List.of(), "shop", "web"),
                new ContainerDto("o1", "other-1", "img", "running", "Up", null, Instant.now(), List.of(), "other", "o")));
        shopCert = TestCerts.valid("RSA", "shop.example.com", "dns:shop.example.com");
        wildCert = TestCerts.valid("RSA", "example.org", "dns:example.org,dns:*.example.org");

        String acme = "{\"letsencrypt-http\":{\"Account\":{\"PrivateKey\":\"ACCOUNTKEYMATERIAL\"},\"Certificates\":["
                + AcmeJsonParserTest.entry("shop.example.com", List.of(), shopCert) + ","
                + AcmeJsonParserTest.entry("example.org", List.of("*.example.org"), wildCert) + "]}}";
        when(files.readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-http.json"), anyLong()))
                .thenReturn(acme.getBytes(StandardCharsets.UTF_8));
        when(files.readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-dns.json"), anyLong()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
        log("2030-01-01T00:00:00Z ERR Unable to obtain ACME certificate error=\"too many requests\" domain=fail.example.com");

        route("shop.example.com", "shop-web-1", "ACME_HTTP", null);
        route("app.example.org", "other-1", "ACME_HTTP", null);
        route("pending.example.com", "shop-web-1", "ACME_HTTP", null);
        route("fail.example.com", "other-1", "ACME_HTTP", null);
        route("lan.example.com", "shop-web-1", "INTERNAL", null);
        UUID certId = jdbc.queryForObject("INSERT INTO proxy_certificates (host_id, name, cert_pem, key_encrypted, domains, not_after) "
                + "VALUES (?, 'mine', 'pem', '\\x00', '{custom.example.com}', '2031-02-03T04:05:06Z') RETURNING id", UUID.class, hostId);
        route("custom.example.com", "other-1", "CUSTOM", certId);
    }

    void log(String... lines) {
        org.mockito.Mockito.doAnswer((InvocationOnMock inv) -> {
            Consumer<LogLine> onLine = inv.getArgument(6);
            Consumer<Throwable> onEnd = inv.getArgument(7);
            for (String l : lines) {
                onLine.accept(new LogLine(LogLine.Stream.STDERR, l, null));
            }
            onEnd.accept(null);
            return (java.io.Closeable) () -> { };
        }).when(logs).snapshot(any(), any(), eq("proxy1"), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    void route(String hostname, String container, String mode, UUID certId) {
        jdbc.update("INSERT INTO proxy_routes (host_id, hostname, container_name, target_port, cert_mode, certificate_id) "
                + "VALUES (?, ?, ?, 80, ?, ?)", hostId, hostname, container, mode, certId);
    }

    MockHttpSession login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    String url() {
        return "/api/hosts/" + hostId + "/proxy/routes/status";
    }

    String body(MockHttpSession session) throws Exception {
        return mvc.perform(get(url()).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void viewerWithHostAccessSeesTheStatusOfEveryRoute() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, 'VIEW' FROM users "
                + "WHERE username = 'viewer'", hostId);

        mvc.perform(get(url()).session(login("viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.hostname=='shop.example.com')].status").value("ISSUED"))
                .andExpect(jsonPath("$[?(@.hostname=='shop.example.com')].notAfter").isNotEmpty())
                .andExpect(jsonPath("$[?(@.hostname=='app.example.org')].status").value("ISSUED"))
                .andExpect(jsonPath("$[?(@.hostname=='pending.example.com')].status").value("PENDING"))
                .andExpect(jsonPath("$[?(@.hostname=='fail.example.com')].status").value("FAILED"))
                .andExpect(jsonPath("$[?(@.hostname=='fail.example.com')].lastError").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("too many requests"))))
                .andExpect(jsonPath("$[?(@.hostname=='lan.example.com')].status").value("INTERNAL"))
                .andExpect(jsonPath("$[?(@.hostname=='custom.example.com')].status").value("CUSTOM"))
                .andExpect(jsonPath("$[?(@.hostname=='custom.example.com')].notAfter").value("2031-02-03T04:05:06Z"));
    }

    @Test
    void theResponseCarriesNoKeyMaterialOrCertificateText() throws Exception {
        String json = body(login("admin"));

        String keyBody = shopCert.keyPem().replaceAll("-----[A-Z ]+-----|\\s", "");
        String certBody = shopCert.certPem().replaceAll("-----[A-Z ]+-----|\\s", "");
        assertThat(json).doesNotContain(keyBody.substring(0, 40)).doesNotContain(certBody.substring(0, 40))
                .doesNotContain("PRIVATE").doesNotContain("BEGIN").doesNotContain("ACCOUNTKEYMATERIAL")
                .doesNotContain(Base64.getEncoder().encodeToString(shopCert.keyPem().getBytes(StandardCharsets.UTF_8)).substring(0, 40));
    }

    @Test
    void aDeploymentOnlyGrantSeesOnlyTheRoutesOfItsOwnContainers() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'VIEW' FROM users "
                + "WHERE username = 'viewer'", deploymentId);

        String json = body(login("viewer"));

        assertThat(json).contains("shop.example.com", "pending.example.com", "lan.example.com")
                .doesNotContain("app.example.org").doesNotContain("fail.example.com").doesNotContain("custom.example.com")
                .doesNotContain("too many requests");
    }

    @Test
    void aUserWithoutAnyAccessToTheHostGetsNotFound() throws Exception {
        mvc.perform(get(url()).session(login("viewer"))).andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
    }

    @Test
    void theProxyStorageIsReadOncePerWindowEvenAcrossRequests() throws Exception {
        MockHttpSession admin = login("admin");

        body(admin);
        body(admin);

        verify(files, times(1)).readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-http.json"), anyLong());
    }

    @Test
    void aMissingDnsStoreIsNormalForHttpOnlySetupsSoFactsLiveForTheFullTtl() throws Exception {
        MockHttpSession admin = login("admin");

        body(admin);
        clock.now = clock.now.plusSeconds(2).plusMillis(500); // past the refresh floor (2s), within the ttl (3s)
        body(admin);

        verify(files, times(1)).readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-http.json"), anyLong());
    }

    private void missing(String path) {
        when(files.readFile(any(), any(), eq("proxy1"), eq(path), anyLong()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
    }

    @Test
    void aMissingHttpStoreWithAcmeHttpRoutesIsDegradedSoItIsRetriedAfterTheFloor() throws Exception {
        missing("/letsencrypt/acme-http.json");
        MockHttpSession admin = login("admin");

        body(admin);
        clock.now = clock.now.plusSeconds(2).plusMillis(500); // past the refresh floor (2s), within the ttl (3s)
        body(admin);

        verify(files, times(2)).readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-http.json"), anyLong());
    }

    @Test
    void aMissingDnsStoreWithAcmeDnsRoutesIsDegradedSoItIsRetriedAfterTheFloor() throws Exception {
        route("dns.example.com", "shop-web-1", "ACME_DNS", null);
        MockHttpSession admin = login("admin");

        body(admin);
        clock.now = clock.now.plusSeconds(2).plusMillis(500);
        body(admin);

        verify(files, times(2)).readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-dns.json"), anyLong());
    }

    @Test
    void aMissingHttpStoreWithoutAcmeHttpRoutesKeepsTheFullTtl() throws Exception {
        jdbc.update("DELETE FROM proxy_routes WHERE cert_mode = 'ACME_HTTP'");
        missing("/letsencrypt/acme-http.json");
        MockHttpSession admin = login("admin");

        body(admin);
        clock.now = clock.now.plusSeconds(2).plusMillis(500);
        body(admin);

        verify(files, times(1)).readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-http.json"), anyLong());
    }

    @Test
    void aProxyThatCannotBeReadLeavesRoutesPendingInsteadOfFailingTheRequest() throws Exception {
        org.mockito.Mockito.doThrow(new dev.bethge.shipir.proxy.ProxyException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED", "none"))
                .when(sync).proxyContainerId(any(), any());

        mvc.perform(get(url()).session(login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.hostname=='shop.example.com')].status").value("PENDING"));
    }

    @Test
    void errorLogLineNamingAnotherHostnameDoesNotLeakItIntoTheAskedRoutesLastError() throws Exception {
        log("2030-01-01T00:00:00Z ERR Unable to obtain certificate domains=[fail.example.com internal-admin.corp.example.net] error=\"boom\"");

        String json = body(login("admin"));

        assertThat(json).contains("boom").doesNotContain("internal-admin");
    }

    static final String CADDY_ROOT = "/data/caddy/certificates";
    static final String CADDY_ISSUER = "acme-v02.api.letsencrypt.org-directory";

    static dev.bethge.shipir.docker.dto.ContainerFileEntry dir(String parent, String name) {
        return new dev.bethge.shipir.docker.dto.ContainerFileEntry(name, parent + "/" + name,
                dev.bethge.shipir.docker.dto.ContainerFileEntry.Type.DIRECTORY, 0, Instant.now(), 0755, null);
    }

    String acme(String... entries) {
        return "{\"r\":{\"Account\":{\"PrivateKey\":\"ACCOUNTKEYMATERIAL\"},\"Certificates\":[" + String.join(",", entries) + "]}}";
    }

    void acmeFile(String path, String json) {
        when(files.readFile(any(), any(), eq("proxy1"), eq(path), anyLong())).thenReturn(json.getBytes(StandardCharsets.UTF_8));
    }

    String statusOf(String body, String hostname) throws Exception {
        return com.jayway.jsonpath.JsonPath.<List<String>>read(body, "$[?(@.hostname=='" + hostname + "')].status").get(0);
    }

    @Test
    void caddyRoutesAreJudgedByTheCaddyStorageNotByAcmeJson() throws Exception {
        jdbc.update("UPDATE proxy_instances SET type = 'CADDY' WHERE host_id = ?", hostId);
        when(files.list(any(), any(), eq("proxy1"), eq(CADDY_ROOT), anyLong())).thenReturn(List.of(dir(CADDY_ROOT, CADDY_ISSUER)));
        when(files.list(any(), any(), eq("proxy1"), eq(CADDY_ROOT + "/" + CADDY_ISSUER), anyLong()))
                .thenReturn(List.of(dir(CADDY_ROOT + "/" + CADDY_ISSUER, "shop.example.com"),
                        dir(CADDY_ROOT + "/" + CADDY_ISSUER, "wildcard_.example.org")));
        when(files.readFile(any(), any(), eq("proxy1"), eq(CADDY_ROOT + "/" + CADDY_ISSUER + "/shop.example.com/shop.example.com.crt"), anyLong()))
                .thenReturn(shopCert.certPem().getBytes(StandardCharsets.UTF_8));
        when(files.readFile(any(), any(), eq("proxy1"), eq(CADDY_ROOT + "/" + CADDY_ISSUER + "/wildcard_.example.org/wildcard_.example.org.crt"), anyLong()))
                .thenReturn(wildCert.certPem().getBytes(StandardCharsets.UTF_8));

        String json = body(login("admin"));

        assertThat(statusOf(json, "shop.example.com")).isEqualTo("ISSUED");
        assertThat(statusOf(json, "app.example.org")).isEqualTo("ISSUED");
        assertThat(statusOf(json, "pending.example.com")).isEqualTo("PENDING");
        assertThat(statusOf(json, "fail.example.com")).isEqualTo("FAILED");
        verify(files, org.mockito.Mockito.never()).readFile(any(), any(), any(), eq("/letsencrypt/acme-http.json"), anyLong());
        verify(files, org.mockito.Mockito.never()).readFile(any(), any(), any(), org.mockito.ArgumentMatchers.endsWith(".key"), anyLong());
    }

    @Test
    void acmeDnsRoutesReadTheDnsStoreAndAcmeHttpRoutesTheHttpStore() throws Exception {
        TestCerts.Pair a = TestCerts.valid("RSA", "dns-only.example.com", "dns:dns-only.example.com");
        TestCerts.Pair b = TestCerts.valid("RSA", "http-wants-dns.example.com", "dns:http-wants-dns.example.com");
        acmeFile("/letsencrypt/acme-dns.json", acme(AcmeJsonParserTest.entry("dns-only.example.com", List.of(), a),
                AcmeJsonParserTest.entry("http-wants-dns.example.com", List.of(), b)));
        route("dns-only.example.com", "shop-web-1", "ACME_DNS", null);
        route("http-wants-dns.example.com", "shop-web-1", "ACME_HTTP", null);

        String json = body(login("admin"));

        assertThat(statusOf(json, "dns-only.example.com")).isEqualTo("ISSUED");
        assertThat(statusOf(json, "http-wants-dns.example.com")).isEqualTo("PENDING");
        assertThat(statusOf(json, "shop.example.com")).isEqualTo("ISSUED");
    }

    @Test
    void anExpiredCertificateIsPendingAndFailedWhenTheLogHasAnError() throws Exception {
        TestCerts.Pair old = TestCerts.expired("old.example.com", "dns:old.example.com");
        TestCerts.Pair old2 = TestCerts.expired("old2.example.com", "dns:old2.example.com");
        acmeFile("/letsencrypt/acme-http.json", acme(AcmeJsonParserTest.entry("old.example.com", List.of(), old),
                AcmeJsonParserTest.entry("old2.example.com", List.of(), old2)));
        log("2030-01-01T00:00:00Z ERR Unable to renew certificate error=\"challenge failed\" domain=old2.example.com");
        route("old.example.com", "shop-web-1", "ACME_HTTP", null);
        route("old2.example.com", "shop-web-1", "ACME_HTTP", null);

        String json = body(login("admin"));

        assertThat(statusOf(json, "old.example.com")).isEqualTo("PENDING");
        assertThat(statusOf(json, "old2.example.com")).isEqualTo("FAILED");
        mvc.perform(get(url()).session(login("admin")))
                .andExpect(jsonPath("$[?(@.hostname=='old.example.com')].notAfter").isNotEmpty());
    }

    @Test
    void refreshIsRateLimitedSoTheProxyIsNotReadAgainImmediately() throws Exception {
        MockHttpSession admin = login("admin");

        body(admin);
        mvc.perform(get(url() + "?refresh=true").session(admin)).andExpect(status().isOk());

        verify(files, times(1)).readFile(any(), any(), eq("proxy1"), eq("/letsencrypt/acme-http.json"), anyLong());
    }

    @Test
    void anUnreadableLogLeavesCertificateStatusIntactButGivesNoErrorText() throws Exception {
        org.mockito.Mockito.doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null))
                .when(logs).snapshot(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        String json = body(login("admin"));

        assertThat(statusOf(json, "shop.example.com")).isEqualTo("ISSUED");
        assertThat(statusOf(json, "fail.example.com")).isEqualTo("PENDING");
    }

    @Test
    void aLogThatNeverEndsIsUsedAsFarAsItGotAfterTheTimeout() throws Exception {
        org.mockito.Mockito.doAnswer((InvocationOnMock inv) -> {
            Consumer<LogLine> onLine = inv.getArgument(6);
            onLine.accept(new LogLine(LogLine.Stream.STDERR,
                    "2030-01-01T00:00:00Z ERR Unable to obtain ACME certificate error=\"too many requests\" domain=fail.example.com", null));
            return (java.io.Closeable) () -> { };
        }).when(logs).snapshot(any(), any(), eq("proxy1"), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        String json = body(login("admin"));

        assertThat(statusOf(json, "fail.example.com")).isEqualTo("FAILED");
    }

    @Test
    void repeatedRequestsDuringAnOutageReachDockerOnlyOncePerFloor() throws Exception {
        org.mockito.Mockito.doThrow(new dev.bethge.shipir.proxy.ProxyException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED", "none"))
                .when(sync).proxyContainerId(any(), any());
        MockHttpSession admin = login("admin");

        body(admin);
        body(admin);
        body(admin);

        verify(sync, times(1)).proxyContainerId(any(), any());
    }

    @Test
    void anIssuedRouteStaysIssuedWhenTheProxyBecomesUnreachableAfterAGoodRead() throws Exception {
        MockHttpSession admin = login("admin");
        assertThat(statusOf(body(admin), "shop.example.com")).isEqualTo("ISSUED");
        org.mockito.Mockito.doThrow(new dev.bethge.shipir.proxy.ProxyException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED", "none"))
                .when(sync).proxyContainerId(any(), any());
        clock.now = clock.now.plusSeconds(4);

        assertThat(statusOf(body(admin), "shop.example.com")).isEqualTo("ISSUED");
    }
}
