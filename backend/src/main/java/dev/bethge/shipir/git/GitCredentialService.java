package dev.bethge.shipir.git;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.git.dto.GitConnectionTestRequest;
import dev.bethge.shipir.git.dto.GitConnectionTestResponse;
import dev.bethge.shipir.git.dto.GitCredentialRequest;
import dev.bethge.shipir.git.dto.GitCredentialResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
class GitCredentialService {

    private final GitCredentialRepository credentials;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final GitRemoteChecker checker;
    private final Clock clock;

    GitCredentialService(GitCredentialRepository credentials, UserRepository users, SecretCipher cipher,
                         GitRemoteChecker checker, Clock clock) {
        this.credentials = credentials;
        this.users = users;
        this.cipher = cipher;
        this.checker = checker;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<GitCredentialResponse> list() {
        return credentials.findAll().stream()
                .sorted(Comparator.comparing(GitCredential::getName, String.CASE_INSENSITIVE_ORDER))
                .map(GitCredentialService::toResponse)
                .toList();
    }

    @Auditable(action = "GIT_CREDENTIAL_CREATE", targetType = "GIT_CREDENTIAL", targetId = "#result?.id")
    GitCredentialResponse create(GitCredentialRequest request, String username) {
        if (request.token() == null || request.token().isBlank()) {
            throw new GitException(HttpStatus.BAD_REQUEST, "TOKEN_REQUIRED", "A token is required");
        }
        requireKey();
        if (credentials.existsByName(request.name())) {
            throw nameTaken();
        }
        UUID ownerId = users.findByUsername(username).orElseThrow().getId();
        GitCredential c = new GitCredential(request.name(), request.username(), cipher.encrypt(request.token()),
                hint(request.token()), ownerId, clock.instant());
        try {
            return toResponse(credentials.saveAndFlush(c));
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
    }

    @Auditable(action = "GIT_CREDENTIAL_UPDATE", targetType = "GIT_CREDENTIAL", targetId = "#id")
    @Transactional
    GitCredentialResponse update(UUID id, GitCredentialRequest request) {
        GitCredential c = find(id);
        if (!c.getName().equals(request.name()) && credentials.existsByName(request.name())) {
            throw nameTaken();
        }
        if (request.token() != null && !request.token().isBlank()) {
            requireKey();
            c.replaceToken(cipher.encrypt(request.token()), hint(request.token()));
        }
        c.update(request.name(), request.username(), clock.instant());
        try {
            credentials.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        return toResponse(c);
    }

    @Auditable(action = "GIT_CREDENTIAL_DELETE", targetType = "GIT_CREDENTIAL", targetId = "#id")
    @Transactional
    void delete(UUID id) {
        GitCredential c = find(id);
        if (credentials.countStacksUsing(id) > 0) {
            throw new GitException(HttpStatus.CONFLICT, "CREDENTIAL_IN_USE",
                    "The credential is used by at least one stack");
        }
        credentials.delete(c);
        try {
            credentials.flush();
        } catch (DataIntegrityViolationException e) {
            // a stack referenced it between the check and the delete (FK is RESTRICT)
            throw new GitException(HttpStatus.CONFLICT, "CREDENTIAL_IN_USE",
                    "The credential is used by at least one stack");
        }
    }

    /** Not transactional: the network call must not hold a database connection. */
    GitConnectionTestResponse test(UUID id, GitConnectionTestRequest request) {
        checker.validateUrl(request.repoUrl());
        GitCredential c = credentials.findById(id).orElseThrow(GitCredentialService::notFound);
        requireKey();
        String token = cipher.decrypt(c.getTokenCiphertext());
        return new GitConnectionTestResponse(checker.check(request.repoUrl(), c.getUsername(), token));
    }

    private GitCredential find(UUID id) {
        return credentials.findById(id).orElseThrow(GitCredentialService::notFound);
    }

    private void requireKey() {
        if (!cipher.isConfigured()) {
            throw new GitException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "Git credentials need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to store their token");
        }
    }

    /** Masked display value: the last four characters of a long token, otherwise only dots. */
    static String hint(String token) {
        String t = token.trim();
        return t.length() >= 12 ? "••••" + t.substring(t.length() - 4) : "••••";
    }

    private static GitException notFound() {
        return new GitException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Git credential not found");
    }

    private static GitException nameTaken() {
        return new GitException(HttpStatus.CONFLICT, "CREDENTIAL_NAME_TAKEN",
                "A Git credential with this name already exists");
    }

    private static GitCredentialResponse toResponse(GitCredential c) {
        return new GitCredentialResponse(c.getId(), c.getName(), c.getUsername(), c.getTokenHint(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
