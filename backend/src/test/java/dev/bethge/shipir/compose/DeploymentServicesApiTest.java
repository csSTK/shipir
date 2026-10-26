package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Profiles and per-service actions of a managed deployment, with stubbed compose execution. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentServicesApiTest extends DeploymentTestBase {

    static final String PROFILE_YAML = "services:\n  web:\n    image: nginx\n  debug:\n    image: busybox\n    profiles: [debug]\n";

    @MockitoBean
    ComposeOutputStreamService streams;

    private final List<String> streamed = new CopyOnWriteArrayList<>();

    private String create(Login l) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) streamed::add);
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", PROFILE_YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String deployed(Login l) throws Exception {
        String id = create(l);
        mvc.perform(l.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        awaitResults(1);
        commands.clear();
        options.clear();
        return id;
    }

    private void awaitResults(int n) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'", Integer.class))
                        .isEqualTo(n));
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT action || ':' || outcome FROM audit_log ORDER BY occurred_at, id", String.class);
    }

    private int versionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM deployment_versions", Integer.class);
    }

    private void setProfiles(Login l, String id, String json) throws Exception {
        mvc.perform(l.write(put(url() + "/" + id + "/profiles")).contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    // --- per-service actions

    @Test
    void restartRunsOnlyThatServiceWithoutNewVersionAndAudits() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        int versions = versionCount();

        String body = mvc.perform(op.write(post(url() + "/" + id + "/services/web/restart")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").exists())
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");
        awaitResults(2);

        assertThat(commands).containsExactly(ComposeCommand.RESTART);
        assertThat(options.get(0).services()).containsExactly("web");
        assertThat(versionCount()).isEqualTo(versions);
        verify(streams, timeout(5000)).complete(runId, RunStatus.DONE);
        assertThat(audit()).contains("DEPLOYMENT_SERVICE_RESTART:SUCCESS", "DEPLOYMENT_SERVICE_RESTART_RESULT:SUCCESS");
        assertThat(jdbc.queryForObject("SELECT details->>'service' FROM audit_log WHERE action = 'DEPLOYMENT_SERVICE_RESTART_RESULT'",
                String.class)).isEqualTo("web");
    }

    @Test
    void pullAndUpRunTheirOwnCommandForOneService() throws Exception {
        Login op = login("operator");
        String id = deployed(op);

        mvc.perform(op.write(post(url() + "/" + id + "/services/web/pull"))).andExpect(status().isAccepted());
        awaitResults(2);
        mvc.perform(op.write(post(url() + "/" + id + "/services/web/up"))).andExpect(status().isAccepted());
        awaitResults(3);

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP_SERVICE);
        assertThat(options).allSatisfy(o -> assertThat(o.services()).containsExactly("web"));
        assertThat(audit()).contains("DEPLOYMENT_SERVICE_PULL:SUCCESS", "DEPLOYMENT_SERVICE_UP:SUCCESS");
    }

    @Test
    void serviceBehindAProfileIsKnown() throws Exception {
        Login op = login("operator");
        String id = deployed(op);

        mvc.perform(op.write(post(url() + "/" + id + "/services/debug/up"))).andExpect(status().isAccepted());
        awaitResults(2);

        assertThat(options.get(0).services()).containsExactly("debug");
    }

    @Test
    void unknownServiceIs400AndRunsNothing() throws Exception {
        Login op = login("operator");
        String id = deployed(op);

        mvc.perform(op.write(post(url() + "/" + id + "/services/nope/restart")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_SERVICE"));
        assertThat(commands).isEmpty();
        assertThat(audit()).contains("DEPLOYMENT_SERVICE_RESTART:FAILURE");
    }

    @Test
    void malformedServiceNameIs400BeforeAnythingRuns() throws Exception {
        Login op = login("operator");
        String id = deployed(op);

        // URI objects are not encoded again by MockMvc, so the percent escapes reach the server as written
        for (String bad : List.of("--rm", "-v", "a%26b", "a%20b", "%24(id)")) {
            mvc.perform(op.write(post(java.net.URI.create(url() + "/" + id + "/services/" + bad + "/restart"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SERVICE"));
        }
        // semicolon and line feed are already refused by the HTTP firewall, also 400
        for (String bad : List.of("web%0Aup", "a%3Bb")) {
            mvc.perform(op.write(post(java.net.URI.create(url() + "/" + id + "/services/" + bad + "/restart"))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(commands).isEmpty();
    }

    @Test
    void unknownActionIs404() throws Exception {
        Login op = login("operator");
        String id = deployed(op);

        mvc.perform(op.write(post(url() + "/" + id + "/services/web/down"))).andExpect(status().isNotFound());
        assertThat(commands).isEmpty();
    }

    @Test
    void neverDeployedIs400NotDeployed() throws Exception {
        Login op = login("operator");
        String id = create(op);
        commands.clear();

        mvc.perform(op.write(post(url() + "/" + id + "/services/web/restart")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_DEPLOYED"));
        assertThat(commands).isEmpty();
    }

    @Test
    void busyDeploymentIs409ForServiceActionsAndStart() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        mvc.perform(op.write(post(url() + "/" + id + "/services/web/up"))).andExpect(status().isAccepted());

        mvc.perform(op.write(post(url() + "/" + id + "/services/web/restart")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        mvc.perform(op.write(post(url() + "/" + id + "/start")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        gate.countDown();
        awaitResults(2);
    }

    @Test
    void failedServiceActionKeepsDeploymentStatusAndAuditsFailure() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        upFails = true;

        String body = mvc.perform(op.write(post(url() + "/" + id + "/services/web/restart")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");
        awaitResults(2);

        verify(streams, timeout(5000)).complete(runId, RunStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid", String.class, id)).isEqualTo("RUNNING");
        assertThat(audit()).contains("DEPLOYMENT_SERVICE_RESTART_RESULT:FAILURE");
    }

    @Test
    void viewerIs403UnknownDeploymentIs404AndCsrfIsRequired() throws Exception {
        Login admin = login("admin");
        String id = deployed(admin);
        Login viewer = login("viewer");

        mvc.perform(viewer.write(post(url() + "/" + id + "/services/web/restart"))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(post(url() + "/" + id + "/start"))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(put(url() + "/" + id + "/profiles")).contentType(APPLICATION_JSON).content("{\"profiles\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(admin.write(post(url() + "/" + UUID.randomUUID() + "/services/web/restart"))).andExpect(status().isNotFound());
        mvc.perform(post(url() + "/" + id + "/services/web/restart").session(admin.session())).andExpect(status().isForbidden());
        assertThat(commands).isEmpty();
    }

    // --- start

    @Test
    void startBringsTheDeployedVersionUpWithProfilesAndNoNewVersion() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        setProfiles(op, id, "{\"profiles\":[\"debug\"]}");
        int versions = versionCount();

        mvc.perform(op.write(post(url() + "/" + id + "/start")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").exists());
        awaitResults(2);

        assertThat(commands).containsExactly(ComposeCommand.UP);
        assertThat(options.get(0).profiles()).containsExactly("debug");
        assertThat(options.get(0).services()).isEmpty();
        assertThat(versionCount()).isEqualTo(versions);
        assertThat(audit()).contains("DEPLOYMENT_START:SUCCESS", "DEPLOYMENT_START_RESULT:SUCCESS");
    }

    @Test
    void startOfNeverDeployedIs400() throws Exception {
        Login op = login("operator");
        String id = create(op);

        mvc.perform(op.write(post(url() + "/" + id + "/start")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_DEPLOYED"));
    }

    // --- profiles

    @Test
    void profilesAreStoredShownAndAppliedToDeployStopAndServiceActions() throws Exception {
        Login op = login("operator");
        String id = create(op);
        commands.clear();
        options.clear();
        mvc.perform(op.write(put(url() + "/" + id + "/profiles")).contentType(APPLICATION_JSON)
                        .content("{\"profiles\":[\"debug\",\"tools\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profiles[0]").value("debug"))
                .andExpect(jsonPath("$.profiles[1]").value("tools"));
        mvc.perform(get(url() + "/" + id).session(op.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profiles.length()").value(2));
        assertThat(audit()).contains("DEPLOYMENT_PROFILES_UPDATE:SUCCESS");

        mvc.perform(op.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        awaitResults(1);
        mvc.perform(op.write(post(url() + "/" + id + "/services/web/restart"))).andExpect(status().isAccepted());
        awaitResults(2);
        mvc.perform(op.write(post(url() + "/" + id + "/stop"))).andExpect(status().isOk());

        assertThat(commands).containsExactly(ComposeCommand.UP, ComposeCommand.RESTART, ComposeCommand.DOWN);
        assertThat(options).allSatisfy(o -> assertThat(o.profiles()).containsExactly("debug", "tools"));
    }

    @Test
    void deploymentWithoutProfilesGetsNone() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        mvc.perform(get(url() + "/" + id).session(op.session())).andExpect(jsonPath("$.profiles.length()").value(0));

        mvc.perform(op.write(post(url() + "/" + id + "/pull-update"))).andExpect(status().isAccepted());
        awaitResults(2);

        assertThat(options).allSatisfy(o -> assertThat(o).isEqualTo(ComposeOptions.NONE));
    }

    @Test
    void emptyListClearsProfilesAndInvalidNamesAre400() throws Exception {
        Login op = login("operator");
        String id = create(op);
        setProfiles(op, id, "{\"profiles\":[\"debug\"]}");
        setProfiles(op, id, "{\"profiles\":[]}");
        assertThat(jdbc.queryForObject("SELECT cardinality(profiles) FROM deployments WHERE id = ?::uuid", Integer.class, id)).isZero();

        for (String bad : List.of("--privileged", "a;b", "a b", "*", "")) {
            mvc.perform(op.write(put(url() + "/" + id + "/profiles")).contentType(APPLICATION_JSON)
                            .content("{\"profiles\":[\"%s\"]}".formatted(bad)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(op.write(put(url() + "/" + id + "/profiles")).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateProfilesAreStoredOnce() throws Exception {
        Login op = login("operator");
        String id = create(op);

        mvc.perform(op.write(put(url() + "/" + id + "/profiles")).contentType(APPLICATION_JSON)
                        .content("{\"profiles\":[\"debug\",\"debug\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profiles.length()").value(1));
    }

    @Test
    void servicesEndpointListsServicesAndProfilesOfTheFile() throws Exception {
        Login op = login("operator");
        String id = create(op);

        // like /env this is behind the OPERATOR/ADMIN rule of the managed-deployments sub-paths
        mvc.perform(get(url() + "/" + id + "/services").session(login("viewer").session())).andExpect(status().isForbidden());
        mvc.perform(get(url() + "/" + id + "/services").session(op.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0]").value("debug"))
                .andExpect(jsonPath("$.services[1]").value("web"))
                .andExpect(jsonPath("$.profiles[0]").value("debug"));
    }
}
