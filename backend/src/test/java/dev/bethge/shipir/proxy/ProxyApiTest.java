package dev.bethge.shipir.proxy;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentRunService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.HttpStatus;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The managed proxy API against real Postgres, the real deployment services and a fake {@code docker compose}; the
 * Docker daemon is stubbed. The real daemon is covered by {@link ProxyRealDockerTest}.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@ExtendWith(OutputCaptureExtension.class)
class ProxyApiTest {

    static final String PASSWORD = "A-test-Passw0rd-for-proxies!";
    static final String SECRET = "cf-token-SUPER-SECRET-0123456789";
    static final Path BASE_DIR = baseDir();

    private static Path baseDir() {
        try {
            return Files.createTempDirectory("shipir-proxy-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("shipir.stacks.base-dir", BASE_DIR::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ComposeFileRepository composeFiles;
    @MockitoBean
    ComposeExecutionService execution;
    @MockitoBean
    ContainerService containers;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    ContainerFileService fileService;
    @MockitoBean
    ExecService exec;
    @MockitoBean
    VolumeService volumes;
    @MockitoSpyBean
    DeploymentRunService runs;

    UUID hostId;
    final List<ComposeCommand> commands = new CopyOnWriteArrayList<>();
    volatile List<ContainerDto> containerList = List.of();
    volatile List<NetworkDto> networkList = List.of();

    @BeforeEach
    void reset() throws Exception {
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
                + "SELECT 'Proxy host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        commands.clear();
        containerList = List.of();
        networkList = List.of(new NetworkDto("n0", "bridge", "bridge", "local", null, false, 0));
        org.mockito.Mockito.reset(containers, networks, fileService, exec, volumes, runs);
        when(containers.listContainers(any(), any())).thenAnswer(inv -> containerList);
        when(networks.list(any(), any())).thenAnswer(inv -> networkList);
        when(networks.createNetwork(any(), any(), any())).thenReturn(new CreatedResourceDto("n1", "shipir-proxy"));
        org.mockito.stubbing.Answer<ComposeProcessResult> fake = inv -> {
            UUID h = inv.getArgument(0);
            String name = inv.getArgument(1);
            ComposeCommand command = inv.getArgument(2);
            if (command == ComposeCommand.CONFIG_JSON) {
                Map<String, Object> config = new java.util.LinkedHashMap<>();
                config.put("name", name);
                config.putAll(tools.jackson.dataformat.yaml.YAMLMapper.builder().build().readValue(
                        composeFiles.readCompose(h, name).orElse(""), Map.class));
                return new ComposeProcessResult(0, false,
                        List.of(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(config)));
            }
            if (command == ComposeCommand.UP || command == ComposeCommand.DOWN) {
                commands.add(command);
                if (command == ComposeCommand.UP) {
                    String service = composeFiles.readCompose(h, name).orElse("").contains("caddy:") ? "caddy" : "traefik";
                    containerList = List.of(new ContainerDto("proxy1", "shipir-proxy-" + service + "-1", "img",
                            "running", "Up", null, Instant.now(), List.of("80:80/tcp", "443:443/tcp"),
                            "shipir-proxy", service));
                } else {
                    containerList = List.of();
                }
            }
            return new ComposeProcessResult(0, false, List.of());
        };
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---- helpers -------------------------------------------------------------------------------------------

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

    void grant(String username, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, ? FROM users WHERE username = ?",
                hostId, level, username);
    }

    String url() {
        return "/api/hosts/" + hostId + "/proxy/managed";
    }

    static final String TRAEFIK_HTTP = "{\"type\":\"TRAEFIK\",\"acmeEmail\":\"ops@example.com\",\"defaultCertMode\":\"ACME_HTTP\"}";
    static final String TRAEFIK_DNS = "{\"type\":\"TRAEFIK\",\"acmeEmail\":\"ops@example.com\",\"defaultCertMode\":\"ACME_DNS\","
            + "\"dnsProvider\":\"cloudflare\",\"dnsCredentials\":{\"CF_DNS_API_TOKEN\":\"" + SECRET + "\"}}";

    void setup(String body) throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    List<ContainerFileWrite> awaitWrites() {
        ArgumentCaptor<List<ContainerFileWrite>> captor = ArgumentCaptor.forClass(List.class);
        verify(fileService, timeout(15000)).writeFiles(any(), any(), eq("proxy1"), eq("/etc/shipir-proxy"),
                captor.capture(), anyLong());
        return captor.getValue();
    }

    // ---- setup ---------------------------------------------------------------------------------------------

    @Test
    void setupWithTraefikDeploysTheStackAndWritesTheManagedFileIntoTheContainer() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_HTTP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instance.type").value("TRAEFIK"))
                .andExpect(jsonPath("$.instance.acmeEmail").value("ops@example.com"))
                .andExpect(jsonPath("$.instance.defaultCertMode").value("ACME_HTTP"))
                .andExpect(jsonPath("$.instance.deploymentId").isNotEmpty())
                .andExpect(jsonPath("$.runId").isNotEmpty());

        verify(networks).createNetwork(any(), any(), org.mockito.ArgumentMatchers.argThat(s -> "shipir-proxy".equals(s.name())));
        List<ContainerFileWrite> writes = awaitWrites();
        assertThat(writes).extracting(ContainerFileWrite::path).containsExactly("custom.yml", "managed.yml");
        assertThat(commands).contains(ComposeCommand.UP);
        String env = composeFiles.readEnv(hostId, "shipir-proxy").orElseThrow();
        assertThat(env).contains("ACME_EMAIL=ops@example.com").contains("DNS_PROVIDER=manual");
        assertThat(jdbc.queryForObject("SELECT deployment_id IS NOT NULL FROM proxy_instances", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_SETUP'", String.class)).hasSize(1);
    }

    @Test
    void anExistingShipirProxyNetworkIsReused() throws Exception {
        networkList = List.of(new NetworkDto("n9", "shipir-proxy", "bridge", "local", null, false, 0));

        setup(TRAEFIK_HTTP);

        verify(networks, never()).createNetwork(any(), any(), any());
    }

    @Test
    void setupIsBlockedWhenAnotherProxyRuns() throws Exception {
        containerList = List.of(new ContainerDto("t1", "traefik", "traefik:v3.1", "running", "Up", null, Instant.now(),
                List.of(), null, null));
        when(containers.inspect(any(), any(), eq("t1"))).thenReturn(new ContainerDetailsDto("t1", "traefik",
                "traefik:v3.1", "running", null, null, 0, null, List.of(), List.of(), false, false, List.of(),
                List.of(), Map.of(), List.of(new ContainerDetailsDto.Network("proxy", null, null, null, List.of())),
                List.of(), null, null, null));
        Login admin = login("admin");

        mvc.perform(get(url()).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.setupBlockedReason").value("EXISTING_PROXY"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_HTTP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROXY_SETUP_BLOCKED"))
                .andExpect(jsonPath("$.details[0]").value("EXISTING_PROXY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_instances", Integer.class)).isZero();
        assertThat(commands).isEmpty();
    }

    @Test
    void setupIsBlockedWhenPort80Or443IsBound() throws Exception {
        containerList = List.of(new ContainerDto("w1", "web", "nginx", "running", "Up", null, Instant.now(),
                List.of("0.0.0.0:443:8443/tcp"), "app", "web"));
        Login admin = login("admin");

        mvc.perform(get(url()).session(admin.session()))
                .andExpect(jsonPath("$.setupBlockedReason").value("PORTS_IN_USE"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_HTTP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROXY_SETUP_BLOCKED"))
                .andExpect(jsonPath("$.details[0]").value("PORTS_IN_USE"));
        assertThat(commands).isEmpty();
    }

    @Test
    void aSecondSetupIsRejected() throws Exception {
        setup(TRAEFIK_HTTP);
        awaitWrites();

        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_HTTP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROXY_EXISTS"));
    }

    @Test
    void viewerAndOperatorCannotSetUpOrChange() throws Exception {
        grant("operator", "OPERATE");
        grant("viewer", "VIEW");
        for (String user : List.of("viewer", "operator")) {
            Login l = login(user);
            mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_HTTP)).andExpect(status().isForbidden());
            mvc.perform(l.write(put(url())).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
            mvc.perform(l.write(delete(url()))).andExpect(status().isForbidden());
        }
        assertThat(commands).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_instances", Integer.class)).isZero();
    }

    @Test
    void everyRoleWithAccessSeesTheHostViewAndAnInvisibleHostIs404() throws Exception {
        grant("viewer", "VIEW");
        mvc.perform(get(url()).session(login("viewer").session())).andExpect(status().isOk());
        mvc.perform(get(url()).session(login("operator").session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/hosts/" + UUID.randomUUID() + "/proxy/managed").session(login("admin").session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminWritesAreRefusedBeforeAnyHostLookup() throws Exception {
        // ADMIN is enforced by SecurityConfig (403 for other roles), so visibility is only observable for reads;
        // a non-admin never reaches the service for writes.
        mvc.perform(login("operator").write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_HTTP))
                .andExpect(status().isForbidden());
    }

    @Test
    void caddyWithTheDnsChallengeIsRejected() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(
                        "{\"type\":\"CADDY\",\"defaultCertMode\":\"ACME_DNS\",\"dnsProvider\":\"cloudflare\","
                                + "\"dnsCredentials\":{\"CF_DNS_API_TOKEN\":\"" + SECRET + "\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FOR_PROXY"));
        assertThat(commands).isEmpty();
    }

    @Test
    void dnsChallengeWithoutCredentialsIsRejected() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(
                        "{\"type\":\"TRAEFIK\",\"acmeEmail\":\"a@example.com\",\"defaultCertMode\":\"ACME_DNS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DNS_CREDENTIALS_REQUIRED"));
    }

    @Test
    void invalidBodiesAreRejected() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(
                "{\"type\":\"TRAEFIK\",\"acmeEmail\":\"not-an-email\",\"defaultCertMode\":\"ACME_HTTP\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
    }

    // ---- credentials never leak ----------------------------------------------------------------------------

    @Test
    void dnsCredentialsAreNeverInResponsesLogsAuditOrTheDatabaseInPlaintext(CapturedOutput output) throws Exception {
        Login admin = login("admin");
        String created = mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(TRAEFIK_DNS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instance.dnsProvider").value("cloudflare"))
                .andExpect(jsonPath("$.instance.dnsCredentialsHint").value(org.hamcrest.Matchers.containsString("CF_DNS_API_TOKEN")))
                .andReturn().getResponse().getContentAsString();
        awaitWrites();
        String view = mvc.perform(get(url()).session(admin.session())).andReturn().getResponse().getContentAsString();
        String updated = mvc.perform(admin.write(put(url())).contentType(APPLICATION_JSON)
                        .content("{\"acmeEmail\":\"new@example.com\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(created + view + updated).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
        assertThat(jdbc.queryForObject("SELECT string_agg(coalesce(details::text, ''), ' ') FROM audit_log", String.class))
                .doesNotContain(SECRET);
        assertThat(jdbc.queryForObject("SELECT encode(dns_credentials_encrypted, 'escape') FROM proxy_instances",
                String.class)).doesNotContain(SECRET);
        assertThat(jdbc.queryForObject("SELECT dns_credentials_hint FROM proxy_instances", String.class))
                .doesNotContain(SECRET);
        // the credentials do reach the proxy through the deployment's .env, which is the point
        assertThat(composeFiles.readEnv(hostId, "shipir-proxy").orElseThrow()).contains("CF_DNS_API_TOKEN").contains(SECRET);
    }

    @Test
    void tooManyCredentialVariablesAreRejected() throws Exception {
        StringBuilder creds = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            creds.append(i == 0 ? "" : ",").append("\"K").append(i).append("\":\"v\"");
        }
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(
                        "{\"type\":\"TRAEFIK\",\"acmeEmail\":\"a@example.com\",\"defaultCertMode\":\"ACME_HTTP\","
                                + "\"dnsProvider\":\"cloudflare\",\"dnsCredentials\":{" + creds + "}}"))
                .andExpect(status().isBadRequest());
    }

    // ---- update --------------------------------------------------------------------------------------------

    @Test
    void changingTheEmailOfTraefikRedeploysAndKeepsStoredCredentials() throws Exception {
        setup(TRAEFIK_DNS);
        awaitWrites();
        long upsBefore = commands.stream().filter(c -> c == ComposeCommand.UP).count();

        mvc.perform(login("admin").write(put(url())).contentType(APPLICATION_JSON).content("{\"acmeEmail\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.acmeEmail").value("new@example.com"))
                .andExpect(jsonPath("$.instance.dnsCredentialsHint").isNotEmpty())
                .andExpect(jsonPath("$.runId").isNotEmpty());

        assertThat(commands.stream().filter(c -> c == ComposeCommand.UP).count()).isEqualTo(upsBefore + 1);
        String env = composeFiles.readEnv(hostId, "shipir-proxy").orElseThrow();
        assertThat(env).contains("ACME_EMAIL=new@example.com").contains(SECRET);
    }

    @Test
    void changingOnlyTheDefaultModeSyncsWithoutARedeploy() throws Exception {
        setup(TRAEFIK_DNS);
        awaitWrites();
        long upsBefore = commands.stream().filter(c -> c == ComposeCommand.UP).count();

        mvc.perform(login("admin").write(put(url())).contentType(APPLICATION_JSON).content("{\"defaultCertMode\":\"INTERNAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.defaultCertMode").value("INTERNAL"))
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.nullValue()));

        assertThat(commands.stream().filter(c -> c == ComposeCommand.UP).count()).isEqualTo(upsBefore);
        verify(fileService, org.mockito.Mockito.times(2)).writeFiles(any(), any(), eq("proxy1"), any(), any(), anyLong());
    }

    @Test
    void credentialsCanBeRemovedExplicitlyButNotWhileTheDnsChallengeIsTheDefault() throws Exception {
        setup(TRAEFIK_DNS);
        awaitWrites();
        Login admin = login("admin");

        mvc.perform(admin.write(put(url())).contentType(APPLICATION_JSON).content("{\"removeDnsCredentials\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DNS_IN_USE"));
        mvc.perform(admin.write(put(url())).contentType(APPLICATION_JSON)
                        .content("{\"defaultCertMode\":\"ACME_HTTP\",\"removeDnsCredentials\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.dnsProvider").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.instance.dnsCredentialsHint").value(org.hamcrest.Matchers.nullValue()));
        assertThat(composeFiles.readEnv(hostId, "shipir-proxy").orElseThrow()).doesNotContain(SECRET);
    }

    @Test
    void updatingWithoutAProxyIs404() throws Exception {
        mvc.perform(login("admin").write(put(url())).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROXY_NOT_FOUND"));
    }

    // ---- delete --------------------------------------------------------------------------------------------

    @Test
    void removingTheProxyTearsDownDeletesRoutesAndKeepsCertificates() throws Exception {
        setup(TRAEFIK_HTTP);
        awaitWrites();
        jdbc.update("INSERT INTO proxy_certificates (host_id, name, cert_pem, key_encrypted, domains, not_after) "
                + "VALUES (?, 'mine', 'PEM', '\\x01', ARRAY['a.example.com'], now() + interval '30 days')", hostId);
        jdbc.update("INSERT INTO proxy_routes (host_id, hostname, container_name, target_port, backend_scheme, cert_mode) "
                + "VALUES (?, 'app.example.com', 'web', 80, 'http', 'ACME_HTTP')", hostId);

        mvc.perform(login("admin").write(delete(url()))).andExpect(status().isNoContent());

        assertThat(commands).contains(ComposeCommand.DOWN);
        verify(networks).disconnectContainer(any(), any(), eq("shipir-proxy"), eq("web"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_instances", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_certificates", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action = 'PROXY_REMOVE'", String.class)).hasSize(1);
        mvc.perform(get(url()).session(login("admin").session()))
                .andExpect(jsonPath("$.instance").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void removingWithoutAProxyIs404() throws Exception {
        mvc.perform(login("admin").write(delete(url()))).andExpect(status().isNotFound());
    }

    @Test
    void theHostViewShowsTheDeploymentStatusOfTheProxy() throws Exception {
        setup(TRAEFIK_HTTP);
        awaitWrites();
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM deployments", String.class)).isEqualTo("RUNNING"));

        mvc.perform(get(url()).session(login("admin").session()))
                .andExpect(jsonPath("$.instance.deploymentStatus").value("RUNNING"))
                .andExpect(jsonPath("$.setupBlockedReason").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---- review fixes --------------------------------------------------------------------------------------

    @Test
    void aFailedRedeployDoesNotStoreTheChangeSoARetryRedeploysAgain() throws Exception {
        setup(TRAEFIK_HTTP);
        awaitWrites();
        org.mockito.Mockito.doThrow(new DeploymentException(HttpStatus.CONFLICT, "DEPLOYMENT_IN_PROGRESS", "busy"))
                .doCallRealMethod().when(runs).deploy(any(), any(), any());
        long upsBefore = commands.stream().filter(c -> c == ComposeCommand.UP).count();
        Login admin = login("admin");
        String body = "{\"acmeEmail\":\"new@example.com\"}";

        mvc.perform(admin.write(put(url())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT acme_email FROM proxy_instances", String.class)).isEqualTo("ops@example.com");

        mvc.perform(admin.write(put(url())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT acme_email FROM proxy_instances", String.class)).isEqualTo("new@example.com");
        assertThat(commands.stream().filter(c -> c == ComposeCommand.UP).count()).isEqualTo(upsBefore + 1);
        assertThat(composeFiles.readEnv(hostId, "shipir-proxy").orElseThrow()).contains("ACME_EMAIL=new@example.com");
    }

    @Test
    void composeFailureDetailsAreNotForwardedBecauseTheyCanEchoEnvValues() throws Exception {
        setup(TRAEFIK_DNS);
        awaitWrites();
        org.mockito.Mockito.doThrow(new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_FAILED", "failed",
                List.of("error interpolating CF_DNS_API_TOKEN=" + SECRET))).when(runs).deploy(any(), any(), any());

        String response = mvc.perform(login("admin").write(put(url())).contentType(APPLICATION_JSON)
                        .content("{\"acmeEmail\":\"new@example.com\"}"))
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(SECRET).contains("COMPOSE_FAILED");
    }

    @Test
    void removingTheProxyDeletesTheConfigVolumeButNotTheAcmeVolumeOrTheNetwork() throws Exception {
        setup(TRAEFIK_HTTP);
        awaitWrites();

        mvc.perform(login("admin").write(delete(url()))).andExpect(status().isNoContent());

        verify(volumes).removeVolume(any(), any(), eq("shipir-proxy_shipir-proxy-config"));
        verify(volumes, org.mockito.Mockito.times(1)).removeVolume(any(), any(), any());
        verify(networks, never()).removeNetwork(any(), any(), any());
    }

    @Test
    void aFailingConfigVolumeRemovalDoesNotFailTheRemoval() throws Exception {
        setup(TRAEFIK_HTTP);
        awaitWrites();
        org.mockito.Mockito.doThrow(new DockerConnectionException(DockerErrorCode.IN_USE, "x", null))
                .when(volumes).removeVolume(any(), any(), any());

        mvc.perform(login("admin").write(delete(url()))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_instances", Integer.class)).isZero();
    }
}
