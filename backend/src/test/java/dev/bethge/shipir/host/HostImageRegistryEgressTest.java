package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pull and push of references naming an internal registry are refused with the default egress policy. */
@TestPropertySource(properties = "shipir.egress.allow-private-ranges=false")
class HostImageRegistryEgressTest extends HostApiTestBase {

    @MockitoBean
    ImageService images;
    @MockitoBean
    ComposeOutputStreamService streams;
    @MockitoBean
    RegistryAuthSource registryAuths;

    private String host;

    @BeforeEach
    void setUp() {
        when(registryAuths.auths()).thenReturn(List.of());
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    @Test
    void pullFromInternalRegistryIsRefusedBeforeTheDaemonIsCalled() throws Exception {
        mvc.perform(login("admin").write(post("/api/hosts/{h}/images/pull", host).contentType("application/json")
                        .content("{\"reference\":\"10.0.0.5:5000/x\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTRY_NOT_ALLOWED"));
        verify(images, never()).pull(any(), any(), any(), any(), any());
    }

    @Test
    void pushToLoopbackRegistryIsRefusedBeforeTheDaemonIsCalled() throws Exception {
        mvc.perform(login("operator").write(post("/api/hosts/{h}/images/push", host).contentType("application/json")
                        .content("{\"reference\":\"localhost:5000/app:v1\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTRY_NOT_ALLOWED"));
        verify(images, never()).push(any(), any(), any(), any(), any());
    }
}
