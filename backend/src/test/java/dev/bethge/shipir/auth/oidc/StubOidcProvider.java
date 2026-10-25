package dev.bethge.shipir.auth.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal OIDC provider on a JDK HttpServer: discovery, JWKS and a token endpoint that checks client
 * authentication (Basic), the PKCE verifier and the redirect URI, and signs ID tokens with RS256.
 */
final class StubOidcProvider implements AutoCloseable {

    static final String CLIENT_ID = "shipir-client";
    static final String CLIENT_SECRET = "stub-client-secret-VALUE-12345";

    private final HttpServer server;
    private final RSAKey key;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private volatile boolean discoveryBroken;
    private volatile String issuerOverride;
    private volatile String audienceOverride;
    private volatile List<String> audiencesOverride;
    private volatile boolean omitExp;
    private volatile boolean omitIat;
    private volatile long expiresInMillis = 300_000;
    private volatile boolean signWithForeignKey;
    private volatile String discoveryIssuer;
    private volatile String tokenEndpointOverride;
    private volatile String jwksUriOverride;
    private final RSAKey foreignKey;

    /** What the token endpoint hands out for one authorization code. */
    record Grant(String codeChallenge, String nonce, Map<String, Object> claims) {
    }

    StubOidcProvider() throws Exception {
        foreignKey = new RSAKeyGenerator(2048).keyID("stub-key").generate();
        key = new RSAKeyGenerator(2048).keyID("stub-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/jwks", ex -> respond(ex, 200, "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}"));
        server.createContext("/token", this::token);
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void breakDiscovery() {
        discoveryBroken = true;
    }

    void signWithIssuer(String issuer) {
        issuerOverride = issuer;
    }

    void signWithAudience(String audience) {
        audienceOverride = audience;
    }

    void omitExp() {
        omitExp = true;
    }

    void omitIat() {
        omitIat = true;
    }

    void expiresIn(long millis) {
        expiresInMillis = millis;
    }

    /** Signs with a key that is not in the JWKS (same key id). */
    void signWithForeignKey() {
        signWithForeignKey = true;
    }

    void advertiseTokenEndpoint(String url) {
        tokenEndpointOverride = url;
    }

    void advertiseJwksUri(String url) {
        jwksUriOverride = url;
    }

    void advertiseIssuer(String issuer) {
        discoveryIssuer = issuer;
    }

    void audiences(List<String> audiences) {
        audiencesOverride = audiences;
    }

    void reset() {
        audiencesOverride = null;
        omitExp = false;
        omitIat = false;
        expiresInMillis = 300_000;
        signWithForeignKey = false;
        discoveryIssuer = null;
        tokenEndpointOverride = null;
        jwksUriOverride = null;
        grants.clear();
        discoveryBroken = false;
        issuerOverride = null;
        audienceOverride = null;
    }

    /** Registers a code the browser "got" for the given authorization request; claims become the ID token body. */
    void grant(String code, String codeChallenge, String nonceInToken, Map<String, Object> claims) {
        grants.put(code, new Grant(codeChallenge, nonceInToken, claims));
    }

    private void discovery(HttpExchange ex) throws IOException {
        if (discoveryBroken) {
            respond(ex, 500, "{}");
            return;
        }
        String i = issuer();
        respond(ex, 200, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s","jwks_uri":"%s"}
                """.formatted(discoveryIssuer != null ? discoveryIssuer : i, i,
                tokenEndpointOverride != null ? tokenEndpointOverride : i + "/token",
                jwksUriOverride != null ? jwksUriOverride : i + "/jwks"));
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expectedBasic.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            respond(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        Grant grant = grants.remove(form.get("code"));
        if (grant == null || !"authorization_code".equals(form.get("grant_type")) || form.get("redirect_uri") == null
                || form.get("code_verifier") == null || !s256(form.get("code_verifier")).equals(grant.codeChallenge())) {
            respond(ex, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        try {
            String idToken = sign(grant);
            respond(ex, 200, "{\"access_token\":\"stub-access\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                    + "\"id_token\":\"" + idToken + "\"}");
        } catch (Exception e) {
            respond(ex, 500, "{}");
        }
    }

    private String sign(Grant grant) throws Exception {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(issuerOverride != null ? issuerOverride : issuer())
                .audience(audiencesOverride != null ? audiencesOverride
                        : List.of(audienceOverride != null ? audienceOverride : CLIENT_ID));
        if (!omitIat) {
            b.issueTime(new Date());
        }
        if (!omitExp) {
            b.expirationTime(new Date(System.currentTimeMillis() + expiresInMillis));
        }
        if (grant.nonce() != null) {
            b.claim("nonce", grant.nonce());
        }
        new HashMap<>(grant.claims()).forEach(b::claim);
        RSAKey signer = signWithForeignKey ? foreignKey : key;
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), b.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static List<String> list(String... v) {
        return List.of(v);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
