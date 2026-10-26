package dev.bethge.shipir.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.common.security.SecretCipher;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Overall deadline, size guard and ref validation of {@link GitRepositoryService#sync} against a real JGit stub server. */
class GitSyncLimitsTest {

    @TempDir
    Path tmp;
    private HttpServer server;
    private Git repo;
    private String base;
    private String sha;
    private volatile long dripMillis;
    private volatile long refsDripMillis;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        Path work = tmp.resolve("origin");
        repo = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call();
        Files.writeString(work.resolve("compose.yaml"), "services: {}\n");
        byte[] noise = new byte[3_000_000];
        new Random(1).nextBytes(noise);
        Files.write(work.resolve("big.bin"), noise);
        for (int i = 0; i < 150; i++) {
            Files.writeString(work.resolve("f" + i + ".txt"), "file " + i + " " + "x".repeat(i));
        }
        repo.add().addFilepattern(".").call();
        sha = repo.commit().setMessage("c").setSign(false).setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com").call().getName();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        repo.close();
    }

    private void serve(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        hits.add(path);
        UploadPack up = new UploadPack(repo.getRepository());
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
        byte[] body = out.toByteArray();
        ex.sendResponseHeaders(200, body.length);
        boolean refs = path.endsWith("/info/refs");
        long delay = refs ? refsDripMillis : dripMillis;
        if (delay > 0) {
            int chunk = refs ? 10 : 2000;
            try {
                for (int i = 0; i < body.length; i += chunk) {
                    ex.getResponseBody().write(body, i, Math.min(chunk, body.length - i));
                    ex.getResponseBody().flush();
                    Thread.sleep(delay);
                }
            } catch (IOException | InterruptedException e) {
                // client gave up
            }
        } else {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    private GitRepositoryService service(Duration cloneTimeout, DataSize max) {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(true), InetAddress::getAllByName);
        GitProperties props = new GitProperties(true, Duration.ofSeconds(5), cloneTimeout, max);
        return new GitRepositoryService(mock(GitCredentialRepository.class), mock(SecretCipher.class),
                new GitRemoteChecker(props, policy), props);
    }

    @Test
    void syncWithinLimitsChecksOutTheCommit() {
        GitCheckout c = service(Duration.ofMinutes(1), DataSize.ofMegabytes(50))
                .sync(tmp.resolve("ok"), base + "/r.git", sha, "compose.yaml", null);
        assertThat(c.commitSha()).isEqualTo(sha);
        assertThat(c.composeYaml()).contains("services");
    }

    @Test
    void checkoutOverTheSizeCapIsAbortedAndDeleted() {
        Path dir = tmp.resolve("big");
        assertThatThrownBy(() -> service(Duration.ofMinutes(1), DataSize.ofKilobytes(500))
                .sync(dir, base + "/r.git", sha, "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class,
                        e -> assertThat(e.code()).isEqualTo("GIT_REPO_TOO_LARGE"));
        assertThat(dir).doesNotExist();
    }

    @Test
    void slowDripCloneIsStoppedByTheOverallDeadlineAndDeleted() {
        dripMillis = 150;
        Path dir = tmp.resolve("slow");
        long started = System.nanoTime();
        assertThatThrownBy(() -> service(Duration.ofSeconds(1), DataSize.ofMegabytes(50))
                .sync(dir, base + "/r.git", sha, "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class,
                        e -> assertThat(e.code()).isEqualTo("GIT_CLONE_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        assertThat(dir).doesNotExist();
    }

    @Test
    void slowDripOfTheRefAdvertisementIsStoppedByTheOverallDeadline() {
        refsDripMillis = 400;
        Path dir = tmp.resolve("slowrefs");
        long started = System.nanoTime();
        assertThatThrownBy(() -> service(Duration.ofSeconds(1), DataSize.ofMegabytes(50))
                .sync(dir, base + "/r.git", "main", "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class,
                        e -> assertThat(e.code()).isEqualTo("GIT_CLONE_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(dir).doesNotExist();
    }

    @Test
    void slowDripOfTheRefAdvertisementIsStoppedForAShaRefToo() {
        refsDripMillis = 400;
        Path dir = tmp.resolve("slowsha");
        long started = System.nanoTime();
        assertThatThrownBy(() -> service(Duration.ofSeconds(1), DataSize.ofMegabytes(50))
                .sync(dir, base + "/r.git", sha, "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class,
                        e -> assertThat(e.code()).isEqualTo("GIT_CLONE_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(dir).doesNotExist();
    }

    @Test
    void failedResyncKeepsAnExistingCheckoutOverTheSizeCap() {
        Path dir = tmp.resolve("existing");
        service(Duration.ofMinutes(1), DataSize.ofMegabytes(50)).sync(dir, base + "/r.git", sha, "compose.yaml", null);
        assertThatThrownBy(() -> service(Duration.ofMinutes(1), DataSize.ofKilobytes(500))
                .sync(dir, base + "/r.git", sha, "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class,
                        e -> assertThat(e.code()).isEqualTo("GIT_REPO_TOO_LARGE"));
        assertThat(dir.resolve("compose.yaml")).exists();
        assertThat(dir.resolve("big.bin")).exists();
    }

    @Test
    void failedResyncKeepsAnExistingCheckoutOnTimeout() {
        Path dir = tmp.resolve("existing2");
        service(Duration.ofMinutes(1), DataSize.ofMegabytes(50)).sync(dir, base + "/r.git", sha, "compose.yaml", null);
        refsDripMillis = 400;
        assertThatThrownBy(() -> service(Duration.ofSeconds(1), DataSize.ofMegabytes(50))
                .sync(dir, base + "/r.git", "main", "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class,
                        e -> assertThat(e.code()).isEqualTo("GIT_CLONE_TIMEOUT"));
        assertThat(dir.resolve("compose.yaml")).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--upload-pack=touch /tmp/x", "-x", "main branch", "a..b", "main;id", "$(id)", "a//b",
            "main.lock", "ref/", "-", "refs/heads/../x", "a/.hidden", ""})
    void syncRejectsMalformedRefsBeforeAnyNetworkTraffic(String ref) {
        GitRepositoryService service = service(Duration.ofMinutes(1), DataSize.ofMegabytes(50));
        assertThatThrownBy(() -> service.sync(tmp.resolve("r"), base + "/r.git", ref, "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_GIT_REF");
                    assertThat(e.status().value()).isEqualTo(400);
                });
        assertThatThrownBy(() -> service.remoteCommit(base + "/r.git", ref, null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_GIT_REF"));
        assertThat(hits).isEmpty();
    }

    @Test
    void validRefsPass() {
        for (String ref : new String[]{"main", "release/1.2", "v1.0.0", "feature_x-y", sha}) {
            GitRepositoryService.validateRef(ref);
        }
    }
}
