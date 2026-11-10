package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Container actions against the real local daemon (needs Docker, like every test extending the base). */
class HostContainerActionsRealDockerTest extends HostApiTestBase {

    private final DockerClient docker = DockerClientFactory.lazyClient();

    private String localHostId() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private boolean running(String id) {
        return Boolean.TRUE.equals(docker.inspectContainerCmd(id).exec().getState().getRunning());
    }

    @Test
    void stopThenStartABusyboxContainer() throws Exception {
        String host = localHostId();
        try {
            docker.inspectImageCmd("busybox:1.36").exec();
        } catch (RuntimeException notPulled) {
            docker.pullImageCmd("busybox:1.36").start().awaitCompletion();
        }
        String id = docker.createContainerCmd("busybox:1.36").withCmd("sleep", "300")
                .withName("shipir-act-" + UUID.randomUUID().toString().substring(0, 8)).exec().getId();
        try {
            docker.startContainerCmd(id).exec();
            Login op = login("operator");

            mvc.perform(op.write(post("/api/hosts/{h}/containers/{c}/stop?timeout=1", host, id)))
                    .andExpect(status().isNoContent());
            assertThat(running(id)).isFalse();

            mvc.perform(op.write(post("/api/hosts/{h}/containers/{c}/start", host, id)))
                    .andExpect(status().isNoContent());
            assertThat(running(id)).isTrue();

            mvc.perform(op.write(post("/api/hosts/{h}/containers/{c}/start", host, id)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_CONTAINER_STATE"));
        } finally {
            docker.removeContainerCmd(id).withForce(true).exec();
        }
    }

    @Test
    void unknownContainerIs404() throws Exception {
        String host = localHostId();
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/{c}/kill", host, "doesnotexist1")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
