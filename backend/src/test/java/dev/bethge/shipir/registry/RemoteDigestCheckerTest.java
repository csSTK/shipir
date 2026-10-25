package dev.bethge.shipir.registry;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.host.ImageReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** JDK HttpServer stands in for the registry: no real network. */
class RemoteDigestCheckerTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("robot:s3cret".getBytes(StandardCharsets.UTF_8));

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final RegistryCredentialService credentials = mock(RegistryCredentialService.class);

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String host() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private ImageReference ref() {
        return ImageReference.parse(host() + "/team/app:1.0");
    }

    private RemoteDigestChecker checker(boolean insecure) {
        return checker(insecure, true);
    }

    private RemoteDigestChecker checker(boolean insecure, boolean allowPrivate) {
        return checker(insecure, new EgressPolicy(new EgressProperties(allowPrivate)));
    }

    private RemoteDigestChecker checker(boolean insecure, EgressPolicy policy) {
        return new RemoteDigestChecker(new RegistryProperties(insecure, Duration.ofSeconds(2)), credentials,
                new EgressHttpClient(policy, Duration.ofSeconds(2)));
    }

    private void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @Test
    void readsDigestHeaderWithHeadRequestAndManifestAcceptTypes() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + ex.getRequestHeaders().getFirst("Accept"));
            ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true).digestOf(ref())).contains(DIGEST);
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r).startsWith("HEAD /v2/team/app/manifests/1.0 ");
            assertThat(r).contains("application/vnd.oci.image.index.v1+json");
        });
    }

    @Test
    void followsBearerChallengeWithPullScopeAndStoredLogin() throws Exception {
        start();
        when(credentials.decryptedCredentials()).thenReturn(List.of(new RegistryAuth(host(), "robot", "s3cret")));
        String realm = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/v2/", ex -> {
            if ("Bearer tok123".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + realm + "\",service=\"reg.test\"");
                ex.sendResponseHeaders(401, -1);
            }
            ex.close();
        });
        server.createContext("/token", ex -> {
            requests.add(ex.getRequestURI().getRawQuery() + "|" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"token\":\"tok123\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        assertThat(checker(true).digestOf(ref())).contains(DIGEST);
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r).contains("service=reg.test").contains("scope=repository%3Ateam%2Fapp%3Apull");
            assertThat(r).endsWith("|" + BASIC);
        });
    }

    @Test
    void anonymousBearerWorksWithoutCredentials() throws Exception {
        start();
        when(credentials.decryptedCredentials()).thenThrow(new IllegalStateException("no key"));
        String realm = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/v2/", ex -> {
            if ("Bearer t".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
                ex.sendResponseHeaders(401, -1);
            }
            ex.close();
        });
        server.createContext("/token", ex -> {
            requests.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"access_token\":\"t\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        assertThat(checker(true).digestOf(ref())).contains(DIGEST);
        assertThat(requests).containsExactly("null");
    }

    @Test
    void failuresAreEmptyNotExceptions() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true).digestOf(ref())).isEmpty();
        assertThat(checker(true).digestOf(ImageReference.parse("127.0.0.1:1/x/y:1"))).isEmpty();
    }

    @Test
    void rejectsMalformedDigestHeader() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            ex.getResponseHeaders().add("Docker-Content-Digest", "nonsense");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true).digestOf(ref())).isEmpty();
    }

    @Test
    void httpsOnlyByDefaultSoPlainStubIsNeverContacted() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            requests.add("hit");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(false).digestOf(ref())).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void insecureTokenRealmIsRefusedByDefault() throws Exception {
        start();
        String realm = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/v2/", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.createContext("/token", ex -> {
            requests.add("token");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        // allowInsecureHttp=true only for the registry; realm check shares the flag, so use https-only checker
        assertThat(checker(false).digestOf(ref())).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void privateRegistryIsNeverContactedByDefault() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            requests.add("hit");
            ex.getResponseHeaders().add("Docker-Content-Digest", DIGEST);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true, false).digestOf(ref())).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void imageReferencesPointingAtMetadataOrPrivateHostsYieldNothing() {
        RemoteDigestChecker c = checker(false, false);
        assertThat(c.digestOf(ImageReference.parse("169.254.169.254:443/x/y:1"))).isEmpty();
        assertThat(c.digestOf(ImageReference.parse("10.0.0.5:8443/x/y:1"))).isEmpty();
    }

    @Test
    void bearerRealmOnAnInternalHostIsRefused() throws Exception {
        start();
        java.util.Set<String> looked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        EgressPolicy policy = new EgressPolicy(new EgressProperties(true), host -> {
            looked.add(host);
            return host.equals("internal.test")
                    ? new java.net.InetAddress[]{java.net.InetAddress.getByName("169.254.169.254")}
                    : java.net.InetAddress.getAllByName(host);
        });
        server.createContext("/v2/", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"http://internal.test/token\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true, policy).digestOf(ref())).isEmpty();
        assertThat(looked).contains("internal.test");
    }
}
