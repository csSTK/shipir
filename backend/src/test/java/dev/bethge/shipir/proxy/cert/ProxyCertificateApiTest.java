package dev.bethge.shipir.proxy.cert;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The certificate API against real Postgres; the Docker services are stubbed. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@ExtendWith(OutputCaptureExtension.class)
class ProxyCertificateApiTest {

    static final String PASSWORD = "A-test-Passw0rd-for-certs!";
    static final TestCerts.Pair APPS = TestCerts.valid("RSA", "apps.example.com", "dns:*.apps.example.com,dns:apps.example.com");
    static final TestCerts.Pair OTHER = TestCerts.valid("EC", "other.example.org", "dns:other.example.org");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ContainerFileService fileService;
    @MockitoBean
    ExecService exec;

    UUID hostId;

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_log");
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
                + "SELECT 'Cert host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        org.mockito.Mockito.reset(containers, fileService, exec);
        when(containers.listContainers(any(), any())).thenReturn(List.of());
        doAnswer(inv -> {
            Consumer<Throwable> end = inv.getArgument(7);
            end.accept(null);
            return mock(ExecHandle.class);
        }).when(exec).open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    void grant(String username, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, ? FROM users WHERE username = ?",
                hostId, level, username);
    }

    String url() {
        return "/api/hosts/" + hostId + "/proxy/certificates";
    }

    static String json(String name, TestCerts.Pair p) {
        return "{\"name\":%s,\"certPem\":%s,\"keyPem\":%s}".formatted(q(name), q(p.certPem()), q(p.keyPem()));
    }

    static String renewJson(TestCerts.Pair p) {
        return "{\"certPem\":%s,\"keyPem\":%s}".formatted(q(p.certPem()), q(p.keyPem()));
    }

    static String q(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }

    String upload(String name, TestCerts.Pair pair) throws Exception {
        MvcResult r = mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(json(name, pair)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    void proxyWithRunningContainer() {
        jdbc.update("INSERT INTO proxy_instances (host_id, type, default_cert_mode) VALUES (?, 'TRAEFIK', 'CUSTOM')", hostId);
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("proxy1", "p", "img", "running", "Up", null, Instant.now(), List.of(),
                        "shipir-proxy", "traefik")));
    }

    void route(String hostname, String certificateId) {
        jdbc.update("INSERT INTO proxy_routes (host_id, hostname, container_name, target_port, cert_mode, certificate_id) "
                + "VALUES (?, ?, 'web', 80, 'CUSTOM', ?::uuid)", hostId, hostname, certificateId);
    }

    @SuppressWarnings("unchecked")
    List<ContainerFileWrite> lastWrites() {
        ArgumentCaptor<List<ContainerFileWrite>> captor = ArgumentCaptor.forClass(List.class);
        verify(fileService, org.mockito.Mockito.atLeastOnce()).writeFiles(any(), any(), eq("proxy1"),
                eq("/etc/shipir-proxy"), captor.capture(), anyLong());
        return captor.getValue();
    }

    // ---- upload / list ----------------------------------------------------------------------------------------

    @Test
    void uploadStoresTheKeyEncryptedAndNeverReturnsOrLogsIt(CapturedOutput output) throws Exception {
        String keyLine = APPS.keyPem().split("\n")[1];
        grant("viewer", "VIEW");

        String body = mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(json("apps", APPS)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("apps"))
                .andExpect(jsonPath("$.domains[0]").value("*.apps.example.com"))
                .andExpect(jsonPath("$.notAfter").isNotEmpty())
                .andExpect(jsonPath("$.usedByRoutes").isEmpty())
                .andExpect(jsonPath("$.keyPem").doesNotExist())
                .andExpect(jsonPath("$.certPem").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String list = mvc.perform(get(url()).session(login("viewer").session())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("apps")).andReturn().getResponse().getContentAsString();

        assertThat(body + list).doesNotContain(keyLine).doesNotContain("PRIVATE KEY");
        assertThat(output.getAll()).doesNotContain(keyLine);
        byte[] stored = jdbc.queryForObject("SELECT key_encrypted FROM proxy_certificates", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(keyLine);
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'PROXY_CERT_UPLOAD'", String.class))
                .contains("apps").doesNotContain(keyLine).doesNotContain("PRIVATE KEY");
    }

    @Test
    void invalidMaterialIsA400WithAStableCode() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(json("x", new TestCerts.Pair(APPS.certPem(), OTHER.keyPem()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KEY_MISMATCH"));
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(json("x", new TestCerts.Pair("garbage", APPS.keyPem()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CERTIFICATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_certificates", Integer.class)).isZero();
    }

    @Test
    void aDuplicateNameIs409() throws Exception {
        upload("apps", APPS);

        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(json("apps", OTHER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CERT_NAME_TAKEN"));
    }

    @Test
    void onlyAdminsMayWriteButEveryoneWhoSeesTheHostMayList() throws Exception {
        String id = upload("apps", APPS);
        grant("operator", "OPERATE");
        grant("viewer", "VIEW");
        for (String user : List.of("operator", "viewer")) {
            Login login = login(user);
            mvc.perform(login.write(post(url())).contentType(APPLICATION_JSON).content(json("n-" + user, OTHER)))
                    .andExpect(status().isForbidden());
            mvc.perform(login.write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(renewJson(APPS)))
                    .andExpect(status().isForbidden());
            mvc.perform(login.write(delete(url() + "/" + id))).andExpect(status().isForbidden());
            mvc.perform(get(url()).session(login.session())).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(id));
        }
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_certificates", Integer.class)).isEqualTo(1);
    }

    // ---- delete -----------------------------------------------------------------------------------------------

    @Test
    void deletingACertificateInUseIs409AndListsTheRoutes() throws Exception {
        String id = upload("apps", APPS);
        route("shop.apps.example.com", id);

        mvc.perform(login("admin").write(delete(url() + "/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_IN_USE"))
                .andExpect(jsonPath("$.details[0]").value("shop.apps.example.com"));
        mvc.perform(get(url()).session(login("admin").session()))
                .andExpect(jsonPath("$[0].usedByRoutes[0]").value("shop.apps.example.com"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_certificates", Integer.class)).isEqualTo(1);
    }

    @Test
    void deleteRemovesTheRowAndTheKeyFilesFromTheProxyContainer() throws Exception {
        proxyWithRunningContainer();
        String id = upload("apps", APPS);

        mvc.perform(login("admin").write(delete(url() + "/" + id))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_certificates", Integer.class)).isZero();
        assertThat(lastWrites()).extracting(ContainerFileWrite::path).containsExactly("certs/" + id + ".crt", "certs/" + id + ".key");
        assertThat(lastWrites()).allSatisfy(w -> assertThat(w.content()).isEmpty());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(exec).open(any(), any(), eq("proxy1"), cmd.capture(), anyInt(), anyInt(), any(), any());
        assertThat(cmd.getValue()).startsWith("rm", "-f", "--").contains("/etc/shipir-proxy/certs/" + id + ".key");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_CERT_DELETE'", String.class)).hasSize(1);
    }

    @Test
    void ifTheKeyFilesCannotBeRemovedTheRowStaysAndTheProxyFilesAreRestored() throws Exception {
        proxyWithRunningContainer();
        String id = upload("apps", APPS);
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null)).doNothing().when(fileService)
                .writeFiles(any(), any(), any(), any(), any(), anyLong());

        mvc.perform(login("admin").write(delete(url() + "/" + id)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_certificates", Integer.class)).isEqualTo(1);
        // the repair sync wrote the stored certificate and key back
        List<ContainerFileWrite> writes = lastWrites();
        assertThat(new String(writes.stream().filter(w -> w.path().equals("certs/" + id + ".crt")).findFirst().orElseThrow().content()))
                .isEqualTo(APPS.certPem());
        assertThat(new String(writes.stream().filter(w -> w.path().equals("certs/" + id + ".key")).findFirst().orElseThrow().content()))
                .isEqualTo(APPS.keyPem());
    }

    @Test
    void aDeploymentOnlyGrantSeesNoCertificates() throws Exception {
        String id = upload("apps", APPS);
        UUID deployment = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, hostId);
        route("shop.apps.example.com", id);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' FROM users "
                + "WHERE username = 'operator'", deployment);

        mvc.perform(get(url()).session(login("operator").session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void aCertificateOfAnotherHostIsNotFound() throws Exception {
        String id = upload("apps", APPS);
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'Second', 'TCP', 'other.local', 2375, id FROM users WHERE username = 'admin'");
        UUID other = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'Second'", UUID.class);

        mvc.perform(login("admin").write(delete("/api/hosts/" + other + "/proxy/certificates/" + id)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CERT_NOT_FOUND"));
    }

    // ---- renew ------------------------------------------------------------------------------------------------

    @Test
    void renewReplacesTheCertificateAndSyncsTheProxy() throws Exception {
        proxyWithRunningContainer();
        String id = upload("apps", APPS);
        route("shop.apps.example.com", id);
        TestCerts.Pair renewed = TestCerts.valid("EC", "renewed.apps.example.com", "dns:*.apps.example.com");

        mvc.perform(login("admin").write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(renewJson(renewed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("apps"))
                .andExpect(jsonPath("$.domains[0]").value("*.apps.example.com"))
                .andExpect(jsonPath("$.usedByRoutes[0]").value("shop.apps.example.com"))
                .andExpect(jsonPath("$.keyPem").doesNotExist());

        List<ContainerFileWrite> writes = lastWrites();
        ContainerFileWrite crt = writes.stream().filter(w -> w.path().equals("certs/" + id + ".crt")).findFirst().orElseThrow();
        ContainerFileWrite key = writes.stream().filter(w -> w.path().equals("certs/" + id + ".key")).findFirst().orElseThrow();
        assertThat(new String(crt.content())).isEqualTo(renewed.certPem());
        assertThat(new String(key.content())).isEqualTo(renewed.keyPem());
        assertThat(key.mode()).isEqualTo(0600);
        assertThat(jdbc.queryForObject("SELECT cert_pem FROM proxy_certificates", String.class)).isEqualTo(renewed.certPem());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_CERT_RENEW'", String.class)).hasSize(1);
    }

    @Test
    void renewIsRefusedWhenTheNewDomainsNoLongerCoverARoute() throws Exception {
        proxyWithRunningContainer();
        String id = upload("apps", APPS);
        route("shop.apps.example.com", id);
        String before = jdbc.queryForObject("SELECT cert_pem FROM proxy_certificates", String.class);

        mvc.perform(login("admin").write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(renewJson(OTHER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CERT_DOES_NOT_COVER_HOST"));

        assertThat(jdbc.queryForObject("SELECT cert_pem FROM proxy_certificates", String.class)).isEqualTo(before);
        verify(fileService, never()).writeFiles(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void renewIsRolledBackWhenTheSyncFailsAndTheProxyFilesAreRestored() throws Exception {
        proxyWithRunningContainer();
        String id = upload("apps", APPS);
        String before = jdbc.queryForObject("SELECT cert_pem FROM proxy_certificates", String.class);
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null)).doNothing().when(fileService)
                .writeFiles(any(), any(), any(), any(), any(), anyLong());

        mvc.perform(login("admin").write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(renewJson(TestCerts.valid("EC", "again.apps.example.com", "dns:again.apps.example.com"))))
                .andExpect(status().isBadGateway());

        assertThat(jdbc.queryForObject("SELECT cert_pem FROM proxy_certificates", String.class)).isEqualTo(before);
        // second write = repair sync from the committed (old) state
        ContainerFileWrite crt = lastWrites().stream().filter(w -> w.path().equals("certs/" + id + ".crt")).findFirst().orElseThrow();
        assertThat(new String(crt.content())).isEqualTo(before);
    }

    @Test
    void renewWithoutAProxyJustSavesAndInvalidInputChangesNothing() throws Exception {
        String id = upload("apps", APPS);

        mvc.perform(login("admin").write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(renewJson(OTHER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.domains[0]").value("other.example.org"));
        mvc.perform(login("admin").write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(renewJson(new TestCerts.Pair(APPS.certPem(), OTHER.keyPem()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KEY_MISMATCH"));

        verify(fileService, never()).writeFiles(any(), any(), any(), any(), any(), anyLong());
    }
}
