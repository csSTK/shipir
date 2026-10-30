package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.CertMode;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyInstance;
import dev.bethge.shipir.proxy.ProxyRoute;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Renders Traefik's dynamic file configuration ({@code managed.yml}). The user-editable {@code custom.yml} next to it is
 * never produced here. Every string is written as a double-quoted YAML scalar built by {@link #q}, so a value cannot
 * leave its scalar. Traefik runs Go templates over dynamic files, so an opening brace is written as the YAML escape
 * {@code \x7B}: a template action can never appear in the file.
 */
@Component
public class TraefikConfigRenderer implements ProxyConfigRenderer {

    static final String FILE = "managed.yml";
    private static final String REDIRECT = "redirect-https";

    @Override
    public ProxyType type() {
        return ProxyType.TRAEFIK;
    }

    @Override
    public List<RenderedFile> render(ProxyInstance instance, List<ProxyRoute> routes, Map<UUID, ProxyCertificate> certificates) {
        StringBuilder routers = new StringBuilder();
        StringBuilder services = new StringBuilder();
        StringBuilder middlewares = new StringBuilder();
        Set<UUID> customCertificates = new TreeSet<>();
        Set<String> usedIds = RenderValues.newIdSet();
        boolean redirect = false;

        for (ProxyRoute route : RenderValues.sorted(routes)) {
            String id = RenderValues.routeId(route, usedIds);
            List<String> names = names(route);
            String rule = rule(names, RenderValues.pathPrefix(route.getPathPrefix()));
            String entrypoint = route.getEntrypoint() == null ? "websecure" : RenderValues.entrypoint(route.getEntrypoint());
            Map<String, String> users = RenderValues.sorted(route.getBasicAuthUsers());
            Map<String, String> headers = RenderValues.sorted(route.getResponseHeaders());
            List<String> chain = new ArrayList<>();
            if (!users.isEmpty()) {
                chain.add(id + "--auth");
            }
            if (!headers.isEmpty()) {
                chain.add(id + "--headers");
            }

            routers.append("    ").append(q(id)).append(":\n");
            routers.append("      rule: ").append(q(rule)).append('\n');
            routers.append("      entryPoints: [").append(q(entrypoint)).append("]\n");
            appendChain(routers, chain);
            routers.append("      service: ").append(q(id)).append('\n');
            tls(routers, route, names, certificates, customCertificates);

            routers.append("    ").append(q(id + "--http")).append(":\n");
            routers.append("      rule: ").append(q(rule)).append('\n');
            routers.append("      entryPoints: [\"web\"]\n");
            if (route.isRedirectHttps()) {
                redirect = true;
                routers.append("      middlewares: [").append(q(REDIRECT)).append("]\n");
                routers.append("      service: \"noop@internal\"\n");
            } else {
                appendChain(routers, chain);
                routers.append("      service: ").append(q(id)).append('\n');
            }

            services.append("    ").append(q(id)).append(":\n");
            services.append("      loadBalancer:\n        servers:\n");
            services.append("          - url: ").append(q(route.getBackendScheme().value() + "://"
                    + RenderValues.container(route.getContainerName()) + ":" + route.getTargetPort())).append('\n');

            if (!users.isEmpty()) {
                middlewares.append("    ").append(q(id + "--auth")).append(":\n      basicAuth:\n        users:\n");
                for (Map.Entry<String, String> u : users.entrySet()) {
                    middlewares.append("          - ").append(q(RenderValues.user(u.getKey()) + ":"
                            + RenderValues.text(u.getValue(), "password hash"))).append('\n');
                }
            }
            if (!headers.isEmpty()) {
                middlewares.append("    ").append(q(id + "--headers"))
                        .append(":\n      headers:\n        customResponseHeaders:\n");
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    middlewares.append("          ").append(q(RenderValues.headerName(h.getKey()))).append(": ")
                            .append(q(RenderValues.text(h.getValue(), "header value"))).append('\n');
                }
            }
        }
        if (redirect) {
            middlewares.append("    ").append(q(REDIRECT)).append(":\n      redirectScheme:\n")
                    .append("        scheme: \"https\"\n        permanent: true\n");
        }

        StringBuilder out = new StringBuilder(RenderValues.HEADER_COMMENT).append(" (custom.yml)\n");
        if (routers.length() == 0) {
            out.append("http: {}\n");
        } else {
            out.append("http:\n  routers:\n").append(routers).append("  services:\n").append(services);
            if (middlewares.length() > 0) {
                out.append("  middlewares:\n").append(middlewares);
            }
        }
        if (!customCertificates.isEmpty()) {
            out.append("tls:\n  certificates:\n");
            for (UUID cert : customCertificates) {
                out.append("    - certFile: ").append(q(ProxyConfigRenderer.certFile(cert))).append('\n');
                out.append("      keyFile: ").append(q(ProxyConfigRenderer.keyFile(cert))).append('\n');
            }
        }
        return List.of(new RenderedFile(FILE, out.toString()));
    }

    private static void appendChain(StringBuilder out, List<String> chain) {
        if (!chain.isEmpty()) {
            out.append("      middlewares: [")
                    .append(chain.stream().map(TraefikConfigRenderer::q).collect(Collectors.joining(", ")))
                    .append("]\n");
        }
    }

    private static List<String> names(ProxyRoute route) {
        List<String> names = new ArrayList<>();
        for (String n : route.allNames()) {
            names.add(RenderValues.name(n));
        }
        return names;
    }

    private static void tls(StringBuilder out, ProxyRoute route, List<String> names,
                            Map<UUID, ProxyCertificate> certificates, Set<UUID> customCertificates) {
        CertMode mode = route.getCertMode();
        switch (mode) {
            case ACME_HTTP -> out.append("      tls:\n        certResolver: \"letsencrypt-http\"\n");
            case ACME_DNS -> {
                out.append("      tls:\n        certResolver: \"letsencrypt-dns\"\n");
                if (names.stream().anyMatch(n -> n.startsWith("*."))) {
                    out.append("        domains:\n          - main: ").append(q(names.get(0))).append('\n');
                    if (names.size() > 1) {
                        out.append("            sans: [")
                                .append(names.subList(1, names.size()).stream().map(TraefikConfigRenderer::q)
                                        .collect(Collectors.joining(", ")))
                                .append("]\n");
                    }
                }
            }
            case INTERNAL -> out.append("      tls: {}\n");
            case CUSTOM -> {
                customCertificates.add(RenderValues.certificateId(route, certificates));
                out.append("      tls: {}\n");
            }
        }
    }

    /** Host rules per name (wildcards as an anchored regexp over one label), joined, plus the path prefix. */
    private static String rule(List<String> names, String pathPrefix) {
        List<String> hosts = new ArrayList<>();
        for (String n : names) {
            if (n.startsWith("*.")) {
                hosts.add("HostRegexp(`^[a-z0-9-]+" + n.substring(1).replace(".", "\\.") + "$`)");
            } else {
                hosts.add("Host(`" + n + "`)");
            }
        }
        String host = String.join(" || ", hosts);
        if (pathPrefix == null) {
            return host;
        }
        return (hosts.size() > 1 ? "(" + host + ")" : host) + " && PathPrefix(`" + pathPrefix + "`)";
    }

    /** Double-quoted YAML scalar: backslash, quote and '{' escaped, control and non-ASCII characters rejected. */
    static String q(String value) {
        RenderValues.text(value, "value");
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '{' -> sb.append("\\x7B");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
