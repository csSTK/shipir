package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adopting a live Compose project: stubbed Docker listing/inspect, fake compose, real Postgres and file repository. */
class AdoptionApiTest extends DeploymentTestBase {

    static final Path ALLOWED = createAllowedDir();

    private static Path createAllowedDir() {
        try {
            return Files.createTempDirectory("shipir-adopt-allowed-").toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void allowList(DynamicPropertyRegistry registry) {
        registry.add("shipir.compose.adopt-allowed-dirs[0]", ALLOWED::toString);
    }

    @MockitoBean
    ContainerService containers;

    private String url(String project) {
        return "/api/hosts/" + hostId + "/deployments/" + project + "/adopt";
    }

    private void project(String name, String configFiles, String workingDir) {
        when(containers.listContainers(any(), any())).thenReturn(List.of(new ContainerDto("c1", "web-1", "nginx", "running",
                "Up", null, Instant.now(), List.of(), name, "web")));
        ContainerDetailsDto details = mock(ContainerDetailsDto.class);
        when(details.labels()).thenReturn(configFiles == null ? Map.of()
                : Map.of(AdoptionService.CONFIG_FILES_LABEL, configFiles, AdoptionService.WORKING_DIR_LABEL, workingDir));
        when(containers.inspect(any(), any(), anyString())).thenReturn(details);
    }

    @BeforeEach
    void noProjects() {
        when(containers.listContainers(any(), any())).thenReturn(List.of());
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT action || ':' || outcome FROM audit_log WHERE action = 'DEPLOYMENT_ADOPT' "
                + "ORDER BY occurred_at", String.class);
    }

    @Test
    void adoptsWithPastedYamlKeepingProjectNameAndTouchingNoContainers() throws Exception {
        project("legacy", null, null);

        mvc.perform(login("operator").write(post(url("legacy")).contentType("application/json")
                        .content("{\"yaml\":\"services:\\n  web:\\n    image: nginx\\n\",\"env\":[{\"key\":\"A\",\"value\":\"1\"}]}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("legacy"))
                .andExpect(jsonPath("$.envKeys[0]").value("A"));

        assertThat(jdbc.queryForObject("SELECT source FROM deployment_versions", String.class)).isEqualTo("ADOPTED");
        assertThat(commands).doesNotContain(ComposeCommand.UP, ComposeCommand.DOWN, ComposeCommand.DOWN_VOLUMES);
        assertThat(audit()).containsExactly("DEPLOYMENT_ADOPT:SUCCESS");
    }

    @Test
    void readsComposeFileAndEnvFromLabelPathInsideAllowList() throws Exception {
        Path dir = Files.createDirectory(ALLOWED.resolve("app1"));
        Files.writeString(dir.resolve("compose.yaml"), YAML);
        Files.writeString(dir.resolve(".env"), "TOKEN=abc\n");
        project("app1", dir.resolve("compose.yaml").toString(), dir.toString());

        mvc.perform(login("admin").write(post(url("app1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.yaml").value(YAML))
                .andExpect(jsonPath("$.envKeys[0]").value("TOKEN"));
    }

    @Test
    void invalidYamlIsRejectedAndNothingIsCreated() throws Exception {
        project("bad", null, null);

        mvc.perform(login("admin").write(post(url("bad")).contentType("application/json")
                        .content("{\"yaml\":\"INVALID\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void existingManagedNameIs409() throws Exception {
        project("legacy", null, null);
        var login = login("admin");
        mvc.perform(login.write(post(url("legacy")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isCreated());

        mvc.perform(login.write(post(url("legacy")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_NAME_TAKEN"));
    }

    @Test
    void unknownProjectIs404() throws Exception {
        mvc.perform(login("admin").write(post(url("ghost")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void withoutYamlAndNoRecordedFileThePasteIsRequired() throws Exception {
        project("legacy", null, null);

        mvc.perform(login("admin").write(post(url("legacy"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("YAML_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "/etc/../etc/passwd", "relative/compose.yaml", "compose.yaml",
            "%2e%2e/%2e%2e/etc/passwd"})
    void pathsOutsideTheAllowListAreRefused(String label) throws Exception {
        project("evil", label, "/etc");

        mvc.perform(login("admin").write(post(url("evil"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PATH_NOT_ALLOWED"));
        assertThat(audit()).containsExactly("DEPLOYMENT_ADOPT:FAILURE");
    }

    @Test
    void traversalOutOfAllowedDirIsRefused() throws Exception {
        project("evil", ALLOWED + "/../outside/compose.yaml", ALLOWED.toString());

        mvc.perform(login("admin").write(post(url("evil"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PATH_NOT_ALLOWED"));
    }

    @Test
    void symlinkEscapingAllowedDirIsRefused() throws Exception {
        Path outside = Files.createTempDirectory("shipir-adopt-outside-");
        Files.writeString(outside.resolve("compose.yaml"), YAML);
        Path link = ALLOWED.resolve("link");
        Files.createSymbolicLink(link, outside);
        project("evil", link.resolve("compose.yaml").toString(), link.toString());

        mvc.perform(login("admin").write(post(url("evil"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PATH_NOT_ALLOWED"));
    }

    @Test
    void multipleComposeFilesRequirePaste() throws Exception {
        project("multi", ALLOWED + "/a.yaml," + ALLOWED + "/b.yaml", ALLOWED.toString());

        mvc.perform(login("admin").write(post(url("multi"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("YAML_REQUIRED"));
    }

    @Test
    void viewerIsForbiddenAndMaliciousProjectNameIs400() throws Exception {
        mvc.perform(login("viewer").write(post(url("legacy")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(login("admin").write(post(url("Bad_Name")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEPLOYMENT_NAME"));
    }

    @Test
    void requiresCsrfToken() throws Exception {
        var login = login("admin");
        mvc.perform(post(url("legacy")).session(login.session()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    private int adoptionRows() {
        return jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class);
    }

    private void assertNothingAdopted(int before) {
        assertThat(adoptionRows()).isEqualTo(before);
        assertThat(audit()).doesNotContain("DEPLOYMENT_ADOPT:SUCCESS");
    }

    @Test
    void relativePathsInPastedYamlAreRefusedBeforeAnythingIsWritten() throws Exception {
        project("legacy", null, null);
        String yaml = "services:\\n  web:\\n    image: nginx\\n    volumes:\\n      - ./html:/usr/share/nginx/html\\n"
                + "    env_file: .env\\n";

        mvc.perform(login("admin").write(post(url("legacy")).contentType("application/json")
                        .content("{\"yaml\":\"" + yaml + "\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELATIVE_PATHS_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.details[0]").value("web bind mount: ./html"))
                .andExpect(jsonPath("$.details[1]").value("web env_file: .env"));
        assertNothingAdopted(0);
        assertThat(audit()).containsExactly("DEPLOYMENT_ADOPT:FAILURE");
        assertThat(commands).isEmpty();
    }

    @Test
    void relativePathsInTheHostFileAreRefused() throws Exception {
        Path dir = Files.createDirectory(ALLOWED.resolve("rel1"));
        Files.writeString(dir.resolve("compose.yaml"), "services:\n  web:\n    image: x\n    build: ./app\n");
        project("rel1", dir.resolve("compose.yaml").toString(), dir.toString());

        mvc.perform(login("admin").write(post(url("rel1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELATIVE_PATHS_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.details[0]").value("web build context: ./app"));
        assertNothingAdopted(0);
    }

    @Test
    void absolutePathsAndNamedVolumesStayAdoptable() throws Exception {
        project("abs", null, null);
        String yaml = "services:\\n  web:\\n    image: nginx\\n    volumes:\\n      - /srv/html:/html\\n      - data:/d\\n"
                + "volumes:\\n  data: {}\\n";

        mvc.perform(login("admin").write(post(url("abs")).contentType("application/json")
                        .content("{\"yaml\":\"" + yaml + "\"}")))
                .andExpect(status().isCreated());
    }

    @Test
    void operatorWithoutHostGrantGets404() throws Exception {
        jdbc.update("DELETE FROM permissions");
        project("legacy", null, null);

        mvc.perform(login("operator").write(post(url("legacy")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        assertNothingAdopted(0);
    }

    @Test
    void operatorWithViewOnlyGrantGets403() throws Exception {
        jdbc.update("DELETE FROM permissions");
        grantHost("operator", "VIEW");
        project("legacy", null, null);

        mvc.perform(login("operator").write(post(url("legacy")).contentType("application/json").content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertNothingAdopted(0);
    }

    @Test
    void deploymentOnlyGrantCannotAdopt() throws Exception {
        project("legacy", null, null);
        mvc.perform(login("admin").write(post("/api/hosts/" + hostId + "/managed-deployments")
                .contentType("application/json").content(createJson("other", YAML, null)))).andExpect(status().isCreated());
        UUID other = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'other'", UUID.class);
        jdbc.update("DELETE FROM permissions");
        grantDeployment("operator", other, "OPERATE");

        mvc.perform(login("operator").write(post(url("legacy")).contentType("application/json")
                .content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertNothingAdopted(1);
    }

    @Test
    void listingFailureHidesTheConnectionCodeFromOperatorAndAdminKeepsIt() throws Exception {
        when(containers.listContainers(any(), any()))
                .thenThrow(new dev.bethge.shipir.docker.DockerConnectionException(
                        dev.bethge.shipir.docker.DockerErrorCode.TLS_ERROR, "x", null));
        mvc.perform(login("operator").write(post(url("legacy")).contentType("application/json")
                        .content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UNREACHABLE"));
        mvc.perform(login("admin").write(post(url("legacy")).contentType("application/json")
                        .content("{\"yaml\":\"x: 1\"}")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TLS_ERROR"));
    }
}
