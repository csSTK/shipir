package dev.bethge.shipir.ai.settings;

import dev.bethge.shipir.ai.settings.dto.AiConnectionTestResponse;
import dev.bethge.shipir.ai.settings.dto.AiSettingsRequest;
import dev.bethge.shipir.ai.settings.dto.AiSettingsResponse;
import dev.bethge.shipir.ai.settings.dto.AiSettingsResponse.ProviderSettings;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@EnableConfigurationProperties(AiSettingsProperties.class)
public class AiSettingsService {

    private final AiSettingsRepository settings;
    private final AiProviderConfigRepository configs;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final AiConnectionChecker checker;
    private final AiSettingsProperties properties;
    private final Clock clock;

    AiSettingsService(AiSettingsRepository settings, AiProviderConfigRepository configs, UserRepository users, SecretCipher cipher,
                      AiConnectionChecker checker, AiSettingsProperties properties, Clock clock) {
        this.settings = settings;
        this.configs = configs;
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
        AiProvider target = request.provider() != null ? request.provider() : request.activeProvider();
        AiProviderConfig c = loadConfig(target);
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            requireKey();
            c.replaceKey(cipher.encrypt(request.apiKey()), hint(request.apiKey()));
        } else if (request.apiKey() != null && request.apiKey().isEmpty()) {
            c.clearKey();
        }
        UUID by = users.findByUsername(username).map(User::getId).orElse(null);
        c.update(request.baseUrl().trim(), request.model().trim(), clock.instant(), by);
        configs.save(c);
        AiSettings s = load();
        s.update(request.activeProvider(), request.enabled(), clock.instant(), by);
        settings.saveAndFlush(s);
        return toResponse(s);
    }

    /** Not transactional: the network call must not hold a database connection. */
    AiConnectionTestResponse test(AiProvider requested) {
        AiProvider provider = requested != null ? requested : load().getActiveProvider();
        AiProviderConfig c = loadConfig(provider);
        if (!c.hasKey()) {
            throw new AiSettingsException(HttpStatus.BAD_REQUEST, "API_KEY_REQUIRED",
                    "Save an API key before testing the connection");
        }
        requireKey();
        validateUrl(c.getBaseUrl());
        return new AiConnectionTestResponse(checker.check(provider, c.getBaseUrl(), cipher.decrypt(c.getApiKeyCiphertext())));
    }

    /**
     * The decrypted configuration of the active provider for the chat orchestration, or empty when the assistant is
     * not usable: disabled, no key stored for the active provider, or the encryption key is not configured.
     */
    @Transactional(readOnly = true)
    public Optional<AiConfig> activeConfig() {
        AiSettings s = load();
        if (!s.isEnabled() || !cipher.isConfigured()) {
            return Optional.empty();
        }
        AiProviderConfig c = loadConfig(s.getActiveProvider());
        if (!c.hasKey()) {
            return Optional.empty();
        }
        return Optional.of(new AiConfig(c.getProvider(), c.getBaseUrl(), c.getModel(),
                cipher.decrypt(c.getApiKeyCiphertext())));
    }

    private AiSettings load() {
        return settings.findById(AiSettings.SINGLETON_ID).orElseThrow();
    }

    private AiProviderConfig loadConfig(AiProvider provider) {
        return configs.findById(provider).orElseThrow();
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

    private AiSettingsResponse toResponse(AiSettings s) {
        List<ProviderSettings> providers = configs.findAll().stream()
                .sorted(Comparator.comparing(AiProviderConfig::getProvider))
                .map(c -> new ProviderSettings(c.getProvider(), c.getBaseUrl(), c.getModel(), c.hasKey(),
                        c.getApiKeyHint()))
                .toList();
        return new AiSettingsResponse(s.getActiveProvider(), s.isEnabled(), providers);
    }
}
