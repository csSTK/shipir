package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A concurrent LOCAL create that passes the service check is stopped by the DB index and answered 409. */
class HostLocalRaceTest extends HostApiTestBase {

    @MockitoBean
    DockerSystemService docker;

    @Test
    void localHostInsertedDuringCreateIsConflictLocalHostExists() throws Exception {
        Login admin = login("admin");
        // the competing request commits between our existence check and our save (the ping runs in between)
        when(docker.ping(any())).thenAnswer(inv -> {
            jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                    + "SELECT 'Winner', 'LOCAL', id FROM users WHERE username = 'admin'");
            return new ConnectionTestResult(true, "1.0", null, null);
        });
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Loser\",\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCAL_HOST_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameNameRaceStillMapsToNameTaken() throws Exception {
        Login admin = login("admin");
        when(docker.ping(any())).thenAnswer(inv -> {
            jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                    + "SELECT 'Same', 'TCP', 'h', 2375, id FROM users WHERE username = 'admin'");
            return new ConnectionTestResult(true, "1.0", null, null);
        });
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Same\",\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOST_NAME_TAKEN"));
    }
}
