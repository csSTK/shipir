package dev.bethge.shipir.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Client address for throttling. {@code X-Forwarded-For} is only read when the direct peer is a configured
 * trusted proxy; then the right-most entry that is not itself a trusted proxy wins, so a client cannot
 * pick its own address by prepending values.
 */
@Component
@EnableConfigurationProperties(AuthThrottleProperties.class)
class ClientAddressResolver {

    private final Set<String> trustedProxies;

    ClientAddressResolver(AuthThrottleProperties properties) {
        this.trustedProxies = Set.copyOf(properties.trustedProxies());
    }

    String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null) {
            return "unknown";
        }
        if (!trustedProxies.contains(peer)) {
            return peer;
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return peer;
        }
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return peer;
    }
}
