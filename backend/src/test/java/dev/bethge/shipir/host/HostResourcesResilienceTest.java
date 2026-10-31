package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stubbed Docker ports: every failure maps to a stable code and never leaks details. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class HostResourcesResilienceTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ImageService images;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    VolumeService volumes;
    @Autowired
    SecretCipher cipher;

    private String insertTcp(String name, byte[] ca, byte[] cert, byte[] key) {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("""
                INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted,
                    tls_key_encrypted, owner_user_id) VALUES (?, 'TCP', '203.0.113.7', 2376, ?, ?, ?, ?)""",
                name, ca, cert, key, owner);
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = ?", UUID.class, name).toString();
    }

    private String insertTcp(String name) {
        return insertTcp(name, cipher.encrypt("ca"), cipher.encrypt("cert"), cipher.encrypt("key"));
    }

    @ParameterizedTest
    @EnumSource(value = DockerErrorCode.class, names = {"UNREACHABLE", "TIMEOUT", "TLS_ERROR", "PERMISSION_DENIED"})
    void dockerFailureIs502WithErrorCodeAndNoDetails(DockerErrorCode code) throws Exception {
        String id = insertTcp("h");
        var boom = new DockerConnectionException(code, "connect to 203.0.113.7:2376 failed", null);
        when(containers.listContainers(any(), any())).thenThrow(boom);
        when(containers.listDeployments(any(), any())).thenThrow(boom);
        when(images.list(any(), any())).thenThrow(boom);
        when(networks.list(any(), any())).thenThrow(boom);
        when(volumes.list(any(), any())).thenThrow(boom);
        Login viewer = login("viewer");

        for (String r : new String[]{"deployments", "containers", "images", "networks", "volumes"}) {
            mvc.perform(get("/api/hosts/{id}/{r}", id, r).session(viewer.session()))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("UNREACHABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("203.0.113.7"))));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DockerErrorCode.class, names = {"UNREACHABLE", "TIMEOUT", "TLS_ERROR", "PERMISSION_DENIED"})
    void adminKeepsTheDetailedErrorCode(DockerErrorCode code) throws Exception {
        String id = insertTcp("h");
        when(containers.listContainers(any(), any()))
                .thenThrow(new DockerConnectionException(code, "connect failed", null));
        Login admin = login("admin");

        mvc.perform(get("/api/hosts/{id}/containers", id).session(admin.session()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    void undecryptableTlsMaterialIs502DecryptionFailed() throws Exception {
        String id = insertTcp("broken", new byte[]{1, 2, 3}, new byte[]{1, 2, 3}, new byte[]{1, 2, 3});
        mvc.perform(get("/api/hosts/{id}/containers", id).session(login("viewer").session()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DECRYPTION_FAILED"));
    }

    @Test
    void stubbedListsAreSerialisedAsOwnDtos() throws Exception {
        String id = insertTcp("ok");
        when(containers.listDeployments(any(), any())).thenReturn(
                List.of(new DeploymentSummaryDto("web", 2, 1, DeploymentSummaryDto.Status.PARTIAL, "/srv/web")));
        when(containers.listContainers(any(), any())).thenReturn(List.<ContainerDto>of());
        Login viewer = login("operator");

        mvc.perform(get("/api/hosts/{id}/deployments", id).session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("web"))
                .andExpect(jsonPath("$[0].status").value("PARTIAL"));
        mvc.perform(get("/api/hosts/{id}/containers", id).session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
