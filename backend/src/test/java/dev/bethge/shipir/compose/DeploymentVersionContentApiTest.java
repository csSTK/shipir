package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentVersionContentApiTest extends DeploymentTestBase {

    private static final String ENV_V1 =
            "[{\"key\":\"A\",\"value\":\"same\"},{\"key\":\"B\",\"value\":\"topsecret1\"},{\"key\":\"C\",\"value\":\"gone\"}]";
    private static final String ENV_V2 =
            "[{\"key\":\"A\",\"value\":\"same\"},{\"key\":\"B\",\"value\":\"topsecret2\"},{\"key\":\"D\",\"value\":\"fresh\"}]";

    @MockitoBean
    ComposeOutputStreamService streams;

    private String create(Login l, String name) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson(name, YAML, ENV_V1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String body(Login l, String path) throws Exception {
        return mvc.perform(get(path).session(l.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void flagsChangedAddedAndRemovedKeysWithoutLeakingValues() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web");
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "SELECT current_version_id FROM deployments WHERE id = ?::uuid", UUID.class, id) != null
                && "RUNNING".equals(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid",
                String.class, id)));
        UUID deployed = jdbc.queryForObject("SELECT current_version_id FROM deployments WHERE id = ?::uuid",
                UUID.class, id);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML + "# edit\n", ENV_V2))).andExpect(status().isOk());
        UUID draft = jdbc.queryForObject(
                "SELECT id FROM deployment_versions WHERE deployment_id = ?::uuid AND deployed_at IS NULL ORDER BY created_at DESC LIMIT 1", UUID.class, id);

        String draftBody = body(admin, url() + "/" + id + "/versions/" + draft);
        assertThat(JsonPath.<String>read(draftBody, "$.yaml")).contains("# edit");
        assertThat(JsonPath.<Boolean>read(draftBody, "$.deployed")).isFalse();
        assertThat(JsonPath.<String>read(draftBody, "$.baselineVersionId")).isEqualTo(deployed.toString());
        assertThat(JsonPath.<java.util.List<String>>read(draftBody, "$.env[?(@.changed==false)].key")).containsExactly("A");
        assertThat(JsonPath.<java.util.List<String>>read(draftBody, "$.env[?(@.changed==true)].key"))
                .containsExactlyInAnyOrder("B", "D");
        assertThat(JsonPath.<java.util.List<String>>read(draftBody, "$.removedKeys")).containsExactly("C");
        assertThat(draftBody).doesNotContain("topsecret").doesNotContain("fresh").doesNotContain("same")
                .doesNotContain("gone").doesNotContain("value");

        String deployedBody = body(admin, url() + "/" + id + "/versions/" + deployed);
        assertThat(JsonPath.<Boolean>read(deployedBody, "$.deployed")).isTrue();
        assertThat(JsonPath.<java.util.List<String>>read(deployedBody, "$.env[?(@.changed==true)].key")).isEmpty();
        assertThat(JsonPath.<java.util.List<String>>read(deployedBody, "$.removedKeys")).isEmpty();
        assertThat(deployedBody).doesNotContain("topsecret");
    }

    @Test
    void nothingDeployedYetHasNoBaseline() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web");
        UUID draft = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ?::uuid",
                UUID.class, id);
        String b = body(admin, url() + "/" + id + "/versions/" + draft);
        assertThat(JsonPath.<Object>read(b, "$.baselineVersionId")).isNull();
        assertThat(JsonPath.<java.util.List<Object>>read(b, "$.env[?(@.changed==true)]")).isEmpty();
    }

    @Test
    void versionOfAnotherDeploymentAndUnknownIdAre404() throws Exception {
        Login admin = login("admin");
        String a = create(admin, "web");
        String other = create(admin, "other");
        UUID otherVersion = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ?::uuid",
                UUID.class, other);
        mvc.perform(get(url() + "/" + a + "/versions/" + otherVersion).session(admin.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
        mvc.perform(get(url() + "/" + a + "/versions/" + UUID.randomUUID()).session(admin.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void permissions() throws Exception {
        Login admin = login("admin");
        String granted = create(admin, "granted");
        String hidden = create(admin, "hidden");
        UUID gv = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ?::uuid",
                UUID.class, granted);
        UUID hv = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ?::uuid",
                UUID.class, hidden);
        jdbc.update("DELETE FROM permissions");
        grantDeployment("viewer", UUID.fromString(granted), "VIEW");
        Login viewer = login("viewer");

        mvc.perform(get(url() + "/" + granted + "/versions/" + gv).session(viewer.session()))
                .andExpect(status().isOk());
        mvc.perform(get(url() + "/" + hidden + "/versions/" + hv).session(viewer.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEPLOYMENT_NOT_FOUND"));
        Login op = login("operator");
        mvc.perform(get(url() + "/" + granted + "/versions/" + gv).session(op.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(url() + "/" + granted + "/versions/" + gv)).andExpect(status().isUnauthorized());
    }
}
