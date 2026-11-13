package dev.bethge.shipir.compose;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Git-sourced deployments against a real smart-HTTP git server: JGit's {@link UploadPack} behind the JDK HttpServer, with
 * Basic auth on {@code /private.git}. Plain http is reachable only because of {@code allow-insecure-http}.
 */
@TestPropertySource(properties = {
        "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "shipir.git.allow-insecure-http=true", "shipir.git.connect-timeout=5s"})
class GitDeploymentApiTest extends DeploymentTestBase {

    private static final String TOKEN = "ghp_SECRETTOKEN1234567890abcdef";
    private static final String YAML_A = "services:\n  web:\n    image: nginx\n";
    private static final String YAML_B = "services:\n  web:\n    image: nginx:alpine\n";

    @MockitoBean
    ComposeOutputStreamService streams;

    private HttpServer server;
    private Path work;
    private Git repo;
    private String base;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void startServer() throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        jdbc.update("DELETE FROM git_credentials");
        work = Files.createTempDirectory("shipir-git-origin-");
        repo = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call();
        commit("compose.yaml", YAML_A);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
        rootLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void stopServer() {
        rootLogger.detachAppender(logs);
        server.stop(0);
        repo.close();
    }

    private String commit(String file, String content) throws Exception {
        Path target = work.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        repo.add().addFilepattern(file).call();
        return repo.commit().setMessage("change " + file).setSign(false).setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com").call().getName();
    }

    private final java.util.concurrent.atomic.AtomicInteger gitRequests = new java.util.concurrent.atomic.AtomicInteger();

    private void serve(HttpExchange ex) throws IOException {
        gitRequests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        boolean isPrivate = path.startsWith("/private.git/");
        if (!isPrivate && !path.startsWith("/public.git/")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString(("octocat:" + TOKEN).getBytes(StandardCharsets.UTF_8));
        if (isPrivate && !expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        Repository repository = repo.getRepository();
        UploadPack up = new UploadPack(repository);
        up.setRequestPolicy(UploadPack.RequestPolicy.ANY);
        up.setBiDirectionalPipe(false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String type;
        if (path.endsWith("/info/refs")) {
            PacketLineOut pckOut = new PacketLineOut(out);
            pckOut.writeString("# service=git-upload-pack\n");
            pckOut.end();
            up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(pckOut));
            type = "application/x-git-upload-pack-advertisement";
        } else {
            InputStream in = ex.getRequestBody();
            if ("gzip".equals(ex.getRequestHeaders().getFirst("Content-Encoding"))) {
                in = new GZIPInputStream(in);
            }
            up.upload(in, out, null);
            type = "application/x-git-upload-pack-result";
        }
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, out.size());
        ex.getResponseBody().write(out.toByteArray());
        ex.close();
    }

    private String head() throws Exception {
        return repo.getRepository().resolve("refs/heads/main").name();
    }

    private String credential(String token) throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"c-%s\",\"host\":\"127.0.0.1\",\"username\":\"octocat\",\"token\":\"%s\"}"
                                .formatted(UUID.randomUUID(), token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String gitJson(String name, String url, String ref, String composePath, String credentialId) {
        return "{\"name\":\"%s\",\"source\":\"GIT\",\"repoUrl\":\"%s\",\"ref\":\"%s\",\"composePath\":\"%s\"%s}"
                .formatted(name, url, ref, composePath,
                        credentialId == null ? "" : ",\"credentialId\":\"" + credentialId + "\"");
    }

    private String createGit(Login l, String name, String credentialId) throws Exception {
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson(name, base + "/private.git", "main", "compose.yaml", credentialId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void awaitResults(int count) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'",
                        Integer.class)).isGreaterThanOrEqualTo(count));
    }

    private List<String> versionShas(String id) {
        return jdbc.queryForList("SELECT coalesce(commit_sha, 'none') FROM deployment_versions WHERE deployment_id = ?::uuid "
                + "ORDER BY created_at, id", String.class, id);
    }

    @Test
    void operatorCreatesDeploymentFromPrivateRepoWithExistingCredential() throws Exception {
        String cred = credential(TOKEN);
        Login operator = login("operator");
        String body = mvc.perform(operator.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("gitdeployment", base + "/private.git", "main", "compose.yaml", cred)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("GIT"))
                .andExpect(jsonPath("$.yaml").value(YAML_A))
                .andExpect(jsonPath("$.git.repoUrl").value(base + "/private.git"))
                .andExpect(jsonPath("$.git.ref").value("main"))
                .andExpect(jsonPath("$.git.credentialId").value(cred))
                .andExpect(jsonPath("$.git.lastCommitSha").value(head()))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        assertThat(versionShas(id)).containsExactly(head());
        assertThat(files.readCompose(hostId, "gitdeployment").orElseThrow()).contains(YAML_A);
        Path gitConfig = files.repoDir(hostId, "gitdeployment").resolve(".git/config");
        assertThat(Files.readString(gitConfig)).doesNotContain(TOKEN);
        assertThat(files.repoDir(hostId, "gitdeployment").resolve("compose.yaml")).exists();
    }

    @Test
    void createFromPublicRepoNeedsNoCredential() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("pub", base + "/public.git", "main", "compose.yaml", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.git.credentialId").doesNotExist());
    }

    @Test
    void paddedRefIsStoredAndSyncedTrimmed() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("padded", base + "/public.git", " main ", "compose.yaml", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.git.ref").value("main"))
                .andExpect(jsonPath("$.git.lastCommitSha").value(head()));

        assertThat(jdbc.queryForObject("SELECT git_ref FROM deployments WHERE name = 'padded'", String.class))
                .isEqualTo("main");
        assertThat(files.readCompose(hostId, "padded").orElseThrow()).contains(YAML_A);
    }

    @Test
    void viewerIsForbiddenAndOperatorCannotUseMissingCredential() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(viewer.write(post(url())).contentType(APPLICATION_JSON)
                .content(gitJson("g", base + "/public.git", "main", "compose.yaml", null)))
                .andExpect(status().isForbidden());
        mvc.perform(viewer.write(post(url() + "/" + UUID.randomUUID() + "/git/redeploy")))
                .andExpect(status().isForbidden());
        Login operator = login("operator");
        mvc.perform(operator.write(post(url())).contentType(APPLICATION_JSON)
                .content(gitJson("g", base + "/private.git", "main", "compose.yaml", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
    }

    @Test
    void policyDeniedRedeployRestoresTheCheckoutToThePreviousCommit() throws Exception {
        Login operator = login("operator");
        String body = mvc.perform(operator.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("gitdeployment", base + "/public.git", "main", "compose.yaml", null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        String first = head();
        String currentVersion = jdbc.queryForObject("SELECT current_version_id FROM deployments WHERE id = ?::uuid",
                String.class, id);
        commit("compose.yaml", "services:\n  web:\n    image: nginx\n    privileged: true\n");
        commands.clear();

        mvc.perform(operator.write(post(url() + "/" + id + "/git/redeploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));

        Path repoDir = files.repoDir(hostId, "gitdeployment");
        try (Git checkout = Git.open(repoDir.toFile())) {
            assertThat(checkout.getRepository().resolve("HEAD^{commit}").name()).isEqualTo(first);
        }
        assertThat(Files.readString(repoDir.resolve("compose.yaml"))).isEqualTo(YAML_A);
        assertThat(files.readCompose(hostId, "gitdeployment").orElseThrow()).contains(YAML_A).doesNotContain("privileged");
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM deployments WHERE id = ?::uuid", String.class, id))
                .isEqualTo(currentVersion);
        assertThat(versionShas(id)).containsExactly(first);
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void redeployPicksUpNewCommitAndDeploys() throws Exception {
        String cred = credential(TOKEN);
        Login operator = login("operator");
        String id = createGit(operator, "gitdeployment", cred);
        String first = head();
        String second = commit("compose.yaml", YAML_B);

        mvc.perform(operator.write(post(url() + "/" + id + "/git/redeploy")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").exists());
        awaitResults(1);

        assertThat(versionShas(id)).containsExactly(first, second);
        assertThat(jdbc.queryForObject("SELECT last_commit_sha FROM deployments WHERE id = ?::uuid", String.class, id))
                .isEqualTo(second);
        assertThat(files.readCompose(hostId, "gitdeployment").orElseThrow()).contains(YAML_B);
        assertThat(commands).contains(ComposeCommand.UP);
        assertThat(jdbc.queryForObject("SELECT source FROM deployment_versions WHERE commit_sha = ?", String.class, second))
                .isEqualTo("GIT");
        assertThat(jdbc.queryForList("SELECT action || ':' || outcome FROM audit_log", String.class))
                .contains("DEPLOYMENT_GIT_REDEPLOY:SUCCESS", "DEPLOYMENT_GIT_REDEPLOY_RESULT:SUCCESS");
    }

    private org.springframework.test.web.servlet.ResultActions pull(Login l, String id) throws Exception {
        return mvc.perform(l.write(post(url() + "/" + id + "/git/pull")));
    }

    /** Step 1 (pull for review) then step 2 (confirm) with the reviewed commit and hash, as the UI does for an ADMIN. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reviewedRedeploy(Login l, String id)
            throws Exception {
        String body = pull(l, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return confirm(l, id, body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(Login l, String id, String pullBody) {
        String commit = JsonPath.read(pullBody, "$.commitSha");
        String hash = JsonPath.read(pullBody, "$.graphHash");
        return l.write(post(url() + "/" + id + "/git/redeploy")).contentType(APPLICATION_JSON)
                .content("{\"commitSha\":\"%s\",\"graphHash\":\"%s\"}".formatted(commit, hash));
    }

    @Test
    void pullChecksOutTheNewCommitAndReturnsItsHashWithoutDeploying() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String second = commit("extra/app.conf", "a=1\n");
        commands.clear();

        pull(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.commitSha").value(second))
                .andExpect(jsonPath("$.graphHash").isNotEmpty());

        assertThat(repoCheckout("gitdeployment").resolve("extra/app.conf")).exists();
        assertThat(commands).doesNotContain(ComposeCommand.UP);
        assertThat(versionShas(id)).hasSize(1);
    }

    @Test
    void adminRedeployWithTheReviewedHashDeploysAndStoresThatHash() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String second = commit("extra/app.conf", "a=1\n");
        String pullBody = pull(admin, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mvc.perform(confirm(admin, id, pullBody)).andExpect(status().isAccepted());
        awaitResults(1);

        assertThat(versionShas(id)).hasSize(2).endsWith(second);
        assertThat(jdbc.queryForObject("SELECT graph_hash FROM deployment_versions WHERE commit_sha = ?", String.class, second))
                .isEqualTo(JsonPath.<String>read(pullBody, "$.graphHash"));
    }

    @Test
    void redeployIsRefusedWhenAFileChangedAfterTheReview() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        commit("extra/app.conf", "a=1\n");
        String pullBody = pull(admin, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Files.writeString(repoCheckout("gitdeployment").resolve("extra/app.conf"), "a=EVIL\n");
        commands.clear();

        mvc.perform(confirm(admin, id, pullBody)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILES_CHANGED_SINCE_REVIEW"));

        assertThat(commands).doesNotContain(ComposeCommand.UP);
        assertThat(versionShas(id)).hasSize(1);
    }

    @Test
    void redeployDeploysTheReviewedCommitNotAnewerOneOnTheRemote() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String second = commit("compose.yaml", YAML_B);
        String pullBody = pull(admin, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        commit("compose.yaml", YAML_A + "    privileged: true\n");

        mvc.perform(confirm(admin, id, pullBody)).andExpect(status().isAccepted());
        awaitResults(1);

        assertThat(versionShas(id)).endsWith(second);
        assertThat(files.readCompose(hostId, "gitdeployment").orElseThrow()).contains(YAML_B).doesNotContain("privileged");
    }

    @Test
    void redeployWithACommitThatIsNotTheReviewedCheckoutIsRefused() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String first = head();
        commit("compose.yaml", YAML_B);
        String pullBody = pull(admin, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String hash = JsonPath.read(pullBody, "$.graphHash");
        commands.clear();

        // the hash is the one of the reviewed tree, the commit is another one that is in the local object store
        mvc.perform(admin.write(post(url() + "/" + id + "/git/redeploy")).contentType(APPLICATION_JSON)
                        .content("{\"commitSha\":\"%s\",\"graphHash\":\"%s\"}".formatted(first, hash)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILES_CHANGED_SINCE_REVIEW"));

        assertThat(commands).doesNotContain(ComposeCommand.UP);
        assertThat(versionShas(id)).hasSize(1);
    }

    @Test
    void redeployWithACommitThatIsNotLocalIsRefusedWithoutFetching() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        commit("compose.yaml", YAML_B);
        String pullBody = pull(admin, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String hash = JsonPath.read(pullBody, "$.graphHash");
        String unknown = commit("other.txt", "x");
        commands.clear();
        int before = gitRequests.get();

        mvc.perform(admin.write(post(url() + "/" + id + "/git/redeploy")).contentType(APPLICATION_JSON)
                        .content("{\"commitSha\":\"%s\",\"graphHash\":\"%s\"}".formatted(unknown, hash)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILES_CHANGED_SINCE_REVIEW"));

        assertThat(gitRequests.get()).isEqualTo(before);
        assertThat(commands).doesNotContain(ComposeCommand.UP);
        assertThat(versionShas(id)).hasSize(1);
    }

    @Test
    void adminRedeployWithoutAReviewIsRefused() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        commit("compose.yaml", YAML_B);
        commands.clear();

        mvc.perform(admin.write(post(url() + "/" + id + "/git/redeploy"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_REQUIRED"));

        assertThat(commands).doesNotContain(ComposeCommand.UP);
        assertThat(versionShas(id)).hasSize(1);
    }

    @Test
    void viewerCannotPullForReview() throws Exception {
        pull(login("viewer"), UUID.randomUUID().toString()).andExpect(status().isForbidden());
    }

    @Test
    void unchangedCommitStillRedeploysOnManualTrigger() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        mvc.perform(reviewedRedeploy(admin, id)).andExpect(status().isAccepted());
        awaitResults(1);
        mvc.perform(reviewedRedeploy(admin, id)).andExpect(status().isAccepted());
        awaitResults(2);

        assertThat(versionShas(id)).containsExactly(head(), head(), head());
        assertThat(commands.stream().filter(c -> c == ComposeCommand.UP).count()).isEqualTo(2);
    }

    @Test
    void rollbackCopyOfGitVersionKeepsCommitSha() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String first = head();
        commit("compose.yaml", YAML_B);
        mvc.perform(reviewedRedeploy(admin, id)).andExpect(status().isAccepted());
        awaitResults(1);
        UUID firstVersion = jdbc.queryForObject(
                "SELECT id FROM deployment_versions WHERE commit_sha = ?", UUID.class, first);

        mvc.perform(admin.write(post(url() + "/" + id + "/versions/" + firstVersion + "/rollback")))
                .andExpect(status().isAccepted());
        awaitResults(2);

        assertThat(jdbc.queryForObject("SELECT commit_sha FROM deployment_versions WHERE id = "
                + "(SELECT current_version_id FROM deployments WHERE id = ?::uuid)", String.class, id)).isEqualTo(first);
        assertThat(files.readCompose(hostId, "gitdeployment").orElseThrow()).contains(YAML_A);
    }

    @Test
    void composePathTraversalAbsoluteAndSymlinkOutOfRepoAreRejected() throws Exception {
        Path outside = Files.createTempFile("shipir-outside-", ".yaml");
        Files.writeString(outside, YAML_B);
        Files.createSymbolicLink(work.resolve("link.yaml"), outside);
        repo.add().addFilepattern("link.yaml").call();
        repo.commit().setMessage("link").setSign(false).setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com").call();
        Login admin = login("admin");

        for (String bad : List.of("../outside.yaml", "/etc/passwd", "a/../../b.yaml", "..%2Fx.yaml", "link.yaml")) {
            mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                            .content(gitJson("bad", base + "/public.git", "main", bad, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_COMPOSE_PATH"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        assertThat(files.deploymentDir(hostId, "bad")).doesNotExist();
    }

    @Test
    void errorsAreMappedAndLeaveNoDeploymentBehind() throws Exception {
        Login admin = login("admin");
        String wrong = credential("ghp_wrongwrongwrongwrong0000");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("a", base + "/private.git", "main", "compose.yaml", wrong)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GIT_AUTH_FAILED"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("a", base + "/private.git", "main", "compose.yaml", null)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GIT_AUTH_FAILED"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("a", base + "/public.git", "nope", "compose.yaml", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GIT_REF_NOT_FOUND"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("a", base + "/public.git", "main", "missing.yaml", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMPOSE_FILE_NOT_FOUND"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("a", "https://user:pw@example.com/r.git", "main", "compose.yaml", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REPO_URL"));
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"source\":\"GIT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        assertThat(files.deploymentDir(hostId, "a")).doesNotExist();
    }

    @Test
    void tokenNeverAppearsInResponsesAuditOrLogs() throws Exception {
        String cred = credential(TOKEN);
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", cred);
        commit("compose.yaml", YAML_B);
        StringBuilder seen = new StringBuilder();
        seen.append(mvc.perform(reviewedRedeploy(admin, id)).andReturn()
                .getResponse().getContentAsString());
        awaitResults(1);
        seen.append(mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("x", base + "/private.git", "nope", "compose.yaml", cred)))
                .andReturn().getResponse().getContentAsString());
        seen.append(mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(gitJson("y", base + "/private.git", "main", "compose.yaml", credential("ghp_bad"))))
                .andReturn().getResponse().getContentAsString());
        seen.append(jdbc.queryForList("SELECT cast(details as text) FROM audit_log", String.class));
        logs.list.forEach(e -> seen.append(e.getFormattedMessage())
                .append(e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()));

        assertThat(seen.toString()).doesNotContain(TOKEN).doesNotContain("ghp_bad");
    }

    @Test
    void gitDeploymentYamlIsReadOnlyButEnvIsEditable() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson(YAML_B, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GIT_MANAGED"));
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"env\":[{\"key\":\"MODE\",\"value\":\"prod\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yaml").value(YAML_A))
                .andExpect(jsonPath("$.envKeys[0]").value("MODE"));
        assertThat(files.readEnv(hostId, "gitdeployment").orElseThrow()).contains("MODE=prod");
        assertThat(files.readCompose(hostId, "gitdeployment").orElseThrow()).contains(YAML_A);
    }

    @Test
    void redeployOfFileDeploymentIsRejected() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("plain", YAML_A, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mvc.perform(admin.write(post(url() + "/" + id + "/git/redeploy")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_GIT_DEPLOYMENT"));
    }

    private static final String APP_YAML = """
            services:
              web:
                image: nginx
                env_file: ./app.env
                volumes:
                  - ./data:/data
                build:
                  context: .
            """;

    private Path repoCheckout(String name) {
        return files.repoDir(hostId, name);
    }

    @Test
    void composeRunsAgainstTheCheckoutSoRelativePathsResolveInTheRepo() throws Exception {
        commit("deploy/app.env", "FROM_REPO=1\n");
        commit("deploy/data/keep.txt", "x");
        commit("deploy/compose.yaml", APP_YAML);
        commit(".env", "TRACKED=repo\n");
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(("{\"name\":\"reldeployment\",\"source\":\"GIT\",\"repoUrl\":\"%s\",\"ref\":\"main\","
                                + "\"composePath\":\"deploy/compose.yaml\",\"env\":[{\"key\":\"SHIPIR_VAR\",\"value\":\"s\"}]}")
                                .formatted(base + "/public.git")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        Path expected = repoCheckout("reldeployment").resolve("deploy/compose.yaml");

        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        awaitResults(1);

        // validation (config) and up both ran on the file inside the checkout, whose directory holds app.env and data/
        assertThat(commands).contains(ComposeCommand.CONFIG, ComposeCommand.UP);
        assertThat(composeFiles).isNotEmpty().allMatch(expected::equals);
        assertThat(expected.getParent().resolve("app.env")).exists();
        assertThat(expected.getParent().resolve("data/keep.txt")).exists();
        // Shipir's env lives beside the checkout; the tracked .env of the repository is left untouched
        assertThat(files.readEnv(hostId, "reldeployment")).hasValueSatisfying(e -> assertThat(e).contains("SHIPIR_VAR=s"));
        assertThat(Files.readString(repoCheckout("reldeployment").resolve(".env"))).isEqualTo("TRACKED=repo\n");
    }

    @Test
    void fileDeploymentsStillUseTheStoredComposeFile() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"plain\",\"yaml\":\"services:\\n  web:\\n    image: nginx\\n\"}"))
                .andExpect(status().isCreated());
        assertThat(composeFiles).isNotEmpty().containsOnlyNulls();
    }

    @Test
    void rollbackChecksOutTheCommitOfThatVersionBeforeRunning() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String first = head();
        commit("compose.yaml", YAML_B);
        mvc.perform(reviewedRedeploy(admin, id)).andExpect(status().isAccepted());
        awaitResults(1);
        assertThat(Files.readString(repoCheckout("gitdeployment").resolve("compose.yaml"))).isEqualTo(YAML_B);
        UUID firstVersion = jdbc.queryForObject(
                "SELECT id FROM deployment_versions WHERE commit_sha = ?", UUID.class, first);

        mvc.perform(admin.write(post(url() + "/" + id + "/versions/" + firstVersion + "/rollback")))
                .andExpect(status().isAccepted());
        awaitResults(2);

        assertThat(Files.readString(repoCheckout("gitdeployment").resolve("compose.yaml"))).isEqualTo(YAML_A);
        assertThat(composeFiles.getLast()).isEqualTo(repoCheckout("gitdeployment").resolve("compose.yaml"));
    }

    @Test
    void failedPullLeavesTheCheckoutOnTheDeployedCommitAndPlainDeployStillWorks() throws Exception {
        Login admin = login("admin");
        String id = createGit(admin, "gitdeployment", credential(TOKEN));
        String invalid = commit("compose.yaml", "services:\n  web:\n    image: INVALID\n");
        pull(admin, id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPOSE"));
        // the failed pull no longer leaves the invalid commit checked out (shipr-y37c.2)
        assertThat(Files.readString(repoCheckout("gitdeployment").resolve("compose.yaml"))).isEqualTo(YAML_A);
        // alignCheckout of a plain deploy still covers a checkout found on a newer commit (simulated directly)
        try (Git local = Git.open(repoCheckout("gitdeployment").toFile())) {
            local.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(invalid).call();
        }
        assertThat(Files.readString(repoCheckout("gitdeployment").resolve("compose.yaml"))).contains("INVALID");

        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        awaitResults(1);

        assertThat(Files.readString(repoCheckout("gitdeployment").resolve("compose.yaml"))).isEqualTo(YAML_A);
    }
}
