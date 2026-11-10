package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import dev.bethge.shipir.common.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/search}: matching, limits, permission filtering and resilience against unreachable hosts. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class SearchApiTest extends HostApiTestBase {

    @Autowired
    SecretCipher cipher;

    @Autowired
    SearchService searchService;

    @MockitoBean
    ContainerService containers;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    private UUID alpha;
    private UUID beta;

    @BeforeEach
    void hosts() {
        SetupGateFixture.resetWithAdminHost(jdbc, "alpha-host");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, "
                + "tls_cert_encrypted, tls_key_encrypted, owner_user_id) SELECT 'beta-host', 'TCP', '203.0.113.8', 2376, "
                + "?, ?, ?, id FROM users WHERE username = 'admin'",
                cipher.encrypt("ca"), cipher.encrypt("cert"), cipher.encrypt("key"));
        alpha = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'alpha-host'", UUID.class);
        beta = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'beta-host'", UUID.class);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop-stack', ?, 'x', id FROM users WHERE username = 'admin'", alpha);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop-beta', ?, 'x', id FROM users WHERE username = 'admin'", beta);
        when(containers.listDeployments(eq(alpha), any())).thenReturn(
                List.of(new DeploymentSummaryDto("shop-stack", 1, 1, DeploymentSummaryDto.Status.RUNNING, "/a"),
                        new DeploymentSummaryDto("shopwild", 1, 1, DeploymentSummaryDto.Status.RUNNING, "/b")));
        when(containers.listContainers(eq(alpha), any())).thenReturn(List.of(
                container("c1", "shop-web-1", "nginx:1", "shop-stack"),
                container("c2", "db", "postgres:16", null)));
        when(containers.listDeployments(eq(beta), any())).thenReturn(
                List.of(new DeploymentSummaryDto("shop-beta", 1, 1, DeploymentSummaryDto.Status.RUNNING, "/c")));
        when(containers.listContainers(eq(beta), any())).thenReturn(List.of(
                container("c3", "shop-beta-1", "redis:7", "shop-beta")));
    }

    private static ContainerDto container(String id, String name, String image, String project) {
        return new ContainerDto(id, name, image, "running", "Up", null, null, List.of(), project, null);
    }

    @Test
    void adminFindsHostsDeploymentsAndContainersCaseInsensitive() throws Exception {
        Login admin = login("admin");
        mvc.perform(get("/api/search").param("q", "SHOP").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                // managed (2) + unmanaged live (1) + containers (2)
                .andExpect(jsonPath("$.results.length()").value(5))
                .andExpect(jsonPath("$.results[?(@.type=='HOST')]").isEmpty())
                .andExpect(jsonPath("$.results[?(@.name=='shopwild')].managed").value(false))
                .andExpect(jsonPath("$.results[?(@.name=='shopwild')].id").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.results[?(@.name=='shop-web-1')].detail").value("nginx:1"));
        mvc.perform(get("/api/search").param("q", "alpha").session(admin.session()))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].type").value("HOST"))
                .andExpect(jsonPath("$.results[0].id").value(alpha.toString()));
        mvc.perform(get("/api/search").param("q", "postgres").session(admin.session()))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].name").value("db"));
    }

    @Test
    void systemScopeCallerSeesLiveResultsFromWorkerThreads() {
        // No authentication, only SystemScope: the live() workers must still see everything.
        var response = dev.bethge.shipir.common.security.SystemScope.call(() -> searchService.search("shop"));
        org.assertj.core.api.Assertions.assertThat(response.results())
                .extracting(r -> r.name())
                .containsExactlyInAnyOrder("shop-stack", "shop-beta", "shopwild", "shop-web-1", "shop-beta-1");
    }

    @Test
    void callerWithoutAuthenticationAndWithoutSystemScopeFindsNothing() {
        org.assertj.core.api.Assertions.assertThat(searchService.search("shop").results()).isEmpty();
    }

    @Test
    void shortOrMissingQueryIs400() throws Exception {
        Login admin = login("admin");
        mvc.perform(get("/api/search").param("q", "a").session(admin.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/search").param("q", "  b ").session(admin.session())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").session(admin.session())).andExpect(status().isBadRequest());
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/search").param("q", "shop")).andExpect(status().isUnauthorized());
    }

    @Test
    void resultsAreLimitedTo50() throws Exception {
        List<ContainerDto> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(container("id" + i, "bulk-" + i, "img", null));
        }
        when(containers.listContainers(eq(alpha), any())).thenReturn(many);
        mvc.perform(get("/api/search").param("q", "bulk").session(login("admin").session()))
                .andExpect(jsonPath("$.results.length()").value(50)).andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    void userWithoutGrantsFindsNothing() throws Exception {
        mvc.perform(get("/api/search").param("q", "shop").session(login("viewer").session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void hostGrantShowsOnlyThatHost() throws Exception {
        grantHost("operator", alpha, "VIEW");
        mvc.perform(get("/api/search").param("q", "shop").session(login("operator").session()))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[?(@.hostName=='beta-host')]").isEmpty());
        mvc.perform(get("/api/search").param("q", "beta").session(login("operator").session()))
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void deploymentOnlyGrantShowsOnlyThatDeploymentAndItsContainers() throws Exception {
        UUID dep = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'shop-stack'", UUID.class);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'VIEW' "
                + "FROM users WHERE username = 'operator'", dep);
        mvc.perform(get("/api/search").param("q", "shop").session(login("operator").session()))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[?(@.type=='DEPLOYMENT')].name").value("shop-stack"))
                .andExpect(jsonPath("$.results[?(@.type=='CONTAINER')].name").value("shop-web-1"));
        // the host itself is visible, its name matches
        mvc.perform(get("/api/search").param("q", "alpha").session(login("operator").session()))
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    void unreachableHostIsSkippedWithoutFailingTheRequest() throws Exception {
        var boom = new DockerConnectionException(DockerErrorCode.UNREACHABLE, "secret detail", null);
        when(containers.listDeployments(eq(beta), any())).thenThrow(boom);
        when(containers.listContainers(eq(beta), any())).thenThrow(boom);
        mvc.perform(get("/api/search").param("q", "shop").session(login("admin").session()))
                .andExpect(status().isOk())
                // alpha: managed shop-stack + shopwild + container (3); beta: only the DB-managed deployment
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[?(@.name=='shop-beta-1')]").isEmpty())
                .andExpect(jsonPath("$.results[?(@.name=='shop-beta')].managed").value(true));
    }
}
