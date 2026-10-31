package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/hosts/{h}/containers/{cid}} against a stubbed ContainerService: env redaction, errors, visibility. */
class HostContainerInspectTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ImageService images;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    VolumeService volumes;

    private UUID host;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    @BeforeEach
    void host() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        when(containers.inspect(any(), any(), eq("web1"))).thenReturn(details("shop"));
    }

    private static ContainerDetailsDto details(String project) {
        return new ContainerDetailsDto("id1", "web", "nginx:1", "running", null, null, 2,
                new ContainerDetailsDto.RestartPolicy("always", 0), List.of("nginx"), List.of(), false, false,
                List.of(new ContainerDetailsDto.EnvVar("SECRET", "hunter2"), new ContainerDetailsDto.EnvVar("A", "b")),
                List.of(), Map.of(), List.of(), List.of(),
                new ContainerDetailsDto.Health("unhealthy", 3, List.of()), project, project == null ? null : "web");
    }

    @Test
    void adminAndOperatorSeeEnvValues() throws Exception {
        grantHost("operator", host, "VIEW");
        for (String user : new String[]{"admin", "operator"}) {
            mvc.perform(get("/api/hosts/{h}/containers/web1", host).session(login(user).session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.env[0].key").value("SECRET"))
                    .andExpect(jsonPath("$.env[0].value").value("hunter2"))
                    .andExpect(jsonPath("$.restartPolicy.name").value("always"))
                    .andExpect(jsonPath("$.health.failingStreak").value(3));
        }
    }

    @Test
    void viewerGetsEnvKeysWithoutValues() throws Exception {
        grantHost("viewer", host, "VIEW");
        mvc.perform(get("/api/hosts/{h}/containers/web1", host).session(login("viewer").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.env.length()").value(2))
                .andExpect(jsonPath("$.env[0].key").value("SECRET"))
                .andExpect(jsonPath("$.env[0].value").doesNotExist())
                .andExpect(jsonPath("$.env[1].value").doesNotExist());
    }

    @Test
    void unknownContainerIs404ResourceNotFound() throws Exception {
        when(containers.inspect(any(), any(), eq("gone"))).thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
        mvc.perform(get("/api/hosts/{h}/containers/gone", host).session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unreachableHostIs502() throws Exception {
        when(containers.inspect(any(), any(), eq("web1"))).thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null));
        mvc.perform(get("/api/hosts/{h}/containers/web1", host).session(login("admin").session()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UNREACHABLE"));
    }

    @Test
    void invalidIdIs400AndNeverReachesDocker() throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/{c}", host, "-rf").session(login("admin").session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
        verify(containers, never()).inspect(any(), any(), any());
    }

    @Test
    void hostWithoutGrantIs404() throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/web1", host).session(login("operator").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void deploymentOnlyGrantSeesOnlyContainersOfVisibleManagedDeployment() throws Exception {
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' "
                + "FROM users WHERE username = 'operator'", dep);
        when(containers.inspect(any(), any(), eq("other1"))).thenReturn(details("unmanaged"));
        when(containers.inspect(any(), any(), eq("plain1"))).thenReturn(details(null));
        var op = login("operator");

        mvc.perform(get("/api/hosts/{h}/containers/web1", host).session(op.session())).andExpect(status().isOk());
        mvc.perform(get("/api/hosts/{h}/containers/other1", host).session(op.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/hosts/{h}/containers/plain1", host).session(op.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void containerOfASystemManagedDeploymentHidesEnvAndImageEnvValuesFromNonAdmins() throws Exception {
        grantHost("operator", host, "OPERATE");
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", host);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin'", host);
        ContainerDetailsDto proxy = new ContainerDetailsDto("id2", "proxy", "traefik:3", "running", null, null, 0,
                new ContainerDetailsDto.RestartPolicy("always", 0), List.of(), List.of(), false, false,
                List.of(new ContainerDetailsDto.EnvVar("CF_DNS_API_TOKEN", "dns-s3cr3t")),
                List.of(new ContainerDetailsDto.EnvVar("IMG_SECRET", "img-s3cr3t")), List.of(), Map.of(), List.of(),
                List.of(), null, "shipir-proxy", "traefik", null, null);
        when(containers.inspect(any(), any(), eq("proxy1"))).thenReturn(proxy);

        mvc.perform(get("/api/hosts/{h}/containers/proxy1", host).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.env[0].key").value("CF_DNS_API_TOKEN"))
                .andExpect(jsonPath("$.env[0].value").doesNotExist())
                .andExpect(jsonPath("$.imageEnv[0].key").value("IMG_SECRET"))
                .andExpect(jsonPath("$.imageEnv[0].value").doesNotExist());
        mvc.perform(get("/api/hosts/{h}/containers/proxy1", host).session(login("admin").session()))
                .andExpect(jsonPath("$.env[0].value").value("dns-s3cr3t"))
                .andExpect(jsonPath("$.imageEnv[0].value").value("img-s3cr3t"));
        // an ordinary deployment's container is unchanged for the operator
        mvc.perform(get("/api/hosts/{h}/containers/web1", host).session(login("operator").session()))
                .andExpect(jsonPath("$.env[0].value").value("hunter2"));
    }
}
