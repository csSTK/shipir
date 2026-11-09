package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerResourcesSpec;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recreate, duplicate and resource update endpoints with stubbed Docker services: validation, roles, compose guard, audit. */
class HostContainerEditTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ImageService images;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    VolumeService volumes;

    private String host;

    @BeforeEach
    void setUpHost() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
        when(containers.inspect(any(), any(), eq("web1"))).thenReturn(details(null));
        when(containers.inspect(any(), any(), eq("composed"))).thenReturn(details("shop"));
    }

    private static ContainerDetailsDto details(String project) {
        return new ContainerDetailsDto("id1", "web", "nginx:1", "running", null, null, 0,
                new ContainerDetailsDto.RestartPolicy("no", 0), List.of("nginx"), List.of(), false, false, List.of(), List.of(), Map.of(),
                List.of(), List.of(), null, project, project == null ? null : "web");
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') FROM audit_log WHERE action = ?",
                String.class, action);
    }

    private MockHttpServletRequestBuilder recreate(Login l, String cid, String action, String body) {
        return l.write(post("/api/hosts/{h}/containers/{c}/{a}", host, cid, action))
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder resources(Login l, String cid, String body) {
        return l.write(patch("/api/hosts/{h}/containers/{c}/resources", host, cid))
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    @Test
    void operatorRecreatesAndTheOldNameIsKept() throws Exception {
        when(containers.recreateContainer(any(), any(), eq("web1"), any())).thenReturn(new CreatedResourceDto("new1", "web"));
        mvc.perform(recreate(login("operator"), "web1", "recreate",
                        "{'name':'other','image':'nginx:1.27','env':{'A':'2'},"
                                + "'ports':[{'hostPort':8080,'containerPort':80}],"
                                + "'mounts':[{'type':'VOLUME','source':'data','target':'/data'}],'restartPolicy':'always'}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new1"))
                .andExpect(jsonPath("$.name").value("web"));
        ArgumentCaptor<ContainerCreateSpec> spec = ArgumentCaptor.forClass(ContainerCreateSpec.class);
        verify(containers).recreateContainer(any(), any(), eq("web1"), spec.capture());
        assertThat(spec.getValue().name()).isEqualTo("web");
        assertThat(spec.getValue().image()).isEqualTo("nginx:1.27");
        assertThat(spec.getValue().env()).containsEntry("A", "2");
        assertThat(audit("CONTAINER_RECREATE")).containsExactly("SUCCESS:web1");
    }

    @Test
    void recreateKeepsEveryNetworkWithItsAliases() throws Exception {
        when(containers.recreateContainer(any(), any(), eq("web1"), any())).thenReturn(new CreatedResourceDto("new1", "web"));
        mvc.perform(recreate(login("operator"), "web1", "recreate",
                        "{'image':'nginx','networks':[{'name':'front','aliases':['www']},{'name':'back'}]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<ContainerCreateSpec> spec = ArgumentCaptor.forClass(ContainerCreateSpec.class);
        verify(containers).recreateContainer(any(), any(), eq("web1"), spec.capture());
        assertThat(spec.getValue().networks()).containsExactly(
                new ContainerCreateSpec.Network("front", List.of("www")), new ContainerCreateSpec.Network("back", List.of()));
    }

    @Test
    void invalidDuplicateOrHostCombinedNetworksAreRejected() throws Exception {
        for (String networks : List.of("[{'name':'a'},{'name':'a'}]", "[{'name':'-x'}]", "[{'name':'a','aliases':['--bad']}]",
                "[{'name':''}]")) {
            mvc.perform(recreate(login("operator"), "web1", "recreate", "{'image':'nginx','networks':" + networks + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"));
        }
        mvc.perform(recreate(login("admin"), "web1", "recreate",
                        "{'image':'nginx','networks':[{'name':'host'},{'name':'a'}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"));
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
    }

    @Test
    void aliasesPerNetworkAreCappedAt100() throws Exception {
        when(containers.recreateContainer(any(), any(), eq("web1"), any())).thenReturn(new CreatedResourceDto("new1", "web"));
        String aliases = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "'a" + i + "'")
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(recreate(login("operator"), "web1", "recreate",
                        "{'image':'nginx','networks':[{'name':'front','aliases':[" + aliases + "]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"))
                .andExpect(jsonPath("$.details[0]").value("networks: at most 100 aliases on front"));
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        mvc.perform(recreate(login("operator"), "web1", "recreate",
                        "{'image':'nginx','networks':[{'name':'front','aliases':[" + aliases.substring(0, aliases.lastIndexOf(",")) + "]}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void recreateValidatesLikeCreateAndListsEveryViolation() throws Exception {
        mvc.perform(recreate(login("operator"), "web1", "recreate",
                        "{'name':'-bad','image':'--evil','env':{'1x':'v'},'ports':[{'containerPort':70000}],"
                                + "'mounts':[{'type':'VOLUME','source':'d','target':'relative'}],'restartPolicy':'sometimes'}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"))
                .andExpect(jsonPath("$.details.length()").value(6));
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        assertThat(audit("CONTAINER_RECREATE")).containsExactly("FAILURE:web1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"recreate", "duplicate"})
    void operatorCannotUseAdminOnlyOptionsButAdminCan(String action) throws Exception {
        when(containers.recreateContainer(any(), any(), any(), any())).thenReturn(new CreatedResourceDto("n", "web"));
        when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("n", "copy"));
        for (String option : List.of("'privileged':true", "'hostNetwork':true", "'networks':[{'name':'host'}]",
                "'mounts':[{'type':'BIND','source':'/etc','target':'/host'}]")) {
            String body = "{'image':'busybox'," + option + "}";
            mvc.perform(recreate(login("operator"), "web1", action, body))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
            mvc.perform(recreate(login("admin"), "web1", action, body)).andExpect(status().is2xxSuccessful());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a/../b", "/a//b", "/a/./b", "/a\\\\b", "/a%2fb", "rel"})
    void maliciousBindPathsAreRejectedEvenForAdmin(String path) throws Exception {
        String body = "{'image':'busybox','mounts':[{'type':'BIND','source':'" + path + "','target':'/t'}]}";
        for (String action : List.of("recreate", "duplicate")) {
            mvc.perform(recreate(login("admin"), "web1", action, body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONTAINER_SPEC"));
        }
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        verify(containers, never()).createContainer(any(), any(), any());
    }

    @Test
    void composeManagedContainerIsRefusedForAllThreeEdits() throws Exception {
        Login op = login("operator");
        mvc.perform(recreate(op, "composed", "recreate", "{'image':'busybox'}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANAGED_BY_COMPOSE"));
        mvc.perform(recreate(op, "composed", "duplicate", "{'image':'busybox','name':'copy'}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANAGED_BY_COMPOSE"));
        mvc.perform(resources(op, "composed", "{'memoryBytes':67108864}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANAGED_BY_COMPOSE"));
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        verify(containers, never()).createContainer(any(), any(), any());
        verify(containers, never()).updateResources(any(), any(), any(), any());
    }

    @Test
    void recreateFailureIs409AndAuditedAsFailure() throws Exception {
        when(containers.recreateContainer(any(), any(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.RECREATE_FAILED, "x", null));
        mvc.perform(recreate(login("operator"), "web1", "recreate", "{'image':'busybox'}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTAINER_RECREATE_FAILED"));
        assertThat(audit("CONTAINER_RECREATE")).containsExactly("FAILURE:web1");
    }

    @Test
    void unknownContainerIs404AndBadIdIs400() throws Exception {
        when(containers.inspect(any(), any(), eq("nope")))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
        mvc.perform(recreate(login("operator"), "nope", "recreate", "{'image':'busybox'}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(recreate(login("operator"), "-rf", "recreate", "{'image':'busybox'}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
    }

    @Test
    void duplicateCreatesANewContainerAndLeavesTheOriginalAlone() throws Exception {
        when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("copy1", "web-copy"));
        mvc.perform(recreate(login("operator"), "web1", "duplicate", "{'name':'web-copy','image':'nginx:1','start':true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("copy1"))
                .andExpect(jsonPath("$.name").value("web-copy"));
        ArgumentCaptor<ContainerCreateSpec> spec = ArgumentCaptor.forClass(ContainerCreateSpec.class);
        verify(containers).createContainer(any(), any(), spec.capture());
        assertThat(spec.getValue().name()).isEqualTo("web-copy");
        assertThat(spec.getValue().start()).isTrue();
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        verify(containers, never()).removeContainer(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(audit("CONTAINER_DUPLICATE")).containsExactly("SUCCESS:web1");
    }

    @Test
    void duplicateNameTakenIs409() throws Exception {
        when(containers.createContainer(any(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NAME_TAKEN, "x", null));
        mvc.perform(recreate(login("operator"), "web1", "duplicate", "{'name':'web','image':'nginx:1'}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        assertThat(audit("CONTAINER_DUPLICATE")).containsExactly("FAILURE:web1");
    }

    @Test
    void operatorUpdatesResourcesWithAuditDetails() throws Exception {
        mvc.perform(resources(login("operator"), "web1", "{'memoryBytes':134217728,'cpus':1.5,'restartPolicy':'unless-stopped'}"))
                .andExpect(status().isNoContent());
        ArgumentCaptor<ContainerResourcesSpec> spec = ArgumentCaptor.forClass(ContainerResourcesSpec.class);
        verify(containers).updateResources(any(), any(), eq("web1"), spec.capture());
        assertThat(spec.getValue()).isEqualTo(new ContainerResourcesSpec(134217728L, 1_500_000_000L, "unless-stopped"));
        assertThat(audit("CONTAINER_UPDATE")).containsExactly("SUCCESS:web1");
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'CONTAINER_UPDATE'", String.class))
                .contains("134217728");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{'memoryBytes':-1}", "{'memoryBytes':1024}", "{'cpus':-0.5}", "{'cpus':5000}",
            "{'restartPolicy':'sometimes'}"})
    void invalidResourcesAre400(String body) throws Exception {
        mvc.perform(resources(login("operator"), "web1", body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCES"));
        verify(containers, never()).updateResources(any(), any(), any(), any());
    }

    @Test
    void daemonRefusingLimitsIs409() throws Exception {
        org.mockito.Mockito.doThrow(new DockerConnectionException(DockerErrorCode.UPDATE_REJECTED, "x", null))
                .when(containers).updateResources(any(), any(), any(), any());
        mvc.perform(resources(login("operator"), "web1", "{'memoryBytes':67108864}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCES_REJECTED"));
        assertThat(audit("CONTAINER_UPDATE")).containsExactly("FAILURE:web1");
    }

    @Test
    void viewerForbiddenCsrfRequiredAndHostGrantEnforced() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(recreate(viewer, "web1", "recreate", "{'image':'busybox'}")).andExpect(status().isForbidden());
        mvc.perform(recreate(viewer, "web1", "duplicate", "{'image':'busybox'}")).andExpect(status().isForbidden());
        mvc.perform(resources(viewer, "web1", "{'memoryBytes':0}")).andExpect(status().isForbidden());
        Login op = login("operator");
        mvc.perform(post("/api/hosts/{h}/containers/web1/recreate", host).session(op.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/hosts/{h}/containers/web1/resources", host).session(op.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        verify(containers, never()).updateResources(any(), any(), any(), any());

        jdbc.update("DELETE FROM permissions");
        mvc.perform(recreate(op, "web1", "recreate", "{'image':'busybox'}")).andExpect(status().isNotFound());
        mvc.perform(resources(op, "web1", "{'memoryBytes':0}")).andExpect(status().isNotFound());
    }
}
