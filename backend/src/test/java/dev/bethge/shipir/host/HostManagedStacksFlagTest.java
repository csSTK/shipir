package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.StackSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The live stack list marks projects that Shipir manages (managed flag + id). */
class HostManagedStacksFlagTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;

    @Test
    void liveStacksCarryManagedFlagAndId() throws Exception {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) VALUES ('h', 'LOCAL', ?)", owner);
        UUID host = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        jdbc.update("DELETE FROM stacks");
        jdbc.update("INSERT INTO stacks (name, host_id, compose_file_path, created_by) VALUES ('mine', ?, 'x', ?)",
                host, owner);
        UUID stackId = jdbc.queryForObject("SELECT id FROM stacks", UUID.class);
        when(containers.listStacks(any(), any())).thenReturn(List.of(
                new StackSummaryDto("mine", 2, 2, StackSummaryDto.Status.RUNNING, "/srv/mine"),
                new StackSummaryDto("foreign", 1, 0, StackSummaryDto.Status.STOPPED, null)));

        mvc.perform(get("/api/hosts/{id}/stacks", host).session(login("viewer").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mine"))
                .andExpect(jsonPath("$[0].managed").value(true))
                .andExpect(jsonPath("$[0].managedStackId").value(stackId.toString()))
                .andExpect(jsonPath("$[0].containerCount").value(2))
                .andExpect(jsonPath("$[1].name").value("foreign"))
                .andExpect(jsonPath("$[1].managed").value(false))
                .andExpect(jsonPath("$[1].managedStackId").doesNotExist());
        jdbc.update("DELETE FROM stacks");
    }
}
