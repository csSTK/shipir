package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.dto.ProxySetupRequest;
import dev.bethge.shipir.proxy.dto.ProxyUpdateRequest;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Format checks of setup and update input, before anything is stored or written to a {@code .env}. Pure; every
 * violation is a 400 {@link ProxyException} with a stable code. Credential values are never part of a message.
 */
final class ProxySetupRules {

    static final int MAX_CREDENTIALS = 10;
    static final int MAX_CREDENTIAL_VALUE = 2000;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9][a-z0-9-]{1,39}");
    private static final Pattern CREDENTIAL_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private ProxySetupRules() {
    }

    static void validate(ProxySetupRequest r) {
        if (r.type() == null || r.defaultCertMode() == null) {
            throw bad("INVALID_REQUEST", "type and defaultCertMode are required");
        }
        validateEmail(r.type(), r.acmeEmail());
        validateMode(r.type(), r.defaultCertMode());
        validateDns(r.type(), r.dnsProvider(), r.dnsCredentials());
        if (r.defaultCertMode() == CertMode.ACME_DNS && (r.dnsProvider() == null || r.dnsCredentials() == null
                || r.dnsCredentials().isEmpty())) {
            throw bad("DNS_CREDENTIALS_REQUIRED", "The DNS challenge needs a DNS provider and its credentials");
        }
    }

    /** Format checks only; what the update means for the stored state is decided by the service. */
    static void validate(ProxyUpdateRequest r) {
        boolean replace = r.dnsCredentials() != null && !r.dnsCredentials().isEmpty();
        if (replace && Boolean.TRUE.equals(r.removeDnsCredentials())) {
            throw bad("INVALID_DNS_CREDENTIALS", "Credentials cannot be replaced and removed in one request");
        }
        if (r.defaultCertMode() == CertMode.CUSTOM) {
            throw bad("INVALID_CERT_MODE", "A custom certificate cannot be the default; choose it per route");
        }
        if (r.acmeEmail() != null && !r.acmeEmail().isBlank() && !EMAIL.matcher(r.acmeEmail().trim()).matches()) {
            throw bad("INVALID_EMAIL", "Not a valid e-mail address");
        }
        validateProvider(r.dnsProvider());
        validateCredentials(r.dnsCredentials());
    }

    static void validateEmail(ProxyType type, String email) {
        boolean blank = email == null || email.isBlank();
        if (blank && type == ProxyType.TRAEFIK) {
            throw bad("INVALID_EMAIL", "An e-mail address for Let's Encrypt is required");
        }
        if (!blank && !EMAIL.matcher(email.trim()).matches()) {
            throw bad("INVALID_EMAIL", "Not a valid e-mail address");
        }
    }

    static void validateMode(ProxyType type, CertMode mode) {
        if (mode == CertMode.CUSTOM) {
            throw bad("INVALID_CERT_MODE", "A custom certificate cannot be the default; choose it per route");
        }
        if (type == ProxyType.CADDY && mode == CertMode.ACME_DNS) {
            throw unsupported("The DNS challenge is only supported with Traefik");
        }
    }

    private static void validateDns(ProxyType type, String provider, Map<String, String> credentials) {
        boolean any = provider != null || (credentials != null && !credentials.isEmpty());
        if (any && type == ProxyType.CADDY) {
            throw unsupported("DNS provider credentials are only supported with Traefik");
        }
        validateProvider(provider);
        validateCredentials(credentials);
    }

    private static void validateProvider(String provider) {
        if (provider != null && !PROVIDER.matcher(provider).matches()) {
            throw bad("INVALID_DNS_PROVIDER", "The DNS provider is a short lower-case code such as cloudflare");
        }
    }

    static void validateCredentials(Map<String, String> credentials) {
        if (credentials == null) {
            return;
        }
        if (credentials.size() > MAX_CREDENTIALS) {
            throw bad("INVALID_DNS_CREDENTIALS", "At most " + MAX_CREDENTIALS + " credential variables are allowed");
        }
        for (Map.Entry<String, String> e : credentials.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || !CREDENTIAL_KEY.matcher(key).matches() || key.equals("ACME_EMAIL") || key.equals("DNS_PROVIDER")) {
                throw bad("INVALID_DNS_CREDENTIALS", "Credential names are upper-case environment variable names");
            }
            if (value == null || value.isEmpty() || value.length() > MAX_CREDENTIAL_VALUE || CONTROL.matcher(value).find()) {
                throw bad("INVALID_DNS_CREDENTIALS", "The value of " + key + " is empty, too long or has control characters");
            }
        }
    }

    private static ProxyException bad(String code, String message) {
        return new ProxyException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ProxyException unsupported(String message) {
        return new ProxyException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FOR_PROXY", message);
    }
}
