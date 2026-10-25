package dev.bethge.shipir.auth.oidc;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SSRF protection of the OIDC sinks with the egress policy at its production default (private ranges denied). */
class OidcEgressTest {

    private static StubOidcProvider provider;
    private HttpServer redirector;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startProvider() throws Exception {
        provider = new StubOidcProvider();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @BeforeEach
    void start() throws Exception {
        provider.reset();
        redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", ex -> {
            hits.add(ex.getRequestURI().getPath());
            ex.getResponseHeaders().add("Location", provider.issuer() + "/.well-known/openid-configuration");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        redirector.start();
    }

    @AfterEach
    void stop() {
        redirector.stop(0);
    }

    /** internal.test resolves to the cloud metadata address; everything else resolves normally. */
    private OidcDiscoveryClient client(boolean allowPrivate) {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(allowPrivate), host ->
                host.equals("internal.test") ? new InetAddress[]{InetAddress.getByName("169.254.169.254")}
                        : InetAddress.getAllByName(host));
        return new OidcDiscoveryClient(
                new OidcProperties(true, true, "", Duration.ofSeconds(2)), JsonMapper.builder().build(),
                policy, new EgressHttpClient(policy, Duration.ofSeconds(2)));
    }

    private static void assertUnreachable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OidcException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_UNREACHABLE"));
    }

    @Test
    void discoveryOnLoopbackIsDeniedByDefaultAndLooksUnreachable() {
        assertUnreachable(() -> client(false).fetch(provider.issuer()));
    }

    @Test
    void discoveryOnLoopbackWorksWhenPrivateRangesAreAllowed() {
        assertThat(client(true).fetch(provider.issuer()).tokenEndpoint()).isEqualTo(provider.issuer() + "/token");
    }

    @Test
    void metadataHostIsDeniedEvenWhenPrivateRangesAreAllowed() {
        assertUnreachable(() -> client(true).fetch("http://internal.test"));
        assertUnreachable(() -> client(true).fetch("http://169.254.169.254"));
    }

    @Test
    void tokenEndpointFromTheDiscoveryDocumentIsChecked() {
        provider.advertiseTokenEndpoint("http://internal.test/token");
        assertUnreachable(() -> client(true).fetch(provider.issuer()));
    }

    @Test
    void jwksUriFromTheDiscoveryDocumentIsChecked() {
        provider.advertiseJwksUri("http://internal.test/jwks");
        assertUnreachable(() -> client(true).fetch(provider.issuer()));
    }

    @Test
    void discoveryRedirectIsNotFollowed() {
        String port = String.valueOf(redirector.getAddress().getPort());
        assertUnreachable(() -> client(true).fetch("http://127.0.0.1:" + port));
        assertThat(hits).containsExactly("/.well-known/openid-configuration");
    }

    @Test
    void tokenAndJwksRequestFactoryDeniesInternalTargetsByDefault() {
        OidcDiscoveryClient denying = client(false);
        for (String target : List.of(provider.issuer() + "/token", "http://169.254.169.254/latest/meta-data",
                "http://internal.test/jwks")) {
            assertThatThrownBy(() -> {
                ClientHttpRequest request = denying.requestFactory().createRequest(URI.create(target), HttpMethod.POST);
                request.execute().close();
            }).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void tokenAndJwksRequestFactoryDoesNotFollowRedirects() throws Exception {
        OidcDiscoveryClient allowing = client(true);
        int status;
        try (var response = allowing.requestFactory().createRequest(
                URI.create("http://127.0.0.1:" + redirector.getAddress().getPort() + "/jwks"), HttpMethod.GET).execute()) {
            status = response.getStatusCode().value();
        }
        assertThat(status).isEqualTo(302);
        assertThat(hits).containsExactly("/jwks");
    }
}
