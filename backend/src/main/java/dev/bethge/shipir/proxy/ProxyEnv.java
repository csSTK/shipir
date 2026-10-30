package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.generator.model.ProxyType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code .env} of the proxy deployment and the stored form of the DNS credentials. Credentials are kept as
 * {@code KEY=value} lines inside one {@code SecretCipher} blob (values never contain line breaks, see
 * {@link ProxySetupRules}); the hint shows variable names and the tail of the first long value, never a full secret.
 */
final class ProxyEnv {

    /** lego's manual provider: a valid placeholder, since the Traefik template requires DNS_PROVIDER to be set. */
    static final String PLACEHOLDER_PROVIDER = "manual";

    private ProxyEnv() {
    }

    static List<EnvEntry> entries(ProxyType type, String acmeEmail, String dnsProvider, Map<String, String> credentials) {
        List<EnvEntry> env = new ArrayList<>();
        if (acmeEmail != null && !acmeEmail.isBlank()) {
            env.add(new EnvEntry("ACME_EMAIL", acmeEmail));
        }
        if (type == ProxyType.TRAEFIK) {
            env.add(new EnvEntry("DNS_PROVIDER", dnsProvider == null ? PLACEHOLDER_PROVIDER : dnsProvider));
            new TreeMap<>(credentials).forEach((k, v) -> env.add(new EnvEntry(k, v)));
        }
        return env;
    }

    static String serialize(Map<String, String> credentials) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(credentials).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return out;
    }

    static String hint(Map<String, String> credentials) {
        if (credentials.isEmpty()) {
            return null;
        }
        Map<String, String> sorted = new TreeMap<>(credentials);
        String first = sorted.values().iterator().next();
        String names = String.join(", ", sorted.keySet());
        String tail = first.length() >= 12 ? " (••••" + first.substring(first.length() - 4) + ")" : "";
        String hint = names + tail;
        return hint.length() <= 255 ? hint : hint.substring(0, 255);
    }
}
