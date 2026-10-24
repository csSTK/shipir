package dev.bethge.shipir.registry;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.registry.dto.RegistryCredentialRequest;
import dev.bethge.shipir.registry.dto.RegistryCredentialResponse;
import dev.bethge.shipir.registry.dto.RegistryTestResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class RegistryCredentialService {

    private final RegistryCredentialRepository credentials;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final RegistryLoginChecker checker;
    private final Clock clock;

    RegistryCredentialService(RegistryCredentialRepository credentials, UserRepository users, SecretCipher cipher,
                              RegistryLoginChecker checker, Clock clock) {
        this.credentials = credentials;
        this.users = users;
        this.cipher = cipher;
        this.checker = checker;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RegistryCredentialResponse> list() {
        return credentials.findAll().stream()
                .sorted(Comparator.comparing(RegistryCredential::getName, String.CASE_INSENSITIVE_ORDER))
                .map(RegistryCredentialService::toResponse)
                .toList();
    }

    /**
     * Every stored credential with its decrypted secret, for callers that must authenticate image pulls
     * (e.g. compose runs). Throws {@link RegistryException} 409 ENCRYPTION_KEY_MISSING without a key.
     */
    @Transactional(readOnly = true)
    public List<RegistryAuth> decryptedCredentials() {
        List<RegistryCredential> all = credentials.findAll();
        if (all.isEmpty()) {
            return List.of();
        }
        requireKey();
        return all.stream()
                .map(c -> new RegistryAuth(c.getRegistryHost(), c.getUsername(),
                        cipher.decrypt(c.getSecretCiphertext())))
                .toList();
    }

    @Auditable(action = "REGISTRY_CREDENTIAL_CREATE", targetType = "REGISTRY_CREDENTIAL", targetId = "#result?.id")
    RegistryCredentialResponse create(RegistryCredentialRequest request, String username) {
        if (request.secret() == null || request.secret().isBlank()) {
            throw new RegistryException(HttpStatus.BAD_REQUEST, "SECRET_REQUIRED", "A password or token is required");
        }
        String host = RegistryHost.normalize(request.registryHost());
        requireKey();
        if (credentials.existsByName(request.name())) {
            throw nameTaken();
        }
        if (credentials.existsByRegistryHost(host)) {
            throw hostTaken();
        }
        UUID ownerId = users.findByUsername(username).orElseThrow().getId();
        RegistryCredential c = new RegistryCredential(request.name(), host, request.username(),
                cipher.encrypt(request.secret()), hint(request.secret()), ownerId, clock.instant());
        try {
            return toResponse(credentials.saveAndFlush(c));
        } catch (DataIntegrityViolationException e) {
            throw conflict(request.name(), host);
        }
    }

    @Auditable(action = "REGISTRY_CREDENTIAL_UPDATE", targetType = "REGISTRY_CREDENTIAL", targetId = "#id")
    @Transactional
    RegistryCredentialResponse update(UUID id, RegistryCredentialRequest request) {
        RegistryCredential c = find(id);
        String host = RegistryHost.normalize(request.registryHost());
        if (!c.getName().equals(request.name()) && credentials.existsByName(request.name())) {
            throw nameTaken();
        }
        if (!c.getRegistryHost().equals(host) && credentials.existsByRegistryHost(host)) {
            throw hostTaken();
        }
        if (request.secret() != null && !request.secret().isBlank()) {
            requireKey();
            c.replaceSecret(cipher.encrypt(request.secret()), hint(request.secret()));
        }
        c.update(request.name(), host, request.username(), clock.instant());
        try {
            credentials.flush();
        } catch (DataIntegrityViolationException e) {
            throw conflict(request.name(), host);
        }
        return toResponse(c);
    }

    @Auditable(action = "REGISTRY_CREDENTIAL_DELETE", targetType = "REGISTRY_CREDENTIAL", targetId = "#id")
    @Transactional
    public void delete(UUID id) {
        credentials.delete(find(id));
    }

    /** Not transactional: the network call must not hold a database connection. */
    RegistryTestResponse test(UUID id) {
        RegistryCredential c = credentials.findById(id).orElseThrow(RegistryCredentialService::notFound);
        requireKey();
        String secret = cipher.decrypt(c.getSecretCiphertext());
        return new RegistryTestResponse(checker.check(c.getRegistryHost(), c.getUsername(), secret));
    }

    private RegistryCredential find(UUID id) {
        return credentials.findById(id).orElseThrow(RegistryCredentialService::notFound);
    }

    private void requireKey() {
        if (!cipher.isConfigured()) {
            throw new RegistryException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "Registry credentials need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to store their secret");
        }
    }

    /** Masked display value: the last four characters of a long secret, otherwise only dots. */
    static String hint(String secret) {
        String s = secret.trim();
        return s.length() >= 12 ? "••••" + s.substring(s.length() - 4) : "••••";
    }

    private RegistryException conflict(String name, String host) {
        return credentials.existsByName(name) ? nameTaken() : hostTaken();
    }

    private static RegistryException notFound() {
        return new RegistryException(HttpStatus.NOT_FOUND, "REGISTRY_CREDENTIAL_NOT_FOUND",
                "Registry credential not found");
    }

    private static RegistryException nameTaken() {
        return new RegistryException(HttpStatus.CONFLICT, "REGISTRY_NAME_TAKEN",
                "A registry credential with this name already exists");
    }

    private static RegistryException hostTaken() {
        return new RegistryException(HttpStatus.CONFLICT, "REGISTRY_HOST_TAKEN",
                "A credential for this registry host already exists");
    }

    private static RegistryCredentialResponse toResponse(RegistryCredential c) {
        return new RegistryCredentialResponse(c.getId(), c.getName(), c.getRegistryHost(), c.getUsername(),
                c.getSecretHint(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
