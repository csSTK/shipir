package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecResult;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The custom proxy config API against real Postgres; the Docker daemon (files, exec) is stubbed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProxyCustomConfigApiTest {

    static final String PASSWORD = "A-test-Passw0rd-for-proxies!";

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
    volatile String execOutput = "ok\n";
    volatile int execExit = 0;
    final List<List<String>> execCommands = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM proxy_routes");
        jdbc.update("DELETE FROM proxy_instances");
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Proxy host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        org.mockito.Mockito.reset(containers, fileService, exec);
        execOutput = "ok\n";
        execExit = 0;
        execCommands.clear();
        when(exec.run(any(), any(), eq("proxy1"), any(), any())).thenAnswer(inv -> {
            execCommands.add(inv.getArgument(3));
            return new ExecResult(execExit, execOutput);
        });
    }

    // ---- GET --------------------------------------------------------------------------------------------------

    @Test
    void getReturnsTheManagedViewAndTheCustomFile() throws Exception {
        addProxy("TRAEFIK", "http: {}\n");
        mvc.perform(get(url()).session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRAEFIK"))
                .andExpect(jsonPath("$.custom").value("http: {}\n"))
                .andExpect(jsonPath("$.customFileName").value("custom.yml"))
                .andExpect(jsonPath("$.managed[0].name").value("managed.yml"))
                .andExpect(jsonPath("$.managed[0].content").value(org.hamcrest.Matchers.containsString("Managed by Shipir")));
    }

    @Test
    void managedViewContainsNoSecrets() throws Exception {
        addProxy("CADDY", "");
        jdbc.update("UPDATE proxy_instances SET dns_provider = 'cloudflare', dns_credentials_encrypted = ?, dns_credentials_hint = '...cret'",
                "ciphertext-SUPER-SECRET".getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(get(url()).session(login("admin").session())).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SUPER-SECRET").doesNotContain("cloudflare");
    }

    @Test
    void getWithoutProxyIsNotFound() throws Exception {
        mvc.perform(get(url()).session(login("admin").session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROXY_NOT_FOUND"));
    }

    @Test
    void operatorAndViewerAreForbiddenOnEveryEndpoint() throws Exception {
        addProxy("TRAEFIK", "");
        UUID version = addVersion("old");
        for (String user : List.of("operator", "viewer")) {
            grant(user);
            Login l = login(user);
            mvc.perform(get(url()).session(l.session())).andExpect(status().isForbidden());
            mvc.perform(get(url() + "/custom/versions").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(put(url() + "/custom")).contentType(APPLICATION_JSON).content("{\"content\":\"http: {}\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(l.write(post(url() + "/custom/restore/" + version))).andExpect(status().isForbidden());
            jdbc.update("DELETE FROM permissions");
        }
        verify(fileService, never()).writeFiles(any(), any(), any(), any(), any(), anyLong());
        assertThat(customOf()).isEmpty();
    }

    // ---- PUT: Traefik -----------------------------------------------------------------------------------------

    @Test
    void traefikInvalidYamlIsRejectedAndNothingIsWritten() throws Exception {
        addProxy("TRAEFIK", "");
        putCustom("http: [unclosed").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"));
        assertNothingWritten();
    }

    @Test
    void traefikDisallowedTopLevelKeyIsRejectedAndNothingIsWritten() throws Exception {
        addProxy("TRAEFIK", "");
        putCustom("entryPoints:\n  web:\n    address: ':81'\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("entryPoints")));
        assertNothingWritten();
    }

    @Test
    void validTraefikConfigIsStoredWrittenIntoTheContainerAndBackedUp() throws Exception {
        addProxy("TRAEFIK", "http: {}\n");
        container("traefik", "running");

        putCustom("tls:\n  options: {}\n").andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value("tls:\n  options: {}\n"));

        assertThat(customOf()).isEqualTo("tls:\n  options: {}\n");
        assertThat(jdbc.queryForObject("SELECT custom_config_updated_at FROM proxy_instances", Object.class)).isNotNull();
        List<ContainerFileWrite> w = writes();
        assertThat(w).extracting(ContainerFileWrite::path).contains("custom.yml", "managed.yml");
        assertThat(w.stream().filter(f -> f.path().equals("custom.yml")).map(f -> new String(f.content(), StandardCharsets.UTF_8)))
                .containsExactly("tls:\n  options: {}\n");
        // the replaced content is the backup, with who changed it
        assertThat(jdbc.queryForList("SELECT content FROM proxy_custom_config_versions", String.class)).containsExactly("http: {}\n");
        assertThat(jdbc.queryForObject("SELECT created_by FROM proxy_custom_config_versions", String.class)).isEqualTo("admin");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_CUSTOM_CONFIG_UPDATE'", String.class))
                .hasSize(1);
        // the content itself is not copied into the audit log
        assertThat(jdbc.queryForObject("SELECT string_agg(coalesce(details::text, ''), ' ') FROM audit_log", String.class))
                .doesNotContain("options").contains("length");
    }

    @Test
    void everyWriteCreatesABackupAndListShowsNewestFirst() throws Exception {
        addProxy("TRAEFIK", "");
        container("traefik", "running");
        putCustom("http: {a: 1}").andExpect(status().isOk());
        putCustom("http: {a: 2}").andExpect(status().isOk());
        putCustom("http: {a: 3}").andExpect(status().isOk());

        mvc.perform(get(url() + "/custom/versions").session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].content").value("http: {a: 2}"))
                .andExpect(jsonPath("$[1].content").value("http: {a: 1}"))
                .andExpect(jsonPath("$[2].content").value(""))
                .andExpect(jsonPath("$[0].createdBy").value("admin"));
    }

    @Test
    void onlyTheNewestVersionsAreKept() throws Exception {
        addProxy("TRAEFIK", "");
        container("traefik", "running");
        Login admin = login("admin");
        for (int i = 0; i < ProxyCustomConfigService.MAX_VERSIONS + 5; i++) {
            mvc.perform(admin.write(put(url() + "/custom")).contentType(APPLICATION_JSON)
                    .content("{\"content\":\"http: {n: %d}\"}".formatted(i))).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_custom_config_versions", Integer.class))
                .isEqualTo(ProxyCustomConfigService.MAX_VERSIONS);
        // the oldest ones are gone, the newest backup (the content before the last write) is there
        assertThat(jdbc.queryForList("SELECT content FROM proxy_custom_config_versions", String.class))
                .contains("http: {n: %d}".formatted(ProxyCustomConfigService.MAX_VERSIONS + 3))
                .doesNotContain("http: {n: 0}");
    }

    @Test
    void aVersionCanBeRestoredAndRestoringBacksUpTheCurrentContent() throws Exception {
        addProxy("TRAEFIK", "http: {a: 1}");
        container("traefik", "running");
        UUID old = addVersion("tls: {}");

        mvc.perform(login("admin").write(post(url() + "/custom/restore/" + old)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value("tls: {}"));

        assertThat(customOf()).isEqualTo("tls: {}");
        assertThat(jdbc.queryForList("SELECT content FROM proxy_custom_config_versions", String.class))
                .contains("http: {a: 1}");
        assertThat(writes().stream().filter(f -> f.path().equals("custom.yml"))
                .map(f -> new String(f.content(), StandardCharsets.UTF_8))).containsExactly("tls: {}");
    }

    @Test
    void restoringAnUnknownVersionIsNotFound() throws Exception {
        addProxy("TRAEFIK", "");
        mvc.perform(login("admin").write(post(url() + "/custom/restore/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROXY_CONFIG_VERSION_NOT_FOUND"));
    }

    @Test
    void aVersionOfAnotherHostCannotBeRestored() throws Exception {
        addProxy("TRAEFIK", "");
        UUID otherHost = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'Other', 'TCP', 'docker.local', 2375, id FROM users WHERE username = 'admin' RETURNING id", UUID.class);
        UUID otherInstance = jdbc.queryForObject(
                "INSERT INTO proxy_instances (host_id, type, default_cert_mode) VALUES (?, 'TRAEFIK', 'ACME_HTTP') RETURNING id",
                UUID.class, otherHost);
        UUID foreign = jdbc.queryForObject(
                "INSERT INTO proxy_custom_config_versions (proxy_instance_id, content) VALUES (?, 'tls: {}') RETURNING id",
                UUID.class, otherInstance);
        mvc.perform(login("admin").write(post(url() + "/custom/restore/" + foreign))).andExpect(status().isNotFound());
        assertThat(customOf()).isEmpty();
    }

    @Test
    void aRestoredVersionIsValidatedAgain() throws Exception {
        addProxy("TRAEFIK", "");
        UUID bad = addVersion("entryPoints: {}");
        mvc.perform(login("admin").write(post(url() + "/custom/restore/" + bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"));
        assertThat(customOf()).isEmpty();
    }

    @Test
    void failedSyncRollsTheStoredConfigBackAndDropsTheBackup() throws Exception {
        addProxy("TRAEFIK", "http: {a: 1}");
        container("traefik", "running");
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null))
                .doNothing().when(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());

        putCustom("http: {a: 2}").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));

        assertThat(customOf()).isEqualTo("http: {a: 1}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_custom_config_versions", Integer.class)).isZero();
    }

    @Test
    void contentOverTheLimitIsRejected() throws Exception {
        addProxy("CADDY", "");
        putCustom("# " + "a".repeat(CustomConfigValidator.MAX_BYTES + 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"));
        assertNothingWritten();
    }

    @Test
    void missingContentIsABadRequest() throws Exception {
        addProxy("TRAEFIK", "");
        mvc.perform(login("admin").write(put(url() + "/custom")).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---- PUT: Caddy -------------------------------------------------------------------------------------------

    @Test
    void caddyValidationFailureIsRejectedWithCaddysMessageAndNothingReachesTheProxy() throws Exception {
        addProxy("CADDY", "respond 200");
        container("caddy", "running");
        execOutput = "Error: adapting config using caddyfile: unknown directive 'bogus'\n";
        execExit = 1;

        putCustom("bogus").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unknown directive 'bogus'")));

        assertThat(customOf()).isEqualTo("respond 200");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_custom_config_versions", Integer.class)).isZero();
        // only the throw-away validation copy was written, never the live custom.caddy
        ArgumentCaptor<List<ContainerFileWrite>> captor = writesCaptor();
        verify(fileService).writeFiles(any(), any(), eq("proxy1"), eq("/etc/shipir-proxy"), captor.capture(), anyLong());
        assertThat(captor.getValue()).allSatisfy(f -> assertThat(f.path()).startsWith("validate-"));
        assertThat(captor.getValue()).anySatisfy(f -> {
            assertThat(f.path()).endsWith("/custom.caddy");
            assertThat(new String(f.content(), StandardCharsets.UTF_8)).isEqualTo("bogus");
        });
        // validate is an argument list without a shell wrapper around user content; the copy is removed afterwards
        assertThat(execCommands.get(0)).contains("validate").doesNotContain("bogus");
        assertThat(execCommands.stream().anyMatch(c -> c.contains("rm") && c.stream().anyMatch(a -> a.contains("validate-"))))
                .isTrue();
    }

    @Test
    void validationThatCannotBeRunIsABadGatewayNotAnInvalidConfig() throws Exception {
        addProxy("CADDY", "");
        container("caddy", "running");
        when(exec.run(any(), any(), eq("proxy1"), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.TIMEOUT, "x", null));

        putCustom("respond 200").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));
        assertThat(customOf()).isEmpty();
    }

    @Test
    void basicAuthHashesAreHiddenInTheManagedView() throws Exception {
        addProxy("TRAEFIK", "");
        jdbc.update("INSERT INTO proxy_routes (host_id, hostname, container_name, target_port, cert_mode, basic_auth_users) "
                + "VALUES (?, 'app.example.com', 'app', 8080, 'INTERNAL', ?::jsonb)", hostId,
                "{\"alice\":\"$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234\"}");
        String body = mvc.perform(get(url()).session(login("admin").session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("alice").doesNotContain("abcdefghijklmnopqrstuu").contains("hidden");
    }

    @Test
    void caddyValidConfigIsSavedWrittenAndReloaded() throws Exception {
        addProxy("CADDY", "");
        container("caddy", "running");

        putCustom("respond /ping 200").andExpect(status().isOk()).andExpect(jsonPath("$.custom").value("respond /ping 200"));

        assertThat(customOf()).isEqualTo("respond /ping 200");
        ArgumentCaptor<List<ContainerFileWrite>> captor = writesCaptor();
        verify(fileService, org.mockito.Mockito.times(2)).writeFiles(any(), any(), eq("proxy1"), eq("/etc/shipir-proxy"),
                captor.capture(), anyLong());
        List<ContainerFileWrite> live = captor.getAllValues().get(1);
        assertThat(live).extracting(ContainerFileWrite::path).contains("custom.caddy", "Caddyfile");
        assertThat(execCommands.stream().anyMatch(c -> String.join(" ", c).contains("caddy reload"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_custom_config_versions", Integer.class)).isEqualTo(1);
    }

    @Test
    void caddyValidationWithoutARunningProxyIsReportedNotSkipped() throws Exception {
        addProxy("CADDY", "");
        container("caddy", "exited");
        putCustom("respond 200").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));
        assertThat(customOf()).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    void grant(String username) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, 'OPERATE' FROM users WHERE username = ?",
                hostId, username);
    }

    org.springframework.test.web.servlet.ResultActions putCustom(String content) throws Exception {
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("content", content));
        return mvc.perform(login("admin").write(put(url() + "/custom")).contentType(APPLICATION_JSON).content(json));
    }

    String url() {
        return "/api/hosts/" + hostId + "/proxy/config";
    }

    void addProxy(String type, String custom) {
        jdbc.update("INSERT INTO proxy_instances (host_id, type, default_cert_mode, custom_config) VALUES (?, ?, 'INTERNAL', ?)",
                hostId, type, custom);
    }

    UUID addVersion(String content) {
        return jdbc.queryForObject("INSERT INTO proxy_custom_config_versions (proxy_instance_id, content, created_by) "
                + "SELECT id, ?, 'someone' FROM proxy_instances WHERE host_id = ? RETURNING id", UUID.class, content, hostId);
    }

    String customOf() {
        return jdbc.queryForObject("SELECT custom_config FROM proxy_instances WHERE host_id = ?", String.class, hostId);
    }

    void container(String service, String state) {
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("proxy1", "shipir-proxy-x-1", "img", state, "s", null, Instant.now(), List.of(),
                        "shipir-proxy", service)));
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ContainerFileWrite>> writesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    List<ContainerFileWrite> writes() {
        ArgumentCaptor<List<ContainerFileWrite>> captor = writesCaptor();
        verify(fileService).writeFiles(any(), any(), eq("proxy1"), eq("/etc/shipir-proxy"), captor.capture(), anyLong());
        return captor.getValue();
    }

    void assertNothingWritten() {
        verify(fileService, never()).writeFiles(any(), any(), any(), any(), any(), anyLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_custom_config_versions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT custom_config_updated_at FROM proxy_instances", Object.class)).isNull();
    }
}
