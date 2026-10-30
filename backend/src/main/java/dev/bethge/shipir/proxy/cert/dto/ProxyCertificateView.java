package dev.bethge.shipir.proxy.cert.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An uploaded certificate as the API shows it: never the key, never the PEM. {@code usedByRoutes} = hostnames of the routes using it. */
public record ProxyCertificateView(UUID id, String name, List<String> domains, Instant notAfter,
                                   List<String> usedByRoutes) {
}
