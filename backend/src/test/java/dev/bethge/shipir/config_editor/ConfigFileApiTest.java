package dev.bethge.shipir.config_editor;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {"shipir.config-editor.backups-per-file=3", "shipir.config-editor.max-file-bytes=200"})
class ConfigFileApiTest {

    private static final String PASSWORD = "A-test-Passw0rd-for-config-files!";
    private static final String YAML = "services:\n  web:\n    image: nginx\n";
    private static final Path BASE_DIR = createBaseDir();

    private static Path createBaseDir() {
        try {
            return Files.createTempDirectory("shipir-config-editor-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void baseDir(DynamicPropertyRegistry registry) {
        registry.add("shipir.stacks.base-dir", BASE_DIR::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ComposeFileRepository files;
    @Autowired ConfigFileService service;
    @MockitoBean ComposeExecutionService execution;

    UUID hostId;
    String deploymentId;
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Config host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        grantHost("operator", "OPERATE");
        grantHost("viewer", "VIEW");
        doReturn(new ComposeProcessResult(0, false, List.of())).when(execution).execute(any(), any(), any(), any(), any());
        doReturn(new ComposeProcessResult(0, false, List.of())).when(execution).execute(any(), any(), any(), any(), any(), any());
        doReturn(new ComposeProcessResult(0, false, List.of())).when(execution)
                .execute(any(), any(), any(), any(), any(), any(), any(), any());
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post("/api/hosts/" + hostId + "/managed-deployments"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"web\",\"yaml\":\"" + YAML.replace("\n", "\\n") + "\",\"env\":[{\"key\":\"A\",\"value\":\"secret-env\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        deploymentId = JsonPath.read(body, "$.id");
        dir = files.deploymentDir(hostId, "web");
    }

    void grantHost(String username, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, ? FROM users WHERE username = ? "
                + "ON CONFLICT (user_id, host_id) DO UPDATE SET level = EXCLUDED.level", hostId, level, username);
    }

    @Test
    void permissionsHiddenDeploymentIs404AndViewGrantCannotWrite() throws Exception {
        String base = "/api/hosts/" + hostId + "/managed-deployments/" + deploymentId + "/files";
        jdbc.update("DELETE FROM permissions");
        Login op = login("operator");
        mvc.perform(get(base).session(op.session())).andExpect(status().isNotFound());
        mvc.perform(op.write(put(base).param("path", "a.txt").contentType(APPLICATION_JSON).content("{\"content\":\"x\"}")))
                .andExpect(status().isNotFound());

        grantHost("operator", "VIEW");
        mvc.perform(get(base).session(op.session())).andExpect(status().isOk());
        mvc.perform(op.write(put(base).param("path", "a.txt").contentType(APPLICATION_JSON).content("{\"content\":\"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(op.write(delete(base).param("path", "a.txt"))).andExpect(status().isForbidden());
        assertThat(Files.exists(dir.resolve("a.txt"))).isFalse();
    }

    @Test
    void filesOfASystemManagedDeploymentAreAdminOnly() throws Exception {
        jdbc.update("UPDATE deployments SET system_managed = TRUE WHERE id = ?::uuid", deploymentId);
        Login admin = login("admin");
        saveFile(admin, "traefik.yml", "a: 1");
        Login op = login("operator");
        mvc.perform(get(url()).session(op.session())).andExpect(status().isForbidden());
        mvc.perform(get(url()).session(op.session()).param("path", "traefik.yml")).andExpect(status().isForbidden());
        mvc.perform(op.write(put(url())).param("path", "traefik.yml").contentType(APPLICATION_JSON).content(body("evil: 1")))
                .andExpect(status().isForbidden());
        mvc.perform(op.write(put(url())).param("path", "new.yml").contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isForbidden());
        mvc.perform(op.write(delete(url())).param("path", "traefik.yml")).andExpect(status().isForbidden());
        mvc.perform(get(url() + "/backups").session(op.session()).param("path", "traefik.yml"))
                .andExpect(status().isForbidden());
        assertThat(Files.readString(dir.resolve("traefik.yml"))).isEqualTo("a: 1");
        assertThat(dir.resolve("new.yml")).doesNotExist();
        mvc.perform(get(url()).session(admin.session())).andExpect(status().isOk());
        mvc.perform(get(url()).session(admin.session()).param("path", "traefik.yml")).andExpect(status().isOk());
    }

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    String url() {
        return "/api/hosts/" + hostId + "/managed-deployments/" + deploymentId + "/files";
    }

    static String body(String content) {
        return "{\"content\":\"" + content.replace("\n", "\\n") + "\"}";
    }

    void saveFile(Login l, String path, String content) throws Exception {
        mvc.perform(l.write(put(url())).param("path", path).contentType(APPLICATION_JSON).content(body(content)))
                .andExpect(status().isOk());
    }

    List<String> backupIds(Login l, String path) throws Exception {
        String json = mvc.perform(get(url() + "/backups").session(l.session()).param("path", path))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[*].id");
    }

    // ---- malicious paths ----

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "a/../../outside.txt", "/etc/passwd", "%2e%2e/outside.txt", "a\\b.txt",
            "a\u0000b.txt", "C:/windows/x"})
    void rejectsMaliciousPaths(String path) throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(url())).param("path", path).contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(get(url()).session(admin.session()).param("path", path))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(admin.write(delete(url())).param("path", path))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSymlinkEscapingTheDeploymentDirectory() throws Exception {
        Path outside = Files.createTempDirectory("shipir-outside-");
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        Files.createSymbolicLink(dir.resolve("link"), outside);
        Login admin = login("admin");
        mvc.perform(get(url()).session(admin.session()).param("path", "link/secret.txt"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(admin.write(put(url())).param("path", "link/new.txt").contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isBadRequest());
        assertThat(outside.resolve("new.txt")).doesNotExist();
    }

    @Test
    void rejectsSymlinkInsideTheDirectoryPointingToComposeFile() throws Exception {
        Files.createSymbolicLink(dir.resolve("alias.yaml"), dir.resolve("compose.yaml"));
        Login admin = login("admin");
        mvc.perform(admin.write(put(url())).param("path", "alias.yaml").contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(get(url()).session(admin.session()).param("path", "alias.yaml"))
                .andExpect(status().isBadRequest());
        assertThat(Files.readString(dir.resolve("compose.yaml"))).isEqualTo(YAML);
    }

    // ---- excluded / read-only ----

    @ParameterizedTest
    @ValueSource(strings = {"compose.yaml", "./compose.yaml", ".env", "COMPOSE.YAML", ".shipir-backups/x", "repo/.git/config"})
    void excludedPathsAreRefused(String path) throws Exception {
        Login admin = login("admin");
        mvc.perform(get(url()).session(admin.session()).param("path", path))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCLUDED_PATH"));
        mvc.perform(admin.write(put(url())).param("path", path).contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXCLUDED_PATH"));
        mvc.perform(admin.write(delete(url())).param("path", path))
                .andExpect(status().isBadRequest());
        assertThat(Files.readString(dir.resolve("compose.yaml"))).isEqualTo(YAML);
        assertThat(Files.readString(dir.resolve(".env"))).contains("secret-env");
    }

    @Test
    void nestedDotEnvIsEditable() throws Exception {
        Login admin = login("admin");
        saveFile(admin, "app/.env", "X=1\n");
        mvc.perform(get(url()).session(admin.session()).param("path", "app/.env"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("X=1\n"));
    }

    @Test
    void gitCheckoutIsReadableButNotWritable() throws Exception {
        Files.createDirectories(dir.resolve("repo/conf"));
        Files.writeString(dir.resolve("repo/conf/app.conf"), "a=1\n");
        Login admin = login("admin");
        mvc.perform(get(url()).session(admin.session()).param("path", "repo/conf/app.conf"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
        mvc.perform(admin.write(put(url())).param("path", "repo/conf/app.conf").contentType(APPLICATION_JSON).content(body("b=2\n")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("READ_ONLY_PATH"));
        mvc.perform(admin.write(delete(url())).param("path", "repo/conf/app.conf"))
                .andExpect(status().isForbidden());
        mvc.perform(admin.write(post(url() + "/restore")).param("path", "repo/conf/app.conf")
                        .contentType(APPLICATION_JSON).content("{\"backupId\":\"20260101000000000-abcdef12\"}"))
                .andExpect(status().isForbidden());
        assertThat(Files.readString(dir.resolve("repo/conf/app.conf"))).isEqualTo("a=1\n");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @ValueSource(strings = {"operator", "admin"})
    void readWithoutSecretsRefusesEnvLikeFilesEvenWhereReadSucceeds(String username) throws Exception {
        Files.writeString(dir.resolve("db.env"), "PASSWORD=hunter2\n");
        Files.writeString(dir.resolve("app.conf"), "listen 80;\n");
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContextHolder.setContext(
                new org.springframework.security.core.context.SecurityContextImpl(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(username, null,
                                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + username.toUpperCase())))));
        try {
            UUID id = UUID.fromString(deploymentId);
            assertThat(service.read(hostId, id, "db.env").content()).contains("hunter2");
            assertThatThrownBy(() -> service.readWithoutSecrets(hostId, id, "db.env"))
                    .isInstanceOfSatisfying(ConfigFileException.class, e -> assertThat(e.code()).isEqualTo("ENV_FILE_RESTRICTED"));
            assertThat(service.readWithoutSecrets(hostId, id, "app.conf").content()).isEqualTo("listen 80;\n");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    // ---- env-like files are not readable by view-only users ----

    @ParameterizedTest
    @ValueSource(strings = {"repo/.env", "db.env", "app/.env", ".env.production", "app/.ENV", "Config/DB.ENV", "secrets/db.conf",
            "app/my-Secret.txt", "tls/server.pem", "tls/SERVER.KEY", "repo/deep/.env.local",
            "tls/store.p12", "tls/STORE.PFX", ".ssh/id_rsa", "app/ID_RSA", "aws/credentials", "app/Credentials.json"})
    void viewerCannotReadEnvLikeFilesButOperatorAndAdminCan(String path) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "TOKEN=hunter2\n");
        Login viewer = login("viewer");
        mvc.perform(get(url()).session(viewer.session()).param("path", path))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENV_FILE_RESTRICTED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
        mvc.perform(get(url()).session(login("operator").session()).param("path", path))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("TOKEN=hunter2\n"));
        mvc.perform(get(url()).session(login("admin").session()).param("path", path)).andExpect(status().isOk());
    }

    @Test
    void viewerStillSeesTreeAndOrdinaryFilesButNotEnvContent() throws Exception {
        Files.createDirectories(dir.resolve("repo"));
        Files.writeString(dir.resolve("repo/.env"), "TOKEN=hunter2\n");
        Files.writeString(dir.resolve("repo/app.conf"), "a=1\n");
        Login viewer = login("viewer");
        String json = mvc.perform(get(url()).session(viewer.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(json, "$[*].path")).contains("repo/.env", "repo/app.conf");
        assertThat(json).doesNotContain("hunter2");
        mvc.perform(get(url()).session(viewer.session()).param("path", "repo/app.conf")).andExpect(status().isOk());
    }

    @Test
    void viewOnlyGrantOnAnOperatorIsAlsoDeniedEnvFiles() throws Exception {
        Files.createDirectories(dir.resolve("repo"));
        Files.writeString(dir.resolve("repo/.env"), "TOKEN=hunter2\n");
        grantHost("operator", "VIEW");
        mvc.perform(get(url()).session(login("operator").session()).param("path", "repo/.env"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENV_FILE_RESTRICTED"));
    }

    @Test
    void symlinkToEnvFileIsRefusedForViewer() throws Exception {
        Files.createDirectories(dir.resolve("repo"));
        Files.writeString(dir.resolve("repo/.env"), "TOKEN=hunter2\n");
        Files.createSymbolicLink(dir.resolve("innocent.txt"), dir.resolve("repo/.env"));
        mvc.perform(get(url()).session(login("viewer").session()).param("path", "innocent.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
    }

    // ---- tree / read / write ----

    @Test
    void treeListsConfigFilesButHidesManagedOnes() throws Exception {
        Login viewer = login("viewer");
        Login admin = login("admin");
        saveFile(admin, "nginx/site.conf", "server {}\n");
        saveFile(admin, "nginx/site.conf", "server { listen 80; }\n");
        Files.createDirectories(dir.resolve("repo/.git"));
        Files.writeString(dir.resolve("repo/.git/HEAD"), "ref");
        Files.writeString(dir.resolve("repo/README"), "r");
        String json = mvc.perform(get(url()).session(viewer.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> paths = JsonPath.read(json, "$[*].path");
        assertThat(paths).containsExactly("nginx", "nginx/site.conf", "repo", "repo/README");
        assertThat((List<Boolean>) JsonPath.read(json, "$[?(@.path=='repo/README')].readOnly")).containsExactly(true);
        assertThat((List<Boolean>) JsonPath.read(json, "$[?(@.path=='nginx/site.conf')].readOnly")).containsExactly(false);
    }

    @Test
    void writeCreatesFileWith0600AndReadReturnsIt() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(url())).param("path", "conf/app.ini").contentType(APPLICATION_JSON).content(body("k=v\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("conf/app.ini"))
                .andExpect(jsonPath("$.content").value("k=v\n"));
        assertThat(java.nio.file.Files.getPosixFilePermissions(dir.resolve("conf/app.ini")))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        mvc.perform(get(url()).session(login("viewer").session()).param("path", "conf/app.ini"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("k=v\n"));
        assertThat(backupIds(admin, "conf/app.ini")).isEmpty();
    }

    @Test
    void readUnknownFileIs404AndUnknownDeploymentIs404() throws Exception {
        Login admin = login("admin");
        mvc.perform(get(url()).session(admin.session()).param("path", "nope.txt"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        mvc.perform(get("/api/hosts/" + hostId + "/managed-deployments/" + UUID.randomUUID() + "/files").session(admin.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEPLOYMENT_NOT_FOUND"));
    }

    @Test
    void rejectsBinaryAndOversizedContent() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(url())).param("path", "a.txt").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"a\\u0000b\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_TEXT"));
        mvc.perform(admin.write(put(url())).param("path", "big.txt").contentType(APPLICATION_JSON).content(body("x".repeat(201))))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        Files.write(dir.resolve("blob.bin"), new byte[]{(byte) 0xff, (byte) 0xfe, 0x00});
        mvc.perform(get(url()).session(admin.session()).param("path", "blob.bin"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_TEXT"));
        Files.write(dir.resolve("huge.txt"), "y".repeat(500).getBytes());
        mvc.perform(get(url()).session(admin.session()).param("path", "huge.txt"))
                .andExpect(status().isContentTooLarge());
    }

    // ---- backups ----

    @Test
    void writeBacksUpPreviousContentFirstAndKeepsOnlyLastN() throws Exception {
        Login admin = login("admin");
        for (int i = 1; i <= 6; i++) {
            saveFile(admin, "app.conf", "v" + i + "\n");
        }
        List<String> ids = backupIds(admin, "app.conf");
        assertThat(ids).hasSize(3); // limit shipir.config-editor.backups-per-file=3, newest first
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // newest backup holds the content before the last write (v5), restoring it brings v5 back
        mvc.perform(admin.write(post(url() + "/restore")).param("path", "app.conf")
                        .contentType(APPLICATION_JSON).content("{\"backupId\":\"" + ids.get(0) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("v5\n"));
        assertThat(Files.readString(dir.resolve("app.conf"))).isEqualTo("v5\n");
        // the restore itself backed up v6
        assertThat(backupIds(admin, "app.conf")).hasSize(3);
    }

    @Test
    void deleteBacksUpAndRestoreBringsFileBack() throws Exception {
        Login admin = login("admin");
        saveFile(admin, "gone.txt", "precious\n");
        mvc.perform(admin.write(delete(url())).param("path", "gone.txt")).andExpect(status().isNoContent());
        assertThat(dir.resolve("gone.txt")).doesNotExist();
        List<String> ids = backupIds(admin, "gone.txt");
        assertThat(ids).hasSize(1);
        mvc.perform(admin.write(post(url() + "/restore")).param("path", "gone.txt")
                        .contentType(APPLICATION_JSON).content("{\"backupId\":\"" + ids.get(0) + "\"}"))
                .andExpect(status().isOk());
        assertThat(Files.readString(dir.resolve("gone.txt"))).isEqualTo("precious\n");
    }

    @Test
    void restoreRejectsBadAndUnknownBackupIds() throws Exception {
        Login admin = login("admin");
        saveFile(admin, "x.txt", "1");
        for (String bad : List.of("../../compose", "x", "20260101000000000-ABCDEF12")) {
            mvc.perform(admin.write(post(url() + "/restore")).param("path", "x.txt")
                            .contentType(APPLICATION_JSON).content("{\"backupId\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BACKUP_ID"));
        }
        mvc.perform(admin.write(post(url() + "/restore")).param("path", "x.txt")
                        .contentType(APPLICATION_JSON).content("{\"backupId\":\"20260101000000000-abcdef12\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BACKUP_NOT_FOUND"));
    }

    // ---- roles, csrf, audit ----

    @Test
    void viewerReadsButCannotWrite_operatorCanWrite() throws Exception {
        saveFile(login("admin"), "r.txt", "1");
        Login viewer = login("viewer");
        mvc.perform(get(url()).session(viewer.session())).andExpect(status().isOk());
        mvc.perform(get(url()).session(viewer.session()).param("path", "r.txt")).andExpect(status().isOk());
        mvc.perform(get(url() + "/backups").session(viewer.session()).param("path", "r.txt")).andExpect(status().isOk());
        mvc.perform(viewer.write(put(url())).param("path", "r.txt").contentType(APPLICATION_JSON).content(body("2")))
                .andExpect(status().isForbidden());
        mvc.perform(viewer.write(delete(url())).param("path", "r.txt")).andExpect(status().isForbidden());
        mvc.perform(viewer.write(post(url() + "/restore")).param("path", "r.txt")
                .contentType(APPLICATION_JSON).content("{\"backupId\":\"20260101000000000-abcdef12\"}"))
                .andExpect(status().isForbidden());
        saveFile(login("operator"), "r.txt", "3");
        assertThat(Files.readString(dir.resolve("r.txt"))).isEqualTo("3");
    }

    @Test
    void anonymousIs401AndMissingCsrfIs403() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        Login admin = login("admin");
        mvc.perform(put(url()).session(admin.session()).param("path", "c.txt").contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isForbidden());
        assertThat(dir.resolve("c.txt")).doesNotExist();
    }

    @Test
    void writesDeletesAndRestoresAreAuditedWithPathButNeverContent() throws Exception {
        Login operator = login("operator");
        saveFile(operator, "audit/a.conf", "TOPSECRETCONTENT-1");
        saveFile(operator, "audit/a.conf", "TOPSECRETCONTENT-2");
        String backup = backupIds(operator, "audit/a.conf").get(0);
        mvc.perform(operator.write(post(url() + "/restore")).param("path", "audit/a.conf")
                .contentType(APPLICATION_JSON).content("{\"backupId\":\"" + backup + "\"}")).andExpect(status().isOk());
        mvc.perform(operator.write(delete(url())).param("path", "audit/a.conf")).andExpect(status().isNoContent());
        mvc.perform(operator.write(put(url())).param("path", "compose.yaml").contentType(APPLICATION_JSON).content(body("x")))
                .andExpect(status().isBadRequest());

        List<String> rows = jdbc.queryForList(
                "SELECT action || '|' || outcome || '|' || username || '|' || target_type || '|' || target_id FROM audit_log "
                        + "WHERE action LIKE 'CONFIG_FILE_%' ORDER BY occurred_at", String.class);
        String target = deploymentId + ":audit/a.conf";
        assertThat(rows).containsExactly(
                "CONFIG_FILE_WRITE|SUCCESS|operator|CONFIG_FILE|" + target,
                "CONFIG_FILE_WRITE|SUCCESS|operator|CONFIG_FILE|" + target,
                "CONFIG_FILE_RESTORE|SUCCESS|operator|CONFIG_FILE|" + target,
                "CONFIG_FILE_DELETE|SUCCESS|operator|CONFIG_FILE|" + target,
                "CONFIG_FILE_WRITE|FAILURE|operator|CONFIG_FILE|" + deploymentId + ":compose.yaml");
        String all = jdbc.queryForObject("SELECT string_agg(row_to_json(a)::text, '') FROM audit_log a", String.class);
        assertThat(all).doesNotContain("TOPSECRETCONTENT");
    }
}
