package dev.bethge.shipir.host;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DELETE against the real local daemon (needs Docker, like every test extending the base). */
class HostResourceDeleteRealDockerTest extends HostApiTestBase {

    private final DockerClient docker = DockerClientFactory.lazyClient();

    private String localHostId() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    @Test
    void volumeAndNetworkAreRemovedThenAre404() throws Exception {
        String host = localHostId();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String volume = "shipir-del-vol-" + suffix;
        docker.createVolumeCmd().withName(volume).exec();
        String network = docker.createNetworkCmd().withName("shipir-del-net-" + suffix).exec().getId();
        Login admin = login("admin");

        mvc.perform(admin.write(delete("/api/hosts/{h}/volumes/{n}", host, volume))).andExpect(status().isNoContent());
        mvc.perform(admin.write(delete("/api/hosts/{h}/volumes/{n}", host, volume)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(admin.write(delete("/api/hosts/{h}/networks/{n}", host, network))).andExpect(status().isNoContent());
        assertThat(docker.listVolumesCmd().exec().getVolumes())
                .noneMatch(v -> volume.equals(v.getName()));
    }

    @Test
    void builtinNetworkIs409() throws Exception {
        String host = localHostId();
        String bridge = docker.listNetworksCmd().withNameFilter("bridge").exec().stream()
                .filter(n -> "bridge".equals(n.getName())).findFirst().orElseThrow().getId();
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/networks/{n}", host, bridge)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void unknownContainerIs404() throws Exception {
        String host = localHostId();
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/containers/{c}", host, "doesnotexist1")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
