package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.BackendScheme;
import dev.bethge.shipir.proxy.CertMode;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyInstance;
import dev.bethge.shipir.proxy.ProxyRoute;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builders for renderer tests; ids of certificates are set by reflection because the database generates them. */
final class RenderFixtures {

    static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID CERT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final String HASH = "$2a$10$" + "a".repeat(53);

    private RenderFixtures() {
    }

    static ProxyInstance instance(ProxyType type, String email) {
        return ProxyInstance.create(HOST, type, email, CertMode.ACME_HTTP);
    }

    static ProxyRoute route(String hostname, String container, int port, CertMode mode) {
        return full(hostname, List.of(), container, port, BackendScheme.HTTP, null, null, mode, null, true, null, null);
    }

    static ProxyRoute full(String hostname, List<String> aliases, String container, int port, BackendScheme scheme,
                           String path, String entrypoint, CertMode mode, UUID certId, boolean redirect,
                           Map<String, String> users, Map<String, String> headers) {
        return ProxyRoute.create(HOST, hostname, aliases, container, port, scheme, path, entrypoint, mode, certId,
                redirect, users, headers);
    }

    static ProxyCertificate certificate() {
        ProxyCertificate c = ProxyCertificate.create(HOST, "wild", "PEM", new byte[]{1},
                List.of("*.example.com"), Instant.parse("2030-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(c, "id", CERT_ID);
        return c;
    }

    static String golden(String name) {
        try (InputStream in = RenderFixtures.class.getResourceAsStream("/proxy/render/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden file " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@HASH@", HASH);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
