package dev.bethge.shipir.ai.settings;

import dev.bethge.shipir.ai.settings.dto.AiConnectionTestResponse;
import dev.bethge.shipir.ai.settings.dto.AiSettingsRequest;
import dev.bethge.shipir.ai.settings.dto.AiSettingsResponse;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
@EnableConfigurationProperties(AiSettingsProperties.class)
public class AiSettingsService {

    private final AiSettingsRepository settings;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final AiConnectionChecker checker;
    private final AiSettingsProperties properties;
    private final Clock clock;

    AiSettingsService(AiSettingsRepository settings, UserRepository users, SecretCipher cipher,
                      AiConnectionChecker checker, AiSettingsProperties properties, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.cipher = cipher;
        this.checker = checker;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    AiSettingsResponse get() {
        return toResponse(load());
    }

    @Auditable(action = "AI_SETTINGS_UPDATE", targetType = "AI_SETTINGS", targetId = "'1'")
    @Transactional
    AiSettingsResponse update(AiSettingsRequest request, String username) {
        validateUrl(request.baseUrl());
        AiSettings s = load();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            requireKey();
            s.replaceKey(cipher.encrypt(request.apiKey()), hint(request.apiKey()));
        } else if (request.apiKey() != null && request.apiKey().isEmpty()) {
            s.clearKey();
        }
        UUID by = users.findByUsername(username).map(User::getId).orElse(null);
        s.update(request.baseUrl().trim(), request.model().trim(), request.enabled(), clock.instant(), by);
        return toResponse(settings.saveAndFlush(s));
    }

    /** Not transactional: the network call must not hold a database connection. */
    AiConnectionTestResponse test() {
        AiSettings s = load();
        if (!s.hasKey()) {
            throw new AiSettingsException(HttpStatus.BAD_REQUEST, "API_KEY_REQUIRED",
                    "Save an API key before testing the connection");
        }
        requireKey();
        validateUrl(s.getBaseUrl());
        return new AiConnectionTestResponse(checker.check(s.getBaseUrl(), cipher.decrypt(s.getApiKeyCiphertext())));
    }

    /**
     * The decrypted configuration for the chat orchestration, or empty when the assistant is not usable:
     * disabled, no key stored, or the encryption key is not configured.
     */
    @Transactional(readOnly = true)
    public Optional<AiConfig> activeConfig() {
        AiSettings s = load();
        if (!s.isEnabled() || !s.hasKey() || !cipher.isConfigured()) {
            return Optional.empty();
        }
        return Optional.of(new AiConfig(s.getBaseUrl(), s.getModel(), cipher.decrypt(s.getApiKeyCiphertext())));
    }

    private AiSettings load() {
        return settings.findById(AiSettings.SINGLETON_ID).orElseThrow();
    }

    private void requireKey() {
        if (!cipher.isConfigured()) {
            throw new AiSettingsException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "The AI API key needs shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to be stored");
        }
    }

    private void validateUrl(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw invalidUrl();
        }
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme)
                || (properties.allowInsecureHttp() && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw invalidUrl();
        }
    }

    private static AiSettingsException invalidUrl() {
        return new AiSettingsException(HttpStatus.BAD_REQUEST, "INVALID_BASE_URL",
                "The base URL must be an https URL without credentials");
    }

    /** Masked display value: the last four characters of a long key, otherwise only dots. */
    static String hint(String key) {
        String k = key.trim();
        return k.length() >= 12 ? "••••" + k.substring(k.length() - 4) : "••••";
    }

    private static AiSettingsResponse toResponse(AiSettings s) {
        return new AiSettingsResponse(s.getBaseUrl(), s.getModel(), s.isEnabled(), s.hasKey(), s.getApiKeyHint());
    }
}
