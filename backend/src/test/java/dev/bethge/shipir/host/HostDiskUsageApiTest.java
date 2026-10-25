package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.DiskUsageDto;
import dev.bethge.shipir.docker.dto.DiskUsageDto.CategoryUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/hosts/{id}/disk-usage} against a stubbed Docker system service: roles, grants, error mapping. */
class HostDiskUsageApiTest extends HostApiTestBase {

    @MockitoBean DockerSystemService system;

    private static final DiskUsageDto USAGE = new DiskUsageDto(
            new CategoryUsage(2, 1092588L, 2000L),
            new CategoryUsage(2, 400L, 300L),
            new CategoryUsage(3, 5096L, 1000L),
            new CategoryUsage(2, 1000L, 700L));

    private UUID host;
    private UUID hidden;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'hidden', 'TCP', 'hidden.local', 2375, id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        hidden = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'hidden'", UUID.class);
        when(system.diskUsage(any(), any())).thenReturn(USAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "operator", "admin"})
    void everyRoleWithAHostWideGrantGetsTheUsage(String user) throws Exception {
        if (!user.equals("admin")) {
            grantHost(user, host, user.equals("viewer") ? "VIEW" : "OPERATE");
        }
        mvc.perform(get("/api/hosts/{h}/disk-usage", host).session(login(user).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.count").value(2))
                .andExpect(jsonPath("$.images.totalSize").value(1092588))
                .andExpect(jsonPath("$.images.reclaimableSize").value(2000))
                .andExpect(jsonPath("$.containers.reclaimableSize").value(300))
                .andExpect(jsonPath("$.volumes.count").value(3))
                .andExpect(jsonPath("$.buildCache.reclaimableSize").value(700));
    }

    @Test
    void userWithoutGrantGets404HostNotFound() throws Exception {
        mvc.perform(get("/api/hosts/{h}/disk-usage", host).session(login("viewer").session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void deploymentOnlyGrantGets403BecauseTheUsageIsHostWide() throws Exception {
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'd', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' "
                + "FROM users WHERE username = 'operator'", dep);
        mvc.perform(get("/api/hosts/{h}/disk-usage", host).session(login("operator").session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("/api/hosts/{h}/disk-usage", UUID.randomUUID()).session(login("admin").session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void hostWithoutAGrantStaysHiddenEvenForOperator() throws Exception {
        grantHost("operator", host, "OPERATE");
        mvc.perform(get("/api/hosts/{h}/disk-usage", hidden).session(login("operator").session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void dockerFailureIs502WithTheDockerCode() throws Exception {
        grantHost("viewer", host, "VIEW");
        when(system.diskUsage(any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null));
        mvc.perform(get("/api/hosts/{h}/disk-usage", host).session(login("viewer").session()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UNREACHABLE"));
    }
}
