package dev.bethge.shipir.proxy.dto;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.CertMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Setup body. {@code dnsProvider} is the lego provider code (e.g. {@code cloudflare}), {@code dnsCredentials} the
 * environment variables of that provider (Traefik only). {@code toString} hides the credentials.
 */
public record ProxySetupRequest(
        @NotNull ProxyType type,
        @Size(max = 254) String acmeEmail,
        @NotNull CertMode defaultCertMode,
        @Size(max = 40) String dnsProvider,
        @Size(max = 10) Map<@Size(max = 64) String, @Size(max = 2000) String> dnsCredentials) {

    @Override
    public String toString() {
        return "ProxySetupRequest[type=" + type + ", defaultCertMode=" + defaultCertMode + "]";
    }
}
