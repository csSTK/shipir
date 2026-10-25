package dev.bethge.shipir.auth.token;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.token.dto.ApiTokenResponse;
import dev.bethge.shipir.auth.token.dto.CreateApiTokenRequest;
import dev.bethge.shipir.auth.token.dto.CreatedApiTokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user's own API tokens. The plaintext is returned by {@link #create} and never stored or logged; audit entries
 * carry the prefix only.
 */
@Service
public class ApiTokenService {

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final Clock clock;
    private final ApiTokenProperties properties;

    ApiTokenService(ApiTokenRepository tokens, UserRepository users, Clock clock, ApiTokenProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<ApiTokenResponse> list(String username) {
        return tokens.findByUserIdOrderByCreatedAtDesc(userOf(username).getId()).stream()
                .map(t -> new ApiTokenResponse(t.getId(), t.getName(), t.getPrefix(), t.getCreatedAt(), t.getExpiresAt(),
                        t.getLastUsedAt()))
                .toList();
    }

    /** @throws ApiTokenException 400 INVALID_EXPIRY if {@code expiresAt} is not in the future or beyond the maximum lifetime */
    @Transactional
    @Auditable(action = "API_TOKEN_CREATE", targetType = "API_TOKEN", targetId = "#result?.id",
            details = "{'prefix': #result.prefix}")
    public CreatedApiTokenResponse create(String username, CreateApiTokenRequest request) {
        User user = userOf(username);
        Instant now = clock.instant();
        Instant latest = now.plus(properties.maxLifetime());
        Instant expiresAt = request.expiresAt() == null ? latest : request.expiresAt();
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(latest)) {
            throw new ApiTokenException(HttpStatus.BAD_REQUEST, "INVALID_EXPIRY",
                    "The expiry must be in the future and within " + properties.maxLifetime().toDays() + " days");
        }
        String token = TokenCodec.newToken(ApiTokenAuthenticator.SCHEME_PREFIX);
        ApiToken saved = tokens.save(new ApiToken(user.getId(), request.name().strip(), TokenCodec.sha256Hex(token),
                TokenCodec.displayPrefix(token), now, expiresAt));
        return new CreatedApiTokenResponse(saved.getId(), saved.getName(), saved.getPrefix(), saved.getCreatedAt(),
                saved.getExpiresAt(), token);
    }

    /** @throws ApiTokenException 404 TOKEN_NOT_FOUND if the token does not exist or belongs to someone else */
    @Transactional
    @Auditable(action = "API_TOKEN_REVOKE", targetType = "API_TOKEN", targetId = "#id")
    public void revoke(String username, UUID id) {
        ApiToken token = tokens.findByIdAndUserId(id, userOf(username).getId()).orElseThrow(
                () -> new ApiTokenException(HttpStatus.NOT_FOUND, "TOKEN_NOT_FOUND", "Token not found"));
        tokens.delete(token);
    }

    private User userOf(String username) {
        return users.findByUsername(username).orElseThrow(
                () -> new ApiTokenException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }
}
