package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.BuildSpec;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitOperationException;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.RunStatus;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /images/build against a stubbed ImageService: validation, limits, roles, audit, streaming, cleanup, Git source. */
@TestPropertySource(properties = {"shipir.images.max-build-context=2KB",
        "shipir.images.build-temp-dir=target/image-build-test-tmp"})
class HostImageBuildTest extends HostApiTestBase {

    private static final Path TMP = Path.of("target/image-build-test-tmp");
    private static final byte[] CONTEXT = "tar-bytes-stand-in".getBytes(StandardCharsets.UTF_8);
    private static final String REPO = "https://example.com/org/repo.git";

    @MockitoBean
    ImageService images;
    @MockitoBean
    ComposeOutputStreamService streams;
    @MockitoBean
    RegistryAuthSource registryAuths;
    @MockitoBean
    GitRepositoryService git;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private String host;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        lines.clear();
        when(streams.start(any())).thenReturn((Consumer<String>) lines::add);
        when(registryAuths.auths()).thenReturn(List.of(new RegistryAuthSource.Auth("ghcr.io", "bot", "s3cret-token")));
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private MockMultipartHttpServletRequestBuilder build(byte[] context, String... params) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/hosts/{h}/images/build", host);
        if (context != null) {
            b.file(new MockMultipartFile("context", "ctx.tar", "application/x-tar", context));
        }
        for (int i = 0; i < params.length; i += 2) {
            b.param(params[i], params[i + 1]);
        }
        return b;
    }

    private static long tmpEntries() throws Exception {
        if (!Files.exists(TMP)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(TMP)) {
            return s.count();
        }
    }

    private void awaitCleanTmp() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(tmpEntries()).isZero());
    }

    private void verifyNoBuild() {
        verify(images, never()).build(any(), any(), any(), any(), any(), any());
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') || ':' || coalesce(details::text, '') "
                + "FROM audit_log WHERE action = ?", String.class, action);
    }

    // ---- happy path ----

    @Test
    void adminBuildsFromUploadStreamsOutputAndAudits() throws Exception {
        AtomicReference<byte[]> sent = new AtomicReference<>();
        doAnswer(inv -> {
            sent.set(inv.<Supplier<InputStream>>getArgument(2).get().readAllBytes());
            inv.<Consumer<String>>getArgument(5).accept("Step 1/2 : FROM busybox");
            return null;
        }).when(images).build(any(), any(), any(), any(), any(), any());

        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:1", "buildArg", "VERSION=1,2", "buildArg", "MODE=prod")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNotEmpty());

        ArgumentCaptor<BuildSpec> spec = ArgumentCaptor.forClass(BuildSpec.class);
        verify(images, timeout(5000)).build(any(), any(), any(), spec.capture(), any(), any());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
        assertThat(spec.getValue().dockerfile()).isEqualTo("Dockerfile");
        assertThat(spec.getValue().tag()).isEqualTo("myapp:1");
        assertThat(spec.getValue().buildArgs()).containsEntry("VERSION", "1,2").containsEntry("MODE", "prod");
        assertThat(spec.getValue().noCache()).isFalse();
        assertThat(spec.getValue().maxContextBytes()).isEqualTo(2048);
        assertThat(spec.getValue().toString()).doesNotContain("1,2").doesNotContain("prod");
        assertThat(sent.get()).isEqualTo(CONTEXT);
        assertThat(lines).contains("Step 1/2 : FROM busybox");
        verify(streams).registerRun(any(), eq(UUID.fromString(host)), eq(null));
        assertThat(audit("IMAGE_BUILD")).hasSize(1).allSatisfy(row -> assertThat(row)
                .startsWith("SUCCESS:myapp:1:").contains("UPLOAD").doesNotContain("VERSION").doesNotContain("prod"));
        assertThat(String.join("\n", lines)).doesNotContain("prod");
        awaitCleanTmp();
    }

    @Test
    void customDockerfileAndNoCacheArePassedOn() throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:2", "dockerfile", "docker/App.Dockerfile", "noCache", "true")))
                .andExpect(status().isAccepted());
        ArgumentCaptor<BuildSpec> spec = ArgumentCaptor.forClass(BuildSpec.class);
        verify(images, timeout(5000)).build(any(), any(), any(), spec.capture(), any(), any());
        assertThat(spec.getValue().dockerfile()).isEqualTo("docker/App.Dockerfile");
        assertThat(spec.getValue().noCache()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedRegistryLoginsGoToTheDaemonWithoutLeaking() throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:3"))).andExpect(status().isAccepted());
        ArgumentCaptor<List<RegistryLogin>> auths = ArgumentCaptor.forClass(List.class);
        verify(images, timeout(5000)).build(any(), any(), any(), any(), auths.capture(), any());
        assertThat(auths.getValue()).hasSize(1);
        assertThat(auths.getValue().get(0).registryHost()).isEqualTo("ghcr.io");
        assertThat(auths.getValue().toString()).doesNotContain("s3cret-token");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE details::text LIKE '%s3cret%' OR target_id LIKE '%s3cret%'",
                Integer.class)).isZero();
    }

    // ---- failures end the run without daemon text and clean up ----

    @Test
    void failedBuildEndsRunAsFailedWithoutDaemonTextAndCleansTemp() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.BUILD_FAILED, "RUN secret-token failed", null))
                .when(images).build(any(), any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:4"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Build failed (BUILD_FAILED)");
        assertThat(String.join("\n", lines)).doesNotContain("secret-token");
        awaitCleanTmp();
    }

    @Test
    void longAndNumerousOutputLinesAreBounded() throws Exception {
        doAnswer(inv -> {
            Consumer<String> out = inv.getArgument(5);
            out.accept("x".repeat(100_000));
            for (int i = 0; i < 50_000; i++) {
                out.accept("line " + i);
            }
            return null;
        }).when(images).build(any(), any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:5"))).andExpect(status().isAccepted());
        verify(streams, timeout(10000)).complete(any(), eq(RunStatus.DONE));
        assertThat(lines.size()).isLessThan(20_000);
        assertThat(lines).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(1000));
    }

    @Test
    void secondBuildOnTheSameHostIs409WhileTheFirstRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(images).build(any(), any(), any(), any(), any(), any());
        Login admin = login("admin");
        mvc.perform(admin.write(build(CONTEXT, "tag", "myapp:6"))).andExpect(status().isAccepted());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        mvc.perform(admin.write(build(CONTEXT, "tag", "myapp:7")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUILD_IN_PROGRESS"));
        release.countDown();
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mvc.perform(admin.write(build(CONTEXT, "tag", "myapp:8"))).andExpect(status().isAccepted()));
    }

    @Test
    void rejectedRequestDoesNotBlockTheHostSlot() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(build(CONTEXT, "tag", "BAD TAG"))).andExpect(status().isBadRequest());
        mvc.perform(admin.write(build(CONTEXT, "tag", "ok:1"))).andExpect(status().isAccepted());
    }

    // ---- input validation ----

    @ParameterizedTest
    @ValueSource(strings = {"", "-v", "--network=host", "My App", "a;b", "app:", "../x", "http://x/y", "app@sha256:abc",
            "app@sha256:0000000000000000000000000000000000000000000000000000000000000000", "app:tag with space"})
    void invalidTagIs400(String tag) throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT, "tag", tag)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
        verifyNoBuild();
        assertThat(tmpEntries()).isZero();
    }

    @Test
    void missingTagIs400() throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../Dockerfile", "a/../../Dockerfile", "/etc/passwd", "/Dockerfile", "a\\b", "a//b", "-Dockerfile",
            "Docker file", "Dockerfile\u0000x", "%2e%2e/Dockerfile", "a/./b", "dir/"})
    void badDockerfilePathIs400(String path) throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:1", "dockerfile", path)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCKERFILE_PATH"));
        verifyNoBuild();
        assertThat(tmpEntries()).isZero();
    }

    @Test
    void overlongDockerfilePathIs400() throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:1", "dockerfile", "x".repeat(300))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCKERFILE_PATH"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOEQUALS", "=value", "1BAD=x", "A B=x", "A;rm -rf /=x", "A$(id)=x", "A-B=x", "K=va\u0000lue"})
    void badBuildArgIs400(String arg) throws Exception {
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "myapp:1", "buildArg", arg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BUILD_ARG"));
        verifyNoBuild();
    }

    @Test
    void duplicateTooManyAndTooLongBuildArgsAre400() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(build(CONTEXT, "tag", "a:1", "buildArg", "A=1", "buildArg", "A=2")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BUILD_ARG"));
        mvc.perform(admin.write(build(CONTEXT, "tag", "a:1", "buildArg", "K=" + "v".repeat(5000))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BUILD_ARG"));
        List<String> many = new ArrayList<>(List.of("tag", "a:1"));
        for (int i = 0; i < 60; i++) {
            many.add("buildArg");
            many.add("K" + i + "=v");
        }
        mvc.perform(admin.write(build(CONTEXT, many.toArray(String[]::new))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BUILD_ARG"));
        verifyNoBuild();
    }

    @Test
    void buildArgErrorNeverEchoesTheValue() throws Exception {
        String body = mvc.perform(login("admin").write(build(CONTEXT, "tag", "a:1", "buildArg", "bad key=super-secret-value")))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("super-secret-value");
    }

    @Test
    void needsExactlyOneSource() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(build(null, "tag", "a:1"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BUILD_SOURCE"));
        mvc.perform(admin.write(build(CONTEXT, "tag", "a:1", "gitUrl", REPO, "gitRef", "main")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BUILD_SOURCE"));
        verifyNoBuild();
    }

    // ---- context size and hostile archives ----

    @Test
    void oversizeContextIs413AndNothingIsLeftOnDisk() throws Exception {
        mvc.perform(login("admin").write(build(new byte[3000], "tag", "a:1")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
        verifyNoBuild();
        assertThat(tmpEntries()).isZero();
        assertThat(audit("IMAGE_BUILD")).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void hostileArchiveIs400AndNothingIsLeftOnDisk() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "x", null))
                .when(images).validateBuildContext(any(), anyLong());
        mvc.perform(login("admin").write(build(CONTEXT, "tag", "a:1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));
        verifyNoBuild();
        assertThat(tmpEntries()).isZero();
    }

    // ---- roles, csrf, host ----

    @Test
    void operatorAndViewerAreForbidden() throws Exception {
        for (String user : new String[]{"operator", "viewer"}) {
            mvc.perform(login(user).write(build(CONTEXT, "tag", "a:1"))).andExpect(status().isForbidden());
        }
        verifyNoBuild();
        assertThat(tmpEntries()).isZero();
    }

    @Test
    void csrfTokenIsRequired() throws Exception {
        mvc.perform(build(CONTEXT, "tag", "a:1").session(login("admin").session())).andExpect(status().isForbidden());
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(login("admin").write(multipart("/api/hosts/{h}/images/build", UUID.randomUUID())
                        .file(new MockMultipartFile("context", "c.tar", "application/x-tar", CONTEXT)).param("tag", "a:1")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    // ---- Git source ----

    @Test
    void gitSourceIsClonedTarredWithoutDotGitAndCleanedUp() throws Exception {
        UUID cred = UUID.randomUUID();
        AtomicReference<List<String>> names = new AtomicReference<>();
        doAnswer(inv -> {
            Path dir = inv.getArgument(0);
            Files.createDirectories(dir.resolve(".git"));
            Files.writeString(dir.resolve(".git/config"), "[core]");
            Files.writeString(dir.resolve("Dockerfile"), "FROM busybox\n");
            Files.createDirectories(dir.resolve("src"));
            Files.writeString(dir.resolve("src/app.sh"), "echo hi\n");
            Files.createSymbolicLink(dir.resolve("escape"), Path.of("/etc/passwd"));
            return new GitCheckout("c".repeat(40), "FROM busybox\n");
        }).when(git).sync(any(), eq(REPO), eq("main"), eq("Dockerfile"), eq(cred));
        doAnswer(inv -> {
            List<String> found = new ArrayList<>();
            try (TarArchiveInputStream tar = new TarArchiveInputStream(inv.<Supplier<InputStream>>getArgument(2).get())) {
                for (TarArchiveEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                    found.add(e.getName());
                }
            }
            names.set(found);
            return null;
        }).when(images).build(any(), any(), any(), any(), any(), any());

        mvc.perform(login("admin").write(build(null, "tag", "fromgit:1", "gitUrl", REPO, "gitRef", "main",
                        "gitCredentialId", cred.toString())))
                .andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
        assertThat(names.get()).contains("Dockerfile", "src/app.sh")
                .noneMatch(n -> n.startsWith(".git")).noneMatch(n -> n.contains("escape"));
        assertThat(audit("IMAGE_BUILD")).hasSize(1)
                .allSatisfy(r -> assertThat(r).contains("GIT").doesNotContain("example.com"));
        awaitCleanTmp();
    }

    @Test
    void gitContextOverTheLimitEndsRunFailedAndCleansUp() throws Exception {
        doAnswer(inv -> {
            Path dir = inv.getArgument(0);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Dockerfile"), "FROM busybox\n");
            Files.write(dir.resolve("big.bin"), new byte[3000]);
            return new GitCheckout("c".repeat(40), "FROM busybox\n");
        }).when(git).sync(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(build(null, "tag", "a:1", "gitUrl", REPO, "gitRef", "main")))
                .andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Build failed (SIZE_LIMIT_EXCEEDED)");
        verifyNoBuild();
        awaitCleanTmp();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--upload-pack=touch /tmp/x", "-x", " main", "main branch", "a..b", "main;id", "$(id)", "a//b",
            "main.lock", "ref/", "-"})
    void gitRefInjectionIs400AndNeverReachesGit(String ref) throws Exception {
        mvc.perform(login("admin").write(build(null, "tag", "a:1", "gitUrl", REPO, "gitRef", ref)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GIT_REF"));
        verify(git, never()).sync(any(), any(), any(), any(), any());
        verifyNoBuild();
    }

    @Test
    void overlongGitRefIs400() throws Exception {
        mvc.perform(login("admin").write(build(null, "tag", "a:1", "gitUrl", REPO, "gitRef", "x".repeat(300))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GIT_REF"));
    }

    @Test
    void badGitUrlUsesTheGitServiceVerdict() throws Exception {
        doThrow(new GitOperationException(HttpStatus.BAD_REQUEST, "INVALID_REPO_URL", "bad url"))
                .when(git).validateUrl("ftp://x/y");
        mvc.perform(login("admin").write(build(null, "tag", "a:1", "gitUrl", "ftp://x/y", "gitRef", "main")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REPO_URL"));
        verify(git, never()).sync(any(), any(), any(), any(), any());
    }

    @Test
    void gitFailureEndsRunWithItsCodeAndCleansUp() throws Exception {
        doThrow(new GitOperationException(HttpStatus.BAD_REQUEST, "GIT_REF_NOT_FOUND", "nope"))
                .when(git).sync(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(build(null, "tag", "a:1", "gitUrl", REPO, "gitRef", "v9")))
                .andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Build failed (GIT_REF_NOT_FOUND)");
        verifyNoBuild();
        awaitCleanTmp();
    }

    @Test
    void missingDockerfileInTheRepositoryIsReportedAsDockerfileNotFound() throws Exception {
        doThrow(new GitOperationException(HttpStatus.BAD_REQUEST, "COMPOSE_FILE_NOT_FOUND", "nope"))
                .when(git).sync(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(build(null, "tag", "a:1", "gitUrl", REPO, "gitRef", "main")))
                .andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Build failed (DOCKERFILE_NOT_FOUND)");
    }
}
