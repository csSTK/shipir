package dev.bethge.shipir.proxy.status;

import java.time.Instant;
import java.util.List;

/** What the status needs from one issued certificate: the names it covers and when it ends. Never any key material. */
record IssuedCert(List<String> domains, Instant notAfter) {
}
