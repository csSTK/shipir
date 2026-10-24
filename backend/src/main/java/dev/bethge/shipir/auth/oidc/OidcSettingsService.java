package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.LocalLoginPolicy;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.oidc.dto.OidcPublicConfigResponse;
import dev.bethge.shipir.auth.oidc.dto.OidcSettingsRequest;
import dev.bethge.shipir.auth.oidc.dto.OidcSettingsResponse;
import dev.bethge.shipir.auth.oidc.dto.OidcTestResponse;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The singleton OIDC configuration: ADMIN read/update/test, the public flags for the login page, the decrypted
 * configuration for a sign-in, and the local-login rule. OIDC counts as enabled when issuer, client id and a
 * client secret are stored.
 */
@Service
@EnableConfigurationProperties(OidcProperties.class)
class OidcSettingsService implements LocalLoginPolicy {

    static final String DEFAULT_SCOPES = "openid email profile";

    private final OidcSettingsRepository settings;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final OidcDiscoveryClient discovery;
    private final OidcProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;

    OidcSettingsService(OidcSettingsRepository settings, UserRepository users, SecretCipher cipher,
                        OidcDiscoveryClient discovery, OidcProperties properties, JsonMapper mapper, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.cipher = cipher;
        this.discovery = discovery;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    OidcSettingsResponse get() {
        return toResponse(load());
    }

    @Auditable(action = "OIDC_SETTINGS_UPDATE", targetType = "OIDC_SETTINGS", targetId = "'1'")
    @Transactional
    OidcSettingsResponse update(OidcSettingsRequest request, String username) {
        OidcSettings s = load();
        String issuer = blankToNull(request.issuerUrl());
        String clientId = blankToNull(request.clientId());
        if (issuer != null) {
            validateIssuer(issuer);
            if (clientId == null) {
                throw new OidcException(HttpStatus.BAD_REQUEST, "CLIENT_ID_REQUIRED", "A client id is required");
            }
        }
        String scopes = blankToNull(request.scopes()) == null ? DEFAULT_SCOPES : request.scopes().trim();
        if (!java.util.Arrays.asList(scopes.split("\\s+")).contains("openid")) {
            throw new OidcException(HttpStatus.BAD_REQUEST, "INVALID_SCOPES", "The scopes must include openid");
        }
        Map<Role, List<String>> mapping = RoleMapping.validate(request.roleMapping());
        if (request.clientSecret() != null && !request.clientSecret().isBlank()) {
            if (!cipher.isConfigured()) {
                throw new OidcException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                        "The client secret needs shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to be stored");
            }
            s.replaceSecret(cipher.encrypt(request.clientSecret()), hint(request.clientSecret()));
        } else if (request.clientSecret() != null) {
            s.clearSecret();
        }
        UUID by = users.findByUsername(username).map(User::getId).orElse(null);
        s.update(issuer, clientId, scopes, blankToNull(request.groupsClaim()), RoleMapping.toJson(mapper, mapping),
                request.autoProvision(), request.localLoginEnabled(), clock.instant(), by);
        if (!s.isLocalLoginEnabled() && !s.isEnabled()) {
            throw new OidcException(HttpStatus.BAD_REQUEST, "LOCAL_LOGIN_REQUIRED",
                    "Password login can only be switched off while single sign-on is fully configured");
        }
        settings.saveAndFlush(s);
        return toResponse(s);
    }

    /** Not transactional: the network call must not hold a database connection. */
    OidcTestResponse test() {
        String issuer = activeIssuer().orElseThrow(() -> new OidcException(HttpStatus.BAD_REQUEST,
                "OIDC_NOT_CONFIGURED", "Save the provider settings before testing"));
        try {
            discovery.fetch(issuer);
            return new OidcTestResponse("OK");
        } catch (OidcException e) {
            return new OidcTestResponse("PROVIDER_INVALID".equals(e.code()) ? "INVALID" : "UNREACHABLE");
        }
    }

    @Transactional(readOnly = true)
    OidcPublicConfigResponse publicConfig() {
        OidcSettings s = load();
        if (!s.isEnabled()) {
            return new OidcPublicConfigResponse(false, null, s.isLocalLoginEnabled());
        }
        return new OidcPublicConfigResponse(true, hostOf(s.getIssuerUrl()), s.isLocalLoginEnabled());
    }

    /** Decrypted configuration for a sign-in; empty when single sign-on is not (fully) configured. */
    @Transactional(readOnly = true)
    Optional<OidcConfig> activeConfig() {
        OidcSettings s = load();
        if (!s.isEnabled()) {
            return Optional.empty();
        }
        String secret;
        try {
            secret = cipher.decrypt(s.getClientSecretCiphertext());
        } catch (EncryptionKeyMissingException e) {
            throw new OidcException("ENCRYPTION_KEY_MISSING", "The client secret cannot be decrypted");
        }
        return Optional.of(new OidcConfig(s.getIssuerUrl(), s.getClientId(), secret, s.getScopes(),
                s.getGroupsClaim(), RoleMapping.parse(mapper, s.getRoleMapping()), s.isAutoProvision()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allows(Role role) {
        if (load().isLocalLoginEnabled()) {
            return true;
        }
        return properties.breakGlassLocalAdmin() && role == Role.ADMIN;
    }

    @Transactional(readOnly = true)
    Optional<String> activeIssuer() {
        OidcSettings s = load();
        return s.isEnabled() ? Optional.of(s.getIssuerUrl()) : Optional.empty();
    }

    private OidcSettings load() {
        return settings.findById(OidcSettings.SINGLETON_ID).orElseThrow();
    }

    private void validateIssuer(String issuer) {
        try {
            URI uri = new URI(issuer);
            boolean schemeOk = "https".equalsIgnoreCase(uri.getScheme())
                    || (properties.allowInsecureHttp() && "http".equalsIgnoreCase(uri.getScheme()));
            if (schemeOk && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null) {
                return;
            }
        } catch (URISyntaxException e) {
            // fall through
        }
        throw new OidcException(HttpStatus.BAD_REQUEST, "INVALID_ISSUER_URL",
                "The issuer URL must be an https URL without credentials, query or fragment");
    }

    private static String hostOf(String issuer) {
        try {
            return new URI(issuer).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Masked display value: the last four characters of a long secret, otherwise only dots. */
    static String hint(String secret) {
        String t = secret.trim();
        return t.length() >= 12 ? "••••" + t.substring(t.length() - 4) : "••••";
    }

    private OidcSettingsResponse toResponse(OidcSettings s) {
        return new OidcSettingsResponse(s.getIssuerUrl(), s.getClientId(), s.getClientSecretCiphertext() != null,
                s.getClientSecretHint(), s.getScopes(), s.getGroupsClaim(),
                RoleMapping.parse(mapper, s.getRoleMapping()), s.isAutoProvision(), s.isLocalLoginEnabled(),
                s.isEnabled());
    }
}
