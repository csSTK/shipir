package dev.bethge.shipir.host;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.testcontainers.DockerClientFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Create endpoints against the real local daemon. */
class HostCreateResourcesRealDockerTest extends HostApiTestBase {

    private final DockerClient docker = DockerClientFactory.lazyClient();

    private String localHostId() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private String create(Login login, String host, String collection, String body) throws Exception {
        return mvc.perform(login.write(post("/api/hosts/{h}/{c}", host, collection))
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace('\'', '"')))
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * A random /24 in 10.0.0.0/8 (outside Docker's default pools) that no existing network overlaps. A fixed subnet
     * collides with other runs sharing the daemon ("Pool overlaps", surfaced as 502).
     */
    private String freeSubnet() {
        var used = docker.listNetworksCmd().exec().stream()
                .flatMap(n -> n.getIpam() == null || n.getIpam().getConfig() == null
                        ? java.util.stream.Stream.<String>empty()
                        : n.getIpam().getConfig().stream().map(c -> c.getSubnet()))
                .filter(java.util.Objects::nonNull).toList();
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 200; i++) {
            String prefix = "10." + rnd.nextInt(100, 250) + "." + rnd.nextInt(0, 256) + ".";
            if (used.stream().noneMatch(u -> u.startsWith("10.") && (prefix.startsWith(u.substring(0, u.lastIndexOf('.') + 1))
                    || u.startsWith(prefix)))) {
                return prefix + "0/24";
            }
        }
        throw new IllegalStateException("no free subnet found");
    }

    @Test
    void networkAndVolumeCreateThenNameTaken() throws Exception {
        String host = localHostId();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String net = "shipir-net-" + suffix;
        String vol = "shipir-vol-" + suffix;
        String subnet = freeSubnet();
        Login op = login("operator");
        String netId = null;
        try {
            mvc.perform(op.write(post("/api/hosts/{h}/networks", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + net + "\",\"internal\":true,\"subnet\":\"" + subnet + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(net));
            var inspected = docker.listNetworksCmd().withNameFilter(net).exec().get(0);
            netId = inspected.getId();
            assertThat(inspected.getInternal()).isTrue();
            mvc.perform(op.write(post("/api/hosts/{h}/networks", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + net + "\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));

            mvc.perform(op.write(post("/api/hosts/{h}/volumes", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + vol + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(vol));
            mvc.perform(op.write(post("/api/hosts/{h}/volumes", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + vol + "\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        } finally {
            if (netId != null) docker.removeNetworkCmd(netId).exec();
            try {
                docker.removeVolumeCmd(vol).exec();
            } catch (RuntimeException ignored) {
                // never created
            }
        }
    }

    @Test
    void containerCreatedStartedAndDuplicateNameRefused() throws Exception {
        String host = localHostId();
        try {
            docker.inspectImageCmd("busybox:1.36").exec();
        } catch (RuntimeException notPulled) {
            docker.pullImageCmd("busybox:1.36").start().awaitCompletion();
        }
        String name = "shipir-create-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{'name':'" + name + "','image':'busybox:1.36','command':['sleep','300'],"
                + "'env':{'FOO':'bar'},'restartPolicy':'no','start':true}";
        Login op = login("operator");
        String id = null;
        try {
            String response = create(op, host, "containers", body);
            id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asText();
            var inspect = docker.inspectContainerCmd(id).exec();
            assertThat(inspect.getState().getRunning()).isTrue();
            assertThat(inspect.getConfig().getEnv()).contains("FOO=bar");
            assertThat(inspect.getHostConfig().getPrivileged()).isNotEqualTo(true);

            mvc.perform(op.write(post("/api/hosts/{h}/containers", host)).contentType(MediaType.APPLICATION_JSON)
                            .content(body.replace('\'', '"')))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        } finally {
            if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        }
    }

    @Test
    void unknownImageIs404() throws Exception {
        String host = localHostId();
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers", host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"shipir-no-such-image-xyz:1\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void startFailureRemovesTheContainer() throws Exception {
        String host = localHostId();
        try {
            docker.inspectImageCmd("busybox:1.36").exec();
        } catch (RuntimeException notPulled) {
            docker.pullImageCmd("busybox:1.36").start().awaitCompletion();
        }
        String name = "shipir-fail-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers", host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"image\":\"busybox:1.36\","
                                + "\"command\":[\"/does-not-exist\"],\"start\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTAINER_START_FAILED"));
        assertThat(docker.listContainersCmd().withShowAll(true).withNameFilter(java.util.List.of(name)).exec()).isEmpty();
    }
}
