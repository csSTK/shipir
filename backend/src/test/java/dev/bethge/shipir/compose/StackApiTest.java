package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.dto.StackCreateRequest;
import dev.bethge.shipir.compose.dto.StackEnvResponse;
import dev.bethge.shipir.compose.dto.StackUpdateRequest;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class StackApiTest extends StackTestBase {

    private static final String SECRET = "s3cr3t-DB-p@ss'word $HOME";
    private static final String ENV = "[{\"key\":\"DB_PASSWORD\",\"value\":\"s3cr3t-DB-p@ss'word $HOME\"},{\"key\":\"MODE\",\"value\":\"prod\"}]";

    private String create(Login l, String name, String env) throws Exception {
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson(name, YAML, env)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void createReturnsDetailWithMaskedEnvAndWritesFiles(CapturedOutput output) throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(createJson("web", YAML, ENV)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("web"))
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.source").value("FILE"))
                .andExpect(jsonPath("$.yaml").value(YAML))
                .andExpect(jsonPath("$.envKeys[0]").value("DB_PASSWORD"))
                .andExpect(jsonPath("$.envKeys[1]").value("MODE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("s3cr3t"))));
        assertThat(files.readCompose(hostId, "web")).contains(YAML);
        assertThat(files.readEnv(hostId, "web").orElseThrow()).contains("DB_PASSWORD=").contains("MODE=prod");
        assertThat(commands).containsExactly(ComposeCommand.CONFIG);
        assertThat(output.getAll()).doesNotContain("s3cr3t");
    }

    @Test
    void getAndListAreOpenToAllRolesAndNeverShowEnvValues() throws Exception {
        String id = create(login("admin"), "web", ENV);
        for (String role : List.of("admin", "operator", "viewer")) {
            Login l = login(role);
            mvc.perform(get(url()).session(l.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("web"))
                    .andExpect(jsonPath("$[0].yaml").doesNotExist());
            mvc.perform(get(url() + "/" + id).session(l.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yaml").value(YAML))
                    .andExpect(jsonPath("$.envKeys.length()").value(2))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("s3cr3t"))));
        }
    }

    @Test
    void envValuesOnlyForAdminAndOperator() throws Exception {
        String id = create(login("admin"), "web", ENV);
        for (String role : List.of("admin", "operator")) {
            mvc.perform(get(url() + "/" + id + "/env").session(login(role).session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[0].key").value("DB_PASSWORD"))
                    .andExpect(jsonPath("$.entries[0].value").value(SECRET))
                    .andExpect(jsonPath("$.entries[1].value").value("prod"));
        }
        mvc.perform(get(url() + "/" + id + "/env").session(login("viewer").session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void envIsEncryptedAtRestAndYamlStoredInVersion() throws Exception {
        create(login("admin"), "web", ENV);
        byte[] cipher = jdbc.queryForObject("SELECT env_ciphertext FROM stack_versions", byte[].class);
        assertThat(new String(cipher, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("s3cr3t").doesNotContain("DB_PASSWORD");
        assertThat(jdbc.queryForObject("SELECT yaml_content FROM stack_versions", String.class)).isEqualTo(YAML);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks s JOIN stack_versions v ON v.id = s.current_version_id",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void stackWithoutEnvHasNoCiphertext() throws Exception {
        create(login("operator"), "plain", null);
        assertThat(jdbc.queryForObject("SELECT env_ciphertext FROM stack_versions", byte[].class)).isNull();
        assertThat(files.readEnv(hostId, "plain")).isEmpty();
    }

    @Test
    void duplicateNameIs409AndKeepsFirstStack() throws Exception {
        Login admin = login("admin");
        create(admin, "web", null);
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(createJson("web", YAML, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STACK_NAME_TAKEN"));
        assertThat(files.readCompose(hostId, "web")).contains(YAML);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidNamesAre400() throws Exception {
        Login admin = login("admin");
        for (String bad : List.of("Upper", "../evil", "a/b", "-lead", "has space", "a".repeat(64))) {
            mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(createJson(bad, YAML, null)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isZero();
    }

    @Test
    void invalidEnvIs400() throws Exception {
        Login admin = login("admin");
        for (String env : List.of("[{\"key\":\"1BAD\",\"value\":\"x\"}]",
                "[{\"key\":\"A\",\"value\":\"line1\\nline2\"}]",
                "[{\"key\":\"A\",\"value\":\"1\"},{\"key\":\"A\",\"value\":\"2\"}]")) {
            mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON).content(createJson("web", YAML, env)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ENV"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isZero();
    }

    @Test
    void invalidComposeIs400WithDetailsAndLeavesNothingBehind() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", "INVALID: [", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("INVALID mapping")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isZero();
        assertThat(files.readCompose(hostId, "web")).isEmpty();
    }

    @Test
    void updateSavesNewYamlAndEnv() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", ENV);
        String yaml2 = "services:\n  web:\n    image: caddy\n";
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson(yaml2, "[{\"key\":\"NEW\",\"value\":\"v\"}]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yaml").value(yaml2))
                .andExpect(jsonPath("$.envKeys.length()").value(1))
                .andExpect(jsonPath("$.envKeys[0]").value("NEW"));
        assertThat(files.readCompose(hostId, "web")).contains(yaml2);
        assertThat(jdbc.queryForObject("SELECT yaml_content FROM stack_versions", String.class)).isEqualTo(yaml2);
        mvc.perform(get(url() + "/" + id + "/env").session(admin.session()))
                .andExpect(jsonPath("$.entries[0].key").value("NEW"));
    }

    @Test
    void updateWithoutEnvKeepsStoredVariablesAndEmptyListClears() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", ENV);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson(YAML + "# c\n", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envKeys.length()").value(2));
        mvc.perform(get(url() + "/" + id + "/env").session(admin.session()))
                .andExpect(jsonPath("$.entries[0].value").value(SECRET));
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson(YAML, "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envKeys.length()").value(0));
        assertThat(files.readEnv(hostId, "web")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT env_ciphertext FROM stack_versions", byte[].class)).isNull();
    }

    @Test
    void invalidUpdateKeepsPreviousFilesAndVersion() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", ENV);
        String before = files.readEnv(hostId, "web").orElseThrow();
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson("INVALID: [", "[{\"key\":\"X\",\"value\":\"y\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE"));
        assertThat(files.readCompose(hostId, "web")).contains(YAML);
        assertThat(files.readEnv(hostId, "web")).contains(before);
        assertThat(jdbc.queryForObject("SELECT yaml_content FROM stack_versions", String.class)).isEqualTo(YAML);
    }

    @Test
    void unknownIdsAre404() throws Exception {
        Login admin = login("admin");
        String missing = UUID.randomUUID().toString();
        mvc.perform(get(url() + "/" + missing).session(admin.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STACK_NOT_FOUND"));
        mvc.perform(admin.write(put(url() + "/" + missing)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML, null))).andExpect(status().isNotFound());
        mvc.perform(admin.write(delete(url() + "/" + missing))).andExpect(status().isNotFound());
        mvc.perform(get("/api/hosts/" + missing + "/managed-stacks").session(admin.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void stackOfAnotherHostIsNotReachableThroughThisHost() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", null);
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Other', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID other = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'Other'", UUID.class);
        mvc.perform(get("/api/hosts/" + other + "/managed-stacks/" + id).session(admin.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerCannotWriteOperatorCan() throws Exception {
        String id = create(login("admin"), "web", null);
        Login viewer = login("viewer");
        mvc.perform(viewer.write(post(url())).contentType(APPLICATION_JSON).content(createJson("x", YAML, null)))
                .andExpect(status().isForbidden());
        mvc.perform(viewer.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML, null))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(delete(url() + "/" + id))).andExpect(status().isForbidden());

        Login operator = login("operator");
        mvc.perform(operator.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML, null))).andExpect(status().isOk());
        mvc.perform(operator.write(delete(url() + "/" + id))).andExpect(status().isNoContent());
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRunsComposeDownAndRemovesFilesAndRow() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", ENV);
        commands.clear();
        mvc.perform(admin.write(delete(url() + "/" + id))).andExpect(status().isNoContent());
        assertThat(commands).containsExactly(ComposeCommand.DOWN);
        assertThat(files.readCompose(hostId, "web")).isEmpty();
        assertThat(files.stackDir(hostId, "web")).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isZero();
    }

    @Test
    void failedComposeDownIs502AndKeepsStack() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", null);
        downFails = true;
        mvc.perform(admin.write(delete(url() + "/" + id)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("COMPOSE_DOWN_FAILED"));
        assertThat(files.readCompose(hostId, "web")).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isEqualTo(1);
    }

    @Test
    void writesAreAuditedWithoutYamlOrEnv(CapturedOutput output) throws Exception {
        Login admin = login("admin");
        String id = create(admin, "web", ENV);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML, ENV))).andExpect(status().isOk());
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson("INVALID: [", ENV))).andExpect(status().isBadRequest());
        mvc.perform(admin.write(delete(url() + "/" + id))).andExpect(status().isNoContent());

        List<String> rows = jdbc.queryForList(
                "SELECT action || ':' || outcome || ':' || coalesce(target_id, '') FROM audit_log "
                        + "WHERE action LIKE 'STACK_%' ORDER BY occurred_at", String.class);
        assertThat(rows).containsExactly("STACK_CREATE:SUCCESS:" + id, "STACK_UPDATE:SUCCESS:" + id,
                "STACK_UPDATE:FAILURE:" + id, "STACK_DELETE:SUCCESS:" + id);
        String dump = String.join("|", jdbc.queryForList("SELECT cast(a.* as text) FROM audit_log a", String.class));
        assertThat(dump).doesNotContain("s3cr3t").doesNotContain("nginx").doesNotContain("DB_PASSWORD");
        assertThat(output.getAll()).doesNotContain("s3cr3t");
    }

    @Test
    void requestsAndResponsesNeverPrintSecretsInToString() {
        var entry = new EnvEntry("K", "s3cr3t");
        assertThat(entry.toString()).doesNotContain("s3cr3t");
        assertThat(new StackCreateRequest("web", "image: secret-yaml", List.of(entry)).toString())
                .doesNotContain("s3cr3t").doesNotContain("secret-yaml");
        assertThat(new StackUpdateRequest("image: secret-yaml", List.of(entry)).toString())
                .doesNotContain("s3cr3t").doesNotContain("secret-yaml");
        assertThat(new StackEnvResponse(List.of(entry)).toString()).doesNotContain("s3cr3t");
    }

    @Test
    void envFileRoundTripsAwkwardValues() {
        List<EnvEntry> entries = List.of(new EnvEntry("A", ""), new EnvEntry("B", "with space"),
                new EnvEntry("C", "it's \"q\" $x \\ #"), new EnvEntry("D", "plain-1.2/x"));
        assertThat(EnvFile.parse(EnvFile.render(entries))).isEqualTo(entries);
    }
}
