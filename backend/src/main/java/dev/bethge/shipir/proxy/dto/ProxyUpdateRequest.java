package dev.bethge.shipir.proxy.dto;

import dev.bethge.shipir.proxy.CertMode;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Update body; a null field keeps the current value. {@code dnsCredentials} null or empty keeps the stored
 * credentials, a non-empty map replaces all of them, {@code removeDnsCredentials=true} deletes them. {@code toString}
 * hides the credentials.
 */
public record ProxyUpdateRequest(
        @Size(max = 254) String acmeEmail,
        CertMode defaultCertMode,
        @Size(max = 40) String dnsProvider,
        @Size(max = 10) Map<@Size(max = 64) String, @Size(max = 2000) String> dnsCredentials,
        Boolean removeDnsCredentials) {

    @Override
    public String toString() {
        return "ProxyUpdateRequest[defaultCertMode=" + defaultCertMode + "]";
    }
}
