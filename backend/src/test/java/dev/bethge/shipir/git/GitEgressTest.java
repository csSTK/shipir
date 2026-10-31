package dev.bethge.shipir.git;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.common.security.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.util.unit.DataSize;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** SSRF protection of the git sinks against local stubs: no real network, JDK HttpServer on loopback. */
class GitEgressTest {

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final List<String> lookups = new CopyOnWriteArrayList<>();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.add(ex.getRequestURI().getPath());
            if (ex.getRequestURI().getPath().startsWith("/redirect")) {
                ex.getResponseHeaders().add("Location",
                        "http://internal.test:" + server.getAddress().getPort() + "/r.git/info/refs?service=git-upload-pack");
                ex.sendResponseHeaders(302, -1);
            } else {
                ex.sendResponseHeaders(404, -1);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** internal.test resolves to the cloud metadata address; everything else resolves normally. */
    private EgressPolicy policy(boolean allowPrivate) {
        return new EgressPolicy(new EgressProperties(allowPrivate), host -> {
            lookups.add(host);
            return host.equals("internal.test")
                    ? new InetAddress[]{InetAddress.getByName("169.254.169.254")}
                    : InetAddress.getAllByName(host);
        });
    }

    static GitProperties props() {
        return new GitProperties(true, Duration.ofSeconds(2), Duration.ofMinutes(1), DataSize.ofMegabytes(50));
    }

    private GitRemoteChecker checker(boolean allowPrivate) {
        return new GitRemoteChecker(props(), policy(allowPrivate));
    }

    @Test
    void connectionTestNeverContactsLoopbackByDefault() {
        assertThat(checker(false).check(base + "/x.git", "127.0.0.1", "u", "t")).isEqualTo(GitRemoteChecker.UNREACHABLE);
        assertThat(hits).isEmpty();
    }

    @Test
    void connectionTestReachesLoopbackWithOptIn() {
        // 404 from the stub: reachable, so the outcome is NOT_FOUND rather than UNREACHABLE
        assertThat(checker(true).check(base + "/x.git", "127.0.0.1", "u", "t")).isEqualTo(GitRemoteChecker.NOT_FOUND);
        assertThat(hits).isNotEmpty();
    }

    @Test
    void redirectToInternalAddressIsRevalidatedAndRefused() {
        assertThat(checker(true).check(base + "/redirect.git", "127.0.0.1", "u", "t")).isEqualTo(GitRemoteChecker.UNREACHABLE);
        assertThat(hits).anyMatch(p -> p.startsWith("/redirect"));
        assertThat(hits).noneMatch(p -> p.startsWith("/r.git"));
        assertThat(lookups).contains("internal.test");
    }

    @Test
    void deniedAndUnreachableAreIndistinguishable() {
        GitRemoteChecker c = checker(false);
        assertThat(c.check("https://10.0.0.5/x.git", "127.0.0.1", "u", "t")).isEqualTo(GitRemoteChecker.UNREACHABLE);
        assertThat(c.check("https://169.254.169.254/x.git", "127.0.0.1", "u", "t")).isEqualTo(GitRemoteChecker.UNREACHABLE);
        assertThat(c.check("https://[::ffff:127.0.0.1]/x.git", "127.0.0.1", "u", "t")).isEqualTo(GitRemoteChecker.UNREACHABLE);
    }

    @Test
    void remoteCommitAndSyncUseTheSamePolicy() throws Exception {
        GitRepositoryService service = new GitRepositoryService(mock(GitCredentialRepository.class),
                mock(SecretCipher.class), checker(false), props());
        assertThatThrownBy(() -> service.remoteCommit(base + "/x.git", "main", null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.code()).isEqualTo("GIT_UNREACHABLE"));
        Path dir = Files.createTempDirectory("shipir-egress-");
        assertThatThrownBy(() -> service.sync(dir, base + "/x.git", "main", "compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.code()).isEqualTo("GIT_UNREACHABLE"));
        assertThat(hits).isEmpty();
    }

    @Test
    void connectsToTheValidatedAddressEvenIfTheNameRebindsAfterwards() {
        // first resolution of rebind.test: loopback (allowed with the opt-in); later ones: the metadata address
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        EgressPolicy rebinding = new EgressPolicy(new EgressProperties(true), host -> host.equals("rebind.test")
                ? new InetAddress[]{InetAddress.getByName(n.getAndIncrement() == 0 ? "127.0.0.1" : "169.254.169.254")}
                : InetAddress.getAllByName(host));

        String outcome = new GitRemoteChecker(props(), rebinding)
                .check("http://rebind.test:" + server.getAddress().getPort() + "/x.git", "rebind.test", "u", "t");

        // the stub answers 404 on the validated address: reached, so NOT_FOUND instead of UNREACHABLE
        assertThat(outcome).isEqualTo(GitRemoteChecker.NOT_FOUND);
        assertThat(hits).isNotEmpty();
    }
}
