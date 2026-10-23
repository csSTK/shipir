package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stubbed Docker port: failing and hanging environments must not affect the others or the response time. */
@TestPropertySource(properties = {
        "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "shipir.host.overview-timeout=1s"})
class HostOverviewResilienceTest extends HostApiTestBase {

    @MockitoBean
    DockerSystemService docker;
    @Autowired
    SecretCipher cipher;

    private void insertTcp(String name, String hostname, byte[] ca, byte[] cert, byte[] key) {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("""
                INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted,
                    tls_key_encrypted, owner_user_id) VALUES (?, 'TCP', ?, 2376, ?, ?, ?, ?)""",
                name, hostname, ca, cert, key, owner);
    }

    private void insertTcp(String name, String hostname) {
        insertTcp(name, hostname, cipher.encrypt("ca"), cipher.encrypt("cert"), cipher.encrypt("key"));
    }

    @Test
    void failingAndHangingHostsAreDownWhileOthersStayUpWithinTimeout() throws Exception {
        insertTcp("a-ok", "ok.example");
        insertTcp("b-boom", "boom.example");
        insertTcp("c-hang", "hang.example");
        insertTcp("d-ok", "ok2.example");
        when(docker.info(any(), any())).thenAnswer(inv -> {
            DockerEndpoint.Tcp endpoint = (DockerEndpoint.Tcp) inv.getArgument(1);
            return switch (endpoint.host()) {
                case "boom.example" -> throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "refused", null);
                case "hang.example" -> {
                    Thread.sleep(60_000);
                    yield null;
                }
                default -> new DockerSystemInfoDto("27.0.1", 4, 8_000_000_000L, 3, 2, 1, 1, 7, 5);
            };
        });
        Login viewer = login("viewer");

        long start = System.nanoTime();
        mvc.perform(get("/api/hosts/overview").session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("a-ok"))
                .andExpect(jsonPath("$[0].status").value("UP"))
                .andExpect(jsonPath("$[0].endpoint").value("tcp://ok.example:2376"))
                .andExpect(jsonPath("$[0].dockerVersion").value("27.0.1"))
                .andExpect(jsonPath("$[0].cpus").value(4))
                .andExpect(jsonPath("$[0].memoryBytes").value(8_000_000_000L))
                .andExpect(jsonPath("$[0].containers.running").value(3))
                .andExpect(jsonPath("$[0].containers.stopped").value(2))
                .andExpect(jsonPath("$[0].containers.healthy").value(1))
                .andExpect(jsonPath("$[0].containers.unhealthy").value(1))
                .andExpect(jsonPath("$[0].images").value(7))
                .andExpect(jsonPath("$[0].volumes").value(5))
                .andExpect(jsonPath("$[1].name").value("b-boom"))
                .andExpect(jsonPath("$[1].status").value("DOWN"))
                .andExpect(jsonPath("$[1].error").value("UNREACHABLE"))
                .andExpect(jsonPath("$[1].dockerVersion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].containers").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[2].name").value("c-hang"))
                .andExpect(jsonPath("$[2].status").value("DOWN"))
                .andExpect(jsonPath("$[2].error").value("TIMEOUT"))
                .andExpect(jsonPath("$[3].name").value("d-ok"))
                .andExpect(jsonPath("$[3].status").value("UP"));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void undecryptableTlsMaterialYieldsDownInsteadOfServerError() throws Exception {
        insertTcp("broken", "broken.example", new byte[]{1, 2, 3}, new byte[]{1, 2, 3}, new byte[]{1, 2, 3});
        insertTcp("fine", "ok.example");
        when(docker.info(any(), any())).thenReturn(new DockerSystemInfoDto("27.0.1", 1, 1L, 0, 0, 0, 0, 0, 0));
        Login viewer = login("viewer");

        mvc.perform(get("/api/hosts/overview").session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("broken"))
                .andExpect(jsonPath("$[0].status").value("DOWN"))
                .andExpect(jsonPath("$[0].error").value("DECRYPTION_FAILED"))
                .andExpect(jsonPath("$[1].name").value("fine"))
                .andExpect(jsonPath("$[1].status").value("UP"));
    }
}
