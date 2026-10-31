package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Create endpoints with stubbed Docker services: validation, ADMIN-only options, roles, CSRF, error mapping, audit. */
class HostCreateResourcesTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ImageService images;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    VolumeService volumes;

    @BeforeEach
    void cleanAudit() {
        jdbc.update("DELETE FROM audit_log");
    }

    private String localHostId() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') FROM audit_log WHERE action = ?",
                String.class, action);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            Login login, String host, String collection, String body) {
        return login.write(post("/api/hosts/{h}/{c}", host, collection)).contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    @Test
    void operatorCreatesAndStartsAContainerWithAuditRow() throws Exception {
        String host = localHostId();
        when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("abc123", "web"));
        mvc.perform(create(login("operator"), host, "containers",
                        "{'name':'web','image':'nginx:1.27','command':['nginx','-g','daemon off;'],'env':{'A':'1'},"
                                + "'ports':[{'hostPort':8080,'containerPort':80,'protocol':'tcp'}],"
                                + "'mounts':[{'type':'VOLUME','source':'data','target':'/data','readOnly':true}],"
                                + "'restartPolicy':'unless-stopped','start':true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("abc123"))
                .andExpect(jsonPath("$.name").value("web"));
        ArgumentCaptor<ContainerCreateSpec> spec = ArgumentCaptor.forClass(ContainerCreateSpec.class);
        verify(containers).createContainer(any(), any(), spec.capture());
        assertThat(spec.getValue().start()).isTrue();
        assertThat(spec.getValue().privileged()).isFalse();
        assertThat(spec.getValue().ports()).hasSize(1);
        assertThat(audit("CONTAINER_CREATE")).containsExactly("SUCCESS:web");
    }

    @Test
    void invalidContainerListsEveryViolation() throws Exception {
        String host = localHostId();
        mvc.perform(create(login("operator"), host, "containers",
                        "{'name':'-bad','image':'--evil','env':{'1x':'v'},'ports':[{'containerPort':70000}],"
                                + "'mounts':[{'type':'VOLUME','source':'d','target':'relative'}],'restartPolicy':'sometimes'}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"))
                .andExpect(jsonPath("$.details.length()").value(6));
        verifyNoInteractions(containers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"'privileged':true", "'hostNetwork':true", "'networks':[{'name':'host'}]",
            "'mounts':[{'type':'BIND','source':'/etc','target':'/host'}]"})
    void operatorCannotUseAdminOnlyOptionsButAdminCan(String option) throws Exception {
        String host = localHostId();
        when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("id1", null));
        String body = "{'image':'busybox'," + option + "}";
        mvc.perform(create(login("operator"), host, "containers", body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        verify(containers, never()).createContainer(any(), any(), any());
        mvc.perform(create(login("admin"), host, "containers", body)).andExpect(status().isCreated());
        assertThat(audit("CONTAINER_CREATE")).containsExactly("FAILURE:busybox", "SUCCESS:busybox");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/a/../b", "/a//b", "/a/./b", "/a\\b", "/a%2fb", "rel"})
    void maliciousBindPathsAreRejectedEvenForAdmin(String path) throws Exception {
        String host = localHostId();
        String body = "{\"image\":\"busybox\",\"mounts\":[{\"type\":\"BIND\",\"source\":\"" + path.replace("\\", "\\\\")
                + "\",\"target\":\"/t\"}]}";
        if (path.equals("/")) {
            when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("i", null));
            mvc.perform(create(login("admin"), host, "containers", body.replace('"', '\''))).andExpect(status().isCreated());
            return;
        }
        mvc.perform(create(login("admin"), host, "containers", body.replace('"', '\'')))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"));
    }

    @Test
    void errorsFromDockerAreMapped() throws Exception {
        String host = localHostId();
        Login op = login("operator");
        when(containers.createContainer(any(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NAME_TAKEN, "x", null))
                .thenThrow(new DockerConnectionException(DockerErrorCode.START_FAILED, "x", null))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
        String body = "{'image':'busybox','name':'web'}";
        mvc.perform(create(op, host, "containers", body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        mvc.perform(create(op, host, "containers", body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTAINER_START_FAILED"));
        mvc.perform(create(op, host, "containers", body)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(audit("CONTAINER_CREATE")).containsExactly("FAILURE:web", "FAILURE:web", "FAILURE:web");
    }

    @Test
    void networkCreateValidatesAndRestrictsDrivers() throws Exception {
        String host = localHostId();
        when(networks.createNetwork(any(), any(), any())).thenReturn(new CreatedResourceDto("net1", "backend"));
        mvc.perform(create(login("operator"), host, "networks",
                        "{'name':'backend','internal':true,'subnet':'172.30.0.0/16'}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("net1"));
        mvc.perform(create(login("operator"), host, "networks", "{'name':'x','driver':'macvlan'}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(create(login("admin"), host, "networks", "{'name':'x','driver':'macvlan'}"))
                .andExpect(status().isCreated());
        mvc.perform(create(login("operator"), host, "networks", "{'name':'','driver':'nope','subnet':'300.1.1.1/8'}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_NETWORK_SPEC"))
                .andExpect(jsonPath("$.details.length()").value(3));
        when(networks.createNetwork(any(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NAME_TAKEN, "x", null));
        mvc.perform(create(login("operator"), host, "networks", "{'name':'backend'}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        assertThat(audit("NETWORK_CREATE")).contains("SUCCESS:backend");
    }

    @Test
    void volumeCreateValidatesAndMapsNameTaken() throws Exception {
        String host = localHostId();
        when(volumes.createVolume(any(), any(), any())).thenReturn(new CreatedResourceDto("data", "data"));
        mvc.perform(create(login("operator"), host, "volumes", "{'name':'data'}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("data"));
        mvc.perform(create(login("operator"), host, "volumes", "{'name':'../x','driver':'nfs'}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VOLUME_SPEC"))
                .andExpect(jsonPath("$.details.length()").value(2));
        when(volumes.createVolume(any(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NAME_TAKEN, "x", null));
        mvc.perform(create(login("admin"), host, "volumes", "{'name':'data'}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        assertThat(audit("VOLUME_CREATE")).containsExactly("SUCCESS:data", "FAILURE:../x", "FAILURE:data");
    }

    @ParameterizedTest
    @ValueSource(strings = {"containers", "networks", "volumes"})
    void viewerForbiddenAndCsrfRequired(String collection) throws Exception {
        String host = localHostId();
        mvc.perform(create(login("viewer"), host, collection, "{'name':'a','image':'busybox'}"))
                .andExpect(status().isForbidden());
        Login op = login("operator");
        mvc.perform(post("/api/hosts/{h}/{c}", host, collection).session(op.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(containers, networks, volumes);
    }

    @Test
    void operatorWithoutHostGrantGets404() throws Exception {
        String host = localHostId();
        Login op = login("operator");
        jdbc.update("DELETE FROM permissions");
        mvc.perform(create(op, host, "volumes", "{'name':'data'}")).andExpect(status().isNotFound());
    }
}
