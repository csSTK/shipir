package dev.bethge.shipir.proxy;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Publish/unpublish API against real Postgres; Docker and the proxy sync are stubbed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProxyRouteApiTest {

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
    NetworkService networks;
    @MockitoSpyBean
    ProxySyncService sync;

    UUID hostId;
    UUID deploymentId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM proxy_routes");
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
                + "SELECT 'Route host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        deploymentId = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, hostId);
        jdbc.update("INSERT INTO proxy_instances (host_id, type, acme_email, default_cert_mode) "
                + "VALUES (?, 'TRAEFIK', 'ops@example.com', 'ACME_HTTP')", hostId);
        org.mockito.Mockito.reset(containers, networks, sync);
        doNothing().when(sync).sync(any());
        container("web1", "shop-web-1", "shop", "running", 80);
        container("db1", "shop-db-1", "shop", "running", 5432, 3306);
        container("other1", "other-1", "other", "running", 8080);
    }

    void container(String id, String name, String project, String state, int... exposed) {
        List<ContainerDto> list = new java.util.ArrayList<>(listed());
        list.removeIf(c -> c.id().equals(id));
        List<String> listedPorts = java.util.Arrays.stream(exposed).mapToObj(p -> p + "/tcp").toList();
        list.add(new ContainerDto(id, name, "img", state, "Up", null, Instant.now(), listedPorts, project, "svc"));
        when(containers.listContainers(any(), any())).thenReturn(list);
        List<ContainerDetailsDto.Port> ports = java.util.Arrays.stream(exposed)
                .mapToObj(p -> new ContainerDetailsDto.Port(p, "tcp", null, null)).toList();
        when(containers.inspect(any(), any(), eq(id))).thenReturn(new ContainerDetailsDto(id, name, "img", state, null,
                null, 0, null, List.of(), List.of(), false, false, List.of(), List.of(), Map.of(), List.of(), ports,
                null, project, "svc"));
    }

    List<ContainerDto> listed() {
        try {
            return containers.listContainers(null, null);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

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

    void grantDeployment(String username) {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' FROM users "
                + "WHERE username = ?", deploymentId, username);
    }

    String url() {
        return "/api/hosts/" + hostId + "/proxy/routes";
    }

    String body(String hostname, String container, String extra) {
        return "{\"hostname\":\"%s\",\"containerName\":\"%s\"%s}".formatted(hostname, container, extra);
    }

    String publish(Login who, String body) throws Exception {
        return mvc.perform(who.write(post(url())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    UUID routeId(String hostname) {
        return jdbc.queryForObject("SELECT id FROM proxy_routes WHERE hostname = ?", UUID.class, hostname);
    }

    @Test
    void publishDetectsTheSingleExposedPortConnectsTheNetworkAndSyncs() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hostname").value("shop.example.com"))
                .andExpect(jsonPath("$.targetPort").value(80))
                .andExpect(jsonPath("$.certMode").value("ACME_HTTP"))
                .andExpect(jsonPath("$.advancedActive").value(false))
                .andExpect(jsonPath("$.containerState").value("running"));

        verify(networks).connectContainer(any(), any(), eq(ProxySyncService.PROXY_NETWORK), eq("web1"));
        verify(sync, times(1)).sync(hostId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_ROUTE_CREATE'", String.class))
                .hasSize(1);
    }

    @Test
    void ambiguousPortsAnswerPortRequiredWithTheOptionsAndWriteNothing() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("db.example.com", "shop-db-1", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PORT_REQUIRED"))
                .andExpect(jsonPath("$.details[0]").value("3306"))
                .andExpect(jsonPath("$.details[1]").value("5432"));

        verify(networks, never()).connectContainer(any(), any(), any(), any());
        verify(sync, never()).sync(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
    }

    @Test
    void anExplicitPortAvoidsDetection() throws Exception {
        publish(login("admin"), body("db.example.com", "shop-db-1", ",\"port\":5432"));
        assertThat(jdbc.queryForObject("SELECT target_port FROM proxy_routes", Integer.class)).isEqualTo(5432);
    }

    @Test
    void duplicateHostnameIs409AndTouchesNothing() throws Exception {
        Login admin = login("admin");
        publish(admin, body("shop.example.com", "shop-web-1", ""));

        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "other-1", "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_HOST_TAKEN"));

        verify(networks, times(1)).connectContainer(any(), any(), any(), any());
        verify(sync, times(1)).sync(any());
    }

    @Test
    void withoutAnInstanceTheAnswerIs409ProxyNotSetUp() throws Exception {
        jdbc.update("DELETE FROM proxy_instances");
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROXY_NOT_SET_UP"));
    }

    @Test
    void viewerCannotPublishUpdateOrUnpublishButCanList() throws Exception {
        UUID id = routeFor(login("admin"));
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, 'VIEW' FROM users "
                + "WHERE username = 'viewer'", hostId);
        Login viewer = login("viewer");

        mvc.perform(viewer.write(post(url())).contentType(APPLICATION_JSON)
                .content(body("a.example.com", "shop-web-1", ""))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(body("a.example.com", "shop-web-1", ""))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(delete(url() + "/" + id))).andExpect(status().isForbidden());
        mvc.perform(get(url()).session(viewer.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    UUID routeFor(Login admin) throws Exception {
        publish(admin, body("shop.example.com", "shop-web-1", ""));
        return routeId("shop.example.com");
    }

    @Test
    void deploymentOnlyGrantPublishesItsOwnContainerButNotAForeignOne() throws Exception {
        grantDeployment("operator");
        Login op = login("operator");

        publish(op, body("shop.example.com", "shop-web-1", ""));
        mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("foreign.example.com", "other-1", "")))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForList("SELECT hostname FROM proxy_routes", String.class)).containsExactly("shop.example.com");
        verify(networks, never()).connectContainer(any(), any(), any(), eq("other1"));
    }

    @Test
    void deploymentOnlyGrantSeesOnlyRoutesAndCandidatesOfItsContainers() throws Exception {
        Login admin = login("admin");
        publish(admin, body("shop.example.com", "shop-web-1", ""));
        publish(admin, body("foreign.example.com", "other-1", ""));
        grantDeployment("operator");
        Login op = login("operator");

        mvc.perform(get(url()).session(op.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hostname").value("shop.example.com"));
        mvc.perform(get(url() + "/candidates").session(op.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.containerName=='other-1')]").isEmpty());
    }

    @Test
    void candidatesListPortsSuggestionAndPublishedFlag() throws Exception {
        Login admin = login("admin");
        publish(admin, body("shop.example.com", "shop-web-1", ""));

        mvc.perform(get(url() + "/candidates").session(admin.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.containerName=='shop-web-1')].suggestedPort").value(80))
                .andExpect(jsonPath("$[?(@.containerName=='shop-web-1')].published").value(true))
                .andExpect(jsonPath("$[?(@.containerName=='shop-db-1')].ports[0]").value(3306))
                .andExpect(jsonPath("$[?(@.containerName=='shop-db-1')].published").value(false));
    }

    @Test
    void candidatesTakePortsFromTheListCallWithoutInspectingAnyContainer() throws Exception {
        Login admin = login("admin");
        // published and exposed entries of the list call, plus a UDP one that must not count
        List<ContainerDto> list = new java.util.ArrayList<>(listed());
        list.removeIf(c -> c.id().equals("web1"));
        list.add(new ContainerDto("web1", "shop-web-1", "img", "running", "Up", null, Instant.now(),
                List.of("8080:80/tcp", "80/tcp", "53/udp"), "shop", "svc"));
        when(containers.listContainers(any(), any())).thenReturn(list);
        org.mockito.Mockito.clearInvocations(containers);

        mvc.perform(get(url() + "/candidates").session(admin.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.containerName=='shop-web-1')].ports[0]").value(80))
                .andExpect(jsonPath("$[?(@.containerName=='shop-web-1')].ports.length()").value(1))
                .andExpect(jsonPath("$[?(@.containerName=='shop-web-1')].suggestedPort").value(80))
                .andExpect(jsonPath("$[?(@.containerName=='shop-db-1')].ports[0]").value(3306));
        verify(containers, never()).inspect(any(), any(), any());
    }

    @Test
    void candidatesInspectOnlyStoppedContainersWhoseListedPortsAreEmpty() throws Exception {
        Login admin = login("admin");
        // a stopped container: the list call reports no ports, its exposed port comes from inspect
        container("old1", "shop-old-1", "shop", "exited", 8081);
        List<ContainerDto> list = new java.util.ArrayList<>(listed());
        list.removeIf(c -> c.id().equals("old1"));
        list.add(new ContainerDto("old1", "shop-old-1", "img", "exited", "Exited (0)", null, Instant.now(),
                List.of(), "shop", "svc"));
        when(containers.listContainers(any(), any())).thenReturn(list);
        org.mockito.Mockito.clearInvocations(containers);

        mvc.perform(get(url() + "/candidates").session(admin.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.containerName=='shop-old-1')].ports[0]").value(8081))
                .andExpect(jsonPath("$[?(@.containerName=='shop-old-1')].suggestedPort").value(8081))
                .andExpect(jsonPath("$[?(@.containerName=='shop-web-1')].ports[0]").value(80));
        verify(containers, times(1)).inspect(any(), any(), eq("old1"));
        verify(containers, never()).inspect(any(), any(), eq("web1"));
        verify(containers, never()).inspect(any(), any(), eq("db1"));
    }

    @Test
    void unpublishDetachesTheNetworkOnlyWhenNoOtherRouteUsesTheContainer() throws Exception {
        Login admin = login("admin");
        publish(admin, body("a.example.com", "shop-web-1", ""));
        publish(admin, body("b.example.com", "shop-web-1", ",\"pathPrefix\":\"/api\""));

        mvc.perform(admin.write(delete(url() + "/" + routeId("a.example.com")))).andExpect(status().isNoContent());
        verify(networks, never()).disconnectContainer(any(), any(), any(), any());

        mvc.perform(admin.write(delete(url() + "/" + routeId("b.example.com")))).andExpect(status().isNoContent());
        verify(networks).disconnectContainer(any(), any(), eq(ProxySyncService.PROXY_NETWORK), eq("web1"));
        verify(sync, times(4)).sync(hostId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_ROUTE_DELETE'", String.class))
                .hasSize(2);
    }

    @Test
    void unpublishToleratesAContainerThatNoLongerExists() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        when(containers.listContainers(any(), any())).thenReturn(List.of());

        mvc.perform(admin.write(delete(url() + "/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
    }

    @Test
    void anAcmeDnsRouteNeedsDnsCredentialsOnTheProxyForPublishAndUpdate() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("dns.example.com", "shop-web-1", ",\"certMode\":\"ACME_DNS\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DNS_CREDENTIALS_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
        verify(sync, never()).sync(any());

        UUID id = routeFor(admin);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", ",\"certMode\":\"ACME_DNS\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DNS_CREDENTIALS_REQUIRED"));
    }

    @Test
    void anInheritedAcmeDnsDefaultWithoutCredentialsIsRejectedToo() throws Exception {
        jdbc.update("UPDATE proxy_instances SET default_cert_mode = 'ACME_DNS'");
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("dns.example.com", "shop-web-1", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DNS_CREDENTIALS_REQUIRED"));
    }

    @Test
    void anAcmeDnsRouteIsAcceptedWhenTheProxyHasDnsCredentials() throws Exception {
        jdbc.update("UPDATE proxy_instances SET dns_provider = 'cloudflare', dns_credentials_encrypted = '\\x0102', "
                + "dns_credentials_hint = 'xx'");
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("dns.example.com", "shop-web-1", ",\"certMode\":\"ACME_DNS\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certMode").value("ACME_DNS"));
    }

    @Test
    void updateChangesTheRouteSyncsAndAudits() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(body("new.example.com", "shop-web-1", ",\"port\":8080,\"redirectHttps\":false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("new.example.com"))
                .andExpect(jsonPath("$.targetPort").value(8080))
                .andExpect(jsonPath("$.advancedActive").value(true));

        verify(sync, times(2)).sync(hostId);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_ROUTE_UPDATE'", String.class))
                .hasSize(1);
    }

    @Test
    void movingARouteToAnotherContainerDetachesTheOldOneWhenUnused() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "other-1", "")))
                .andExpect(status().isOk());

        verify(networks).connectContainer(any(), any(), any(), eq("other1"));
        verify(networks).disconnectContainer(any(), any(), eq(ProxySyncService.PROXY_NETWORK), eq("web1"));
    }

    @Test
    void basicAuthPasswordsAreStoredAsBcryptAndNeverReturned() throws Exception {
        Login admin = login("admin");
        String response = publish(admin, body("shop.example.com", "shop-web-1",
                ",\"basicAuth\":{\"alice\":\"s3cret-pw\"}"));

        assertThat(response).doesNotContain("s3cret-pw").doesNotContain("$2");
        assertThat(response).contains("\"basicAuthUsers\":[\"alice\"]");
        String stored = jdbc.queryForObject("SELECT basic_auth_users->>'alice' FROM proxy_routes", String.class);
        assertThat(stored).startsWith("$2");
        assertThat(BCrypt.checkpw("s3cret-pw", stored)).isTrue();
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'PROXY_ROUTE_CREATE'",
                String.class)).doesNotContain("s3cret-pw");
    }

    @Test
    void updateKeepsAUsersHashWhenNoNewPasswordIsGiven() throws Exception {
        Login admin = login("admin");
        publish(admin, body("shop.example.com", "shop-web-1", ",\"basicAuth\":{\"alice\":\"s3cret-pw\"}"));
        String before = jdbc.queryForObject("SELECT basic_auth_users->>'alice' FROM proxy_routes", String.class);

        mvc.perform(admin.write(put(url() + "/" + routeId("shop.example.com"))).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", ",\"basicAuth\":{\"alice\":null,\"bob\":\"pw-two-2\"}")))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT basic_auth_users->>'alice' FROM proxy_routes", String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT basic_auth_users->>'bob' FROM proxy_routes", String.class)).startsWith("$2");
    }

    @Test
    void aMissingProxyNetworkOnAttachIs502ProxyAttachFailedAndSavesNoRoute() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "gone", null)).when(networks)
                .connectContainer(any(), any(), any(), any());
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_ATTACH_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
        verify(sync, never()).sync(any());
    }

    @Test
    void anUnreachableDaemonOnAttachIs502AndSavesNoRoute() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null)).when(networks)
                .connectContainer(any(), any(), any(), any());
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UNREACHABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
    }

    ProxyException syncFailure() {
        return new ProxyException(org.springframework.http.HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED", "sync failed");
    }

    @Test
    void aSyncFailureOnPublishRollsTheRouteBackDetachesTheNetworkAndAllowsARetry() throws Exception {
        Login admin = login("admin");
        doThrow(syncFailure()).when(sync).sync(any());
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
        verify(networks).disconnectContainer(any(), any(), eq(ProxySyncService.PROXY_NETWORK), eq("web1"));

        doNothing().when(sync).sync(any());
        publish(admin, body("shop.example.com", "shop-web-1", ""));
    }

    @Test
    void aSyncFailureOnPublishRepairsTheProxyWithASecondSyncAndKeepsTheOriginalError() throws Exception {
        doThrow(syncFailure()).when(sync).sync(any());
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));
        // first sync with the route, second (repair) after the rollback removed it
        verify(sync, times(2)).sync(hostId);
    }

    @Test
    void aSyncFailureOnUpdateRepairsWithASecondSyncAndKeepsTheOriginalError() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        org.mockito.Mockito.clearInvocations(sync);
        doThrow(syncFailure()).doNothing().when(sync).sync(any());

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", ",\"port\":81")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));

        verify(sync, times(2)).sync(hostId);
        assertThat(jdbc.queryForObject("SELECT target_port FROM proxy_routes", Integer.class)).isEqualTo(81);
    }

    @Test
    void aSyncFailureOnUnpublishRepairsWithASecondSyncAndKeepsTheOriginalError() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        org.mockito.Mockito.clearInvocations(sync);
        doThrow(syncFailure()).doNothing().when(sync).sync(any());

        mvc.perform(admin.write(delete(url() + "/" + id)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));

        verify(sync, times(2)).sync(hostId);
    }

    @Test
    void aFailingRepairSyncDoesNotReplaceTheOriginalError() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        org.mockito.Mockito.clearInvocations(sync);
        doThrow(syncFailure()).when(sync).sync(any());

        mvc.perform(admin.write(delete(url() + "/" + id)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));
        verify(sync, times(2)).sync(hostId);
    }

    @Test
    void aFailingDetachOnUnpublishStillSyncsFirst() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        org.mockito.Mockito.clearInvocations(sync);
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null)).when(networks)
                .disconnectContainer(any(), any(), any(), any());

        mvc.perform(admin.write(delete(url() + "/" + id))).andExpect(status().isBadGateway());

        verify(sync).sync(hostId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
    }

    @Test
    void aSyncFailureOnAMovingUpdateStillDetachesTheOldContainerAndKeepsTheOriginalError() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        org.mockito.Mockito.clearInvocations(sync, networks);
        doThrow(syncFailure()).doNothing().when(sync).sync(any());

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "other-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));

        verify(networks).disconnectContainer(any(), any(), eq(ProxySyncService.PROXY_NETWORK), eq("web1"));
    }

    @Test
    void aFailingDetachAfterAFailedMovingUpdateDoesNotReplaceTheOriginalError() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        doThrow(syncFailure()).doNothing().when(sync).sync(any());
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null)).when(networks)
                .disconnectContainer(any(), any(), any(), any());

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "other-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROXY_SYNC_FAILED"));
    }

    @Test
    void aFailingDetachOnMoveStillSyncsFirst() throws Exception {
        Login admin = login("admin");
        UUID id = routeFor(admin);
        org.mockito.Mockito.clearInvocations(sync);
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null)).when(networks)
                .disconnectContainer(any(), any(), any(), any());

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(body("shop.example.com", "other-1", ""))).andExpect(status().isBadGateway());

        verify(sync).sync(hostId);
        assertThat(jdbc.queryForObject("SELECT container_name FROM proxy_routes", String.class)).isEqualTo("other-1");
    }

    @Test
    void theRequestToStringHidesBasicAuthPasswords() {
        var request = new dev.bethge.shipir.proxy.dto.ProxyRouteRequest("a.example.com", "web", 80, null, null, null,
                null, null, null, null, Map.of("alice", "s3cret-pw"), null);
        assertThat(request.toString()).doesNotContain("s3cret-pw").contains("a.example.com");
    }

    @Test
    void theProxyItselfCannotBePublished() throws Exception {
        container("px1", "shipir-proxy-traefik-1", "shipir-proxy", "running", 80);
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("proxy.example.com", "shipir-proxy-traefik-1", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTAINER"));
    }

    @Test
    void aViewOnlyDeploymentGrantSeesButCannotPublishItsContainer() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'VIEW' FROM users "
                + "WHERE username = 'operator'", deploymentId);
        Login op = login("operator");
        mvc.perform(get(url() + "/candidates").session(op.session())).andExpect(status().isOk());
        mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                .content(body("shop.example.com", "shop-web-1", ""))).andExpect(status().isForbidden());
    }

    @Test
    void aViewOnlyHostGrantCannotPublishAnOperatorWithoutAnyGrantSeesNoHost() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, 'VIEW' FROM users "
                + "WHERE username = 'operator'", hostId);
        Login op = login("operator");
        mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                .content(body("shop.example.com", "shop-web-1", ""))).andExpect(status().isForbidden());

        jdbc.update("DELETE FROM permissions");
        mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void aDeploymentOnlyUserCannotUpdateOrUnpublishAForeignRoute() throws Exception {
        publish(login("admin"), body("foreign.example.com", "other-1", ""));
        UUID id = routeId("foreign.example.com");
        grantDeployment("operator");
        Login op = login("operator");

        mvc.perform(op.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(body("hijack.example.com", "other-1", ""))).andExpect(status().isNotFound());
        mvc.perform(op.write(delete(url() + "/" + id))).andExpect(status().isNotFound());

        assertThat(jdbc.queryForList("SELECT hostname FROM proxy_routes", String.class)).containsExactly("foreign.example.com");
    }

    @Test
    void aBasicAuthPasswordOver72BytesIsRejected() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", ",\"basicAuth\":{\"alice\":\"" + "x".repeat(73) + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BASIC_AUTH"));
    }

    @Test
    void anUnknownBasicAuthUserWithoutAPasswordIsRejected() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", ",\"basicAuth\":{\"alice\":null}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BASIC_AUTH"));
    }

    @Test
    void unknownContainerIs404() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "nope", "")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTAINER_NOT_FOUND"));
    }

    @Test
    void aTlsFailureOnAttachIsUnreachableForAnOperatorAndTlsErrorForAnAdmin() throws Exception {
        grantDeployment("operator");
        doThrow(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "bad cert", null)).when(networks)
                .connectContainer(any(), any(), any(), any());
        mvc.perform(login("operator").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UNREACHABLE"));
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(body("shop.example.com", "shop-web-1", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TLS_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
    }
}
