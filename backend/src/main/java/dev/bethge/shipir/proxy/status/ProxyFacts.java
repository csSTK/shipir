package dev.bethge.shipir.proxy.status;

import java.util.List;

/**
 * Everything read from the proxy for one host; host-wide, never filtered by user (filtering happens per request).
 * {@code degraded} marks a partial result (a store was missing or unreadable, or the log could not be read in full);
 * the cache keeps such facts only for a short time.
 */
record ProxyFacts(List<IssuedCert> acmeHttp, List<IssuedCert> acmeDns, List<IssuedCert> caddy, List<String> logLines,
                  boolean degraded) {

    static final ProxyFacts EMPTY = new ProxyFacts(List.of(), List.of(), List.of(), List.of(), true);

    ProxyFacts(List<IssuedCert> acmeHttp, List<IssuedCert> acmeDns, List<IssuedCert> caddy, List<String> logLines) {
        this(acmeHttp, acmeDns, caddy, logLines, false);
    }
}
