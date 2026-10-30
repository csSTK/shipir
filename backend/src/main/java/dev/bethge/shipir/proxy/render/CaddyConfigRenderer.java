package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.BackendScheme;
import dev.bethge.shipir.proxy.CertMode;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyInstance;
import dev.bethge.shipir.proxy.ProxyRoute;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders the managed Caddyfile. Host names and container names are written bare after a strict charset check; every
 * other user value is a double-quoted Caddyfile token built by {@link #q}, with quote and opening brace escaped (a
 * brace would start a placeholder), backslashes and {$ rejected. Control characters are rejected, so a value never
 * adds a line. The user-editable {@code custom.caddy} is imported at the end and never produced here.
 */
@Component
public class CaddyConfigRenderer implements ProxyConfigRenderer {

    static final String FILE = "Caddyfile";

    @Override
    public ProxyType type() {
        return ProxyType.CADDY;
    }

    @Override
    public List<RenderedFile> render(ProxyInstance instance, List<ProxyRoute> routes, Map<UUID, ProxyCertificate> certificates) {
        StringBuilder out = new StringBuilder(RenderValues.HEADER_COMMENT).append(" (custom.caddy)\n");
        out.append("{\n\tadmin localhost:2019\n");
        if (instance.getAcmeEmail() != null && !instance.getAcmeEmail().isBlank()) {
            out.append("\temail ").append(q(instance.getAcmeEmail())).append('\n');
        }
        out.append("}\n\n");

        for (ProxyRoute route : RenderValues.sorted(routes)) {
            site(out, route, certificates);
            out.append('\n');
        }
        out.append("import custom.caddy\n");
        return List.of(new RenderedFile(FILE, out.toString()));
    }

    private static void site(StringBuilder out, ProxyRoute route, Map<UUID, ProxyCertificate> certificates) {
        if (route.getCertMode() == CertMode.ACME_DNS) {
            throw new IllegalArgumentException("The DNS challenge is not supported with Caddy");
        }
        if (route.getEntrypoint() != null) {
            throw new IllegalArgumentException("Entrypoints do not exist in Caddy");
        }
        List<String> addresses = new ArrayList<>();
        for (String name : route.allNames()) {
            RenderValues.name(name);
            if (route.isRedirectHttps()) {
                addresses.add(name);
            } else {
                addresses.add("http://" + name);
                addresses.add("https://" + name);
            }
        }
        out.append(String.join(", ", addresses)).append(" {\n");

        switch (route.getCertMode()) {
            case INTERNAL -> out.append("\ttls internal\n");
            case CUSTOM -> {
                UUID cert = RenderValues.certificateId(route, certificates);
                out.append("\ttls ").append(ProxyConfigRenderer.certFile(cert)).append(' ')
                        .append(ProxyConfigRenderer.keyFile(cert)).append('\n');
            }
            default -> {
            }
        }
        for (Map.Entry<String, String> h : RenderValues.sorted(route.getResponseHeaders()).entrySet()) {
            out.append("\theader ").append(q(RenderValues.headerName(h.getKey()))).append(' ')
                    .append(q(h.getValue())).append('\n');
        }
        Map<String, String> users = RenderValues.sorted(route.getBasicAuthUsers());
        if (!users.isEmpty()) {
            out.append("\tbasic_auth {\n");
            for (Map.Entry<String, String> u : users.entrySet()) {
                out.append("\t\t").append(q(RenderValues.user(u.getKey()))).append(' ').append(q(u.getValue())).append('\n');
            }
            out.append("\t}\n");
        }

        String upstream = (route.getBackendScheme() == BackendScheme.HTTP ? "" : route.getBackendScheme().value() + "://")
                + RenderValues.container(route.getContainerName()) + ":" + route.getTargetPort();
        String prefix = RenderValues.pathPrefix(route.getPathPrefix());
        if (prefix == null) {
            out.append("\treverse_proxy ").append(upstream).append('\n');
        } else {
            out.append("\thandle_path ").append(prefix).append("/* {\n");
            out.append("\t\treverse_proxy ").append(upstream).append('\n');
            out.append("\t}\n");
        }
        out.append("}\n");
    }

    /**
     * Double-quoted Caddyfile token. Verified against {@code caddy adapt} (2.10.2): inside quotes only {@code \"} is
     * unescaped, any other backslash stays literal, so a backslash is rejected (a trailing one would swallow the closing
     * quote). {@code {$NAME}} is substituted at adapt time even after a backslash, so it is rejected too. A remaining
     * {@code {} is written as {@code \{}, which Caddy's runtime placeholder replacer treats as a literal brace.
     */
    static String q(String value) {
        RenderValues.text(value, "value");
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Backslashes cannot be represented in a Caddyfile value");
        }
        if (value.contains("{$")) {
            throw new IllegalArgumentException("Environment substitution is not allowed in a Caddyfile value");
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '{') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}
