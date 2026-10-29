package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.LogLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.io.Closeable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Log download against stubbed Docker services: streaming, headers, byte cap, parameter validation, visibility. */
@TestPropertySource(properties = {"shipir.log-download.max-bytes=200B", "shipir.log-download.timeout=3s",
        "shipir.log-download.max-concurrent=3", "shipir.log-download.max-per-user=2"})
class ContainerLogDownloadApiTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containerService;
    @MockitoBean
    ContainerLogService logService;

    private String host;
    private final Closeable closeable = mock(Closeable.class);
    private volatile String project;
    private volatile String name = "web-1";
    private List<LogLine> lines = List.of();
    private Throwable failure;
    private volatile boolean hang;
    /** End callbacks of hanging reads; a test completes them to let a held download finish without waiting for the time limit. */
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Throwable>> hangingEnds =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
    @org.springframework.beans.factory.annotation.Autowired
    ContainerLogDownloadService downloads;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
        project = null;
        name = "web-1";
        autoGrant = true;
        failure = null;
        hang = false;
        started.set(0);
        hangingEnds.clear();
        when(containerService.inspect(any(), any(DockerEndpoint.class), any())).thenAnswer(inv -> details(inv.getArgument(2)));
        when(logService.snapshot(any(), any(DockerEndpoint.class), any(), anyInt(), any(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<LogLine> onLine = inv.getArgument(6);
                    Consumer<Throwable> onEnd = inv.getArgument(7);
                    if (hang) { // registered before it counts as started, so a test that saw it started can end it
                        hangingEnds.add(onEnd);
                    }
                    started.incrementAndGet();
                    lines.forEach(onLine);
                    if (!hang) { // a hanging read never reports its end on its own
                        onEnd.accept(failure);
                    }
                    return closeable;
                });
    }

    private ContainerDetailsDto details(String id) {
        return new ContainerDetailsDto(id, name, "img", "running", Instant.now(), Instant.now(), 0, null, List.of(),
                List.of(), false, false, List.of(), List.of(), java.util.Map.of(), List.of(), List.of(), null,
                project, project == null ? null : "web");
    }

    private static LogLine out(String text) {
        return new LogLine(LogLine.Stream.STDOUT, text, Instant.parse("2026-01-02T03:04:05Z"));
    }

    private String download(Login l, String query) throws Exception {
        return downloadResult(l, query).getResponse().getContentAsString();
    }

    private MvcResult downloadResult(Login l, String query) throws Exception {
        return mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download" + query, host)
                .session(l.session())).andReturn();
    }

    @Test
    void streamsLogsAsTextAttachment() throws Exception {
        lines = List.of(out("one"), new LogLine(LogLine.Stream.STDERR, "two", Instant.parse("2026-01-02T03:04:06Z")));
        Login l = login("viewer");
        MvcResult r = downloadResult(l, "");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/plain");
        assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("attachment")
                .contains("web-1-logs.txt");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getContentAsString()).isEqualTo(
                "2026-01-02T03:04:05Z one\n2026-01-02T03:04:06Z two\n");
    }

    /** The token authenticates the streamed download; the body is written synchronously, so there is no async dispatch. */
    @Test
    void apiTokenAuthenticatesTheStreamedDownload() throws Exception {
        lines = List.of(out("one"));
        login("viewer"); // grants the host to the seeded users
        String plain = dev.bethge.shipir.auth.token.TokenCodec.newToken("shp_");
        jdbc.update("INSERT INTO api_tokens (user_id, name, token_hash, prefix, created_at, expires_at) "
                        + "SELECT id, 'seeded', ?, ?, now(), now() + interval '30 days' FROM users WHERE username = 'viewer'",
                dev.bethge.shipir.auth.token.TokenCodec.sha256Hex(plain), plain.substring(0, 8));

        MvcResult done = mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host)
                .header("Authorization", "Bearer " + plain)).andReturn();

        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(done.getResponse().getContentAsString()).isEqualTo("2026-01-02T03:04:05Z one\n");
    }

    @Test
    void timestampsCanBeLeftOut() throws Exception {
        lines = List.of(out("one"));
        assertThat(download(login("operator"), "?timestamps=false")).isEqualTo("one\n");
        verify(logService).snapshot(any(), any(DockerEndpoint.class), eq("web1"), eq(-1), eq(null), eq(false), any(), any());
    }

    @Test
    void sinceAndTailArePassedToDocker() throws Exception {
        Login l = login("viewer");
        download(l, "?since=2026-01-02T03:04:05Z&tail=250");
        verify(logService).snapshot(any(), any(DockerEndpoint.class), eq("web1"), eq(250),
                eq(Instant.parse("2026-01-02T03:04:05Z")), eq(true), any(), any());
        download(l, "?since=1767323045&tail=all");
        verify(logService).snapshot(any(), any(DockerEndpoint.class), eq("web1"), eq(-1),
                eq(Instant.ofEpochSecond(1767323045L)), eq(true), any(), any());
    }

    @Test
    void stopsAtTheByteCapAndSaysSo() throws Exception {
        lines = java.util.stream.IntStream.range(0, 100).mapToObj(i -> out("line-" + i + "-" + "x".repeat(20))).toList();
        String body = download(login("admin"), "?timestamps=false");
        assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200 + 80);
        assertThat(body).contains("line-0-").doesNotContain("line-99-").endsWith("[output truncated: size limit reached]\n");
        verify(closeable, timeout(2000).atLeastOnce()).close();
    }

    @Test
    void failedReadEndsWithAMarkerNotWithDaemonText() throws Exception {
        lines = List.of(out("one"));
        failure = new DockerConnectionException(DockerErrorCode.UNREACHABLE, "secret internal detail", null);
        String body = download(login("admin"), "?timestamps=false");
        assertThat(body).startsWith("one\n").contains("[log stream failed]").doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"?since=garbage", "?since=-5", "?since=1e9", "?since=2026-13-45T00:00:00Z", "?since=%00",
            "?since=99999999999999999999", "?tail=0", "?tail=-1", "?tail=abc", "?tail=99999999999", "?tail=10;rm",
            "?tail=1000001", "?timestamps=maybe"})
    void rejectsInvalidParameters(String query) throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download" + query, host).session(login("admin").session()))
                .andExpect(status().isBadRequest());
        verify(logService, never()).snapshot(any(), any(DockerEndpoint.class), any(), anyInt(), any(), anyBoolean(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "a%2Fb", "-x", "a b", "%00", "x;y"})
    void rejectsInvalidContainerIds(String id) throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/{c}/logs/download", host, id).session(login("admin").session()))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(logService);
    }

    @Test
    void containerNameIsSanitizedInTheFileName() throws Exception {
        name = "/we\"b\r\nSet-Cookie: x=1;/../ü";
        MvcResult r = downloadResult(login("admin"), "");
        String disposition = r.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).doesNotContain("\r").doesNotContain("\n").doesNotContain("/").doesNotContain("..");
        assertThat(disposition).matches("attachment; filename=\"[A-Za-z0-9._-]+-logs\\.txt\"");
        assertThat(r.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.contains("x=1"));
    }

    @Test
    void emptyNameFallsBackToContainer() throws Exception {
        name = "///";
        assertThat(downloadResult(login("admin"), "").getResponse().getHeader("Content-Disposition"))
                .contains("container-logs.txt");
    }

    @Test
    void unknownContainerIs404() throws Exception {
        when(containerService.inspect(any(), any(DockerEndpoint.class), eq("web1")))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "no such container", null));
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(login("viewer").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unreachableHostIs502() throws Exception {
        when(containerService.inspect(any(), any(DockerEndpoint.class), eq("web1")))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "boom", null));
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(login("viewer").session()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UNREACHABLE"));
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", UUID.randomUUID()).session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void hostWithoutAnyGrantIsInvisible() throws Exception {
        jdbc.update("DELETE FROM permissions");
        Login l = loginWithoutGrants("viewer");
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(l.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        verifyNoInteractions(logService);
    }

    @Test
    void deploymentOnlyGrantSeesOnlyContainersOfThatDeployment() throws Exception {
        jdbc.update("DELETE FROM permissions");
        UUID admin = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        UUID viewer = jdbc.queryForObject("SELECT id FROM users WHERE username = 'viewer'", UUID.class);
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "VALUES ('shop', ?::uuid, 'x', ?) RETURNING id", UUID.class, host, admin);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')", viewer, dep);
        Login l = loginWithoutGrants("viewer");

        project = "shop";
        lines = List.of(out("visible"));
        assertThat(download(l, "?timestamps=false")).isEqualTo("visible\n");

        project = "other";
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(l.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        project = null;
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(l.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host)).andExpect(status().isUnauthorized());
        verifyNoInteractions(logService);
    }

    @Test
    void doesNotFollow() throws Exception {
        download(login("admin"), "");
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any());
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void timeLimitEndsAHangingReadWithAMarkerAndClosesIt() throws Exception {
        hang = true;
        lines = List.of(out("one"));
        Login l = login("admin");
        long t0 = System.nanoTime();
        MvcResult r = downloadResult(l, "?timestamps=false");
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertThat(millis).isGreaterThanOrEqualTo(2500L);
        assertThat(r.getResponse().getContentAsString()).isEqualTo("one\n[output truncated: time limit reached]\n");
        verify(closeable, timeout(2000).atLeastOnce()).close();
    }

    @Test
    void clientAbortClosesTheReadAndFreesThePermit() throws Exception {
        lines = List.of(out("one"), out("two"), out("three"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "x",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            // more aborted downloads than the per-user limit: a leaked permit would turn the third into a 429
            for (int i = 0; i < 4; i++) {
                var d = downloads.open(UUID.fromString(host), "web1", null, null, "false");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.body().writeTo(new java.io.OutputStream() {
                    @Override
                    public void write(int b) throws java.io.IOException {
                        throw new java.io.IOException("client gone");
                    }
                })).isInstanceOf(java.io.IOException.class);
                verify(closeable, timeout(2000).atLeast(i + 1)).close();
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void asAdmin(java.util.concurrent.Callable<?> body) throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "x",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            body.call();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /** open() takes the permit; a failure before the body runs (headers, output stream) must still free it. */
    @Test
    void permitIsFreedWhenTheResponseFailsBetweenOpenAndWrite() throws Exception {
        lines = List.of(out("one"));
        var controller = new ContainerLogDownloadController(downloads);
        var failingHeaders = mock(jakarta.servlet.http.HttpServletResponse.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("committed")).when(failingHeaders).setContentType(any());
        var failingStream = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(failingStream.getOutputStream()).thenThrow(new java.io.IOException("gone"));
        asAdmin(() -> {
            // more failures than max-per-user and max-concurrent: a leaked permit would turn one into a 429
            for (int i = 0; i < 6; i++) {
                var r = i % 2 == 0 ? failingHeaders : failingStream;
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller.download(UUID.fromString(host), "web1", null, null, "false", r))
                        .isNotInstanceOf(HostException.class);
            }
            return null;
        });
    }

    /** A client that stops reading blocks inside out.write; the watchdog closes the stream so the permit comes back. */
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void stalledClientWriteIsAbortedAfterTheTimeAndFreesThePermit() throws Exception {
        lines = List.of(out("one"));
        hang = true;
        asAdmin(() -> {
            for (int i = 0; i < 3; i++) { // per-user limit is 2: leaked permits would produce a 429 on the third
                var d = downloads.open(UUID.fromString(host), "web1", null, null, "false");
                var closed = new java.util.concurrent.CountDownLatch(1);
                var stalled = new java.io.OutputStream() {
                    @Override
                    public void write(int b) throws java.io.IOException {
                        write(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws java.io.IOException {
                        try {
                            closed.await(); // the client never reads
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new java.io.IOException("stream closed");
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
                long t0 = System.nanoTime();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.body().writeTo(stalled))
                        .isInstanceOf(java.io.IOException.class);
                assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(2500L);
            }
            return null;
        });
    }

    /** A watchdog firing after the download finished closes the Docker read but must not touch the response stream. */
    @Test
    void watchdogLeavesTheResponseStreamAloneOnceTheDownloadIsDone() throws Exception {
        var out = mock(java.io.OutputStream.class);
        var docker = mock(Closeable.class);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ContainerLogDownloadService.abortStalled(new java.util.concurrent.atomic.AtomicBoolean(true), cancelled, out, docker);
        verify(docker).close();
        assertThat(cancelled).isTrue();
        verifyNoInteractions(out);
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void limitsConcurrentDownloadsPerUserAndGlobally() throws Exception {
        hang = true;
        lines = List.of(out("one"));
        Login admin = login("admin");
        Login operator = login("operator");
        Login viewer = login("viewer");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var a1 = pool.submit(() -> downloadResult(admin, "?timestamps=false"));
            var a2 = pool.submit(() -> downloadResult(admin, "?timestamps=false"));
            org.awaitility.Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).until(() -> started.get() == 2);

            // third download of the same user: refused before any Docker call
            mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(admin.session()))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_DOWNLOADS"))
                    .andExpect(header().exists("Retry-After"));

            var o1 = pool.submit(() -> downloadResult(operator, "?timestamps=false"));
            org.awaitility.Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).until(() -> started.get() == 3);
            // global limit (3) reached: another user is refused too
            mvc.perform(get("/api/hosts/{h}/containers/web1/logs/download", host).session(viewer.session()))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_DOWNLOADS"));
            assertThat(started.get()).isEqualTo(3);
            verify(containerService, org.mockito.Mockito.times(3)).inspect(any(), any(DockerEndpoint.class), any());

            hangingEnds.forEach(end -> end.accept(null)); // the reads end now, no wait for the time limit
            a1.get(); a2.get(); o1.get();
        } finally {
            pool.shutdownNow();
        }
        // permits are back
        hang = false;
        assertThat(download(admin, "?timestamps=false")).isEqualTo("one\n");
        assertThat(download(admin, "?timestamps=false")).isEqualTo("one\n");
        assertThat(download(admin, "?timestamps=false")).isEqualTo("one\n");
    }

    private boolean autoGrant = true;

    @Override
    protected boolean autoGrantHosts() {
        return autoGrant;
    }

    private Login loginWithoutGrants(String user) throws Exception {
        autoGrant = false;
        return login(user);
    }
}
