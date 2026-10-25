package dev.bethge.shipir.auth.token;

import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a presented bearer secret to an authentication. Empty for anything that is not a currently valid token of
 * an enabled user, without saying why. The role is read from the user on every request, so role changes, disabling and
 * deleting a user (cascade) take effect immediately.
 */
@Service
@EnableConfigurationProperties(ApiTokenProperties.class)
public class ApiTokenAuthenticator {

    public static final String SCHEME_PREFIX = "shp_";
    private static final Pattern SHAPE = Pattern.compile(Pattern.quote(SCHEME_PREFIX) + "[A-Za-z0-9_-]{"
            + TokenCodec.RANDOM_PART_LENGTH + "}");
    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthenticator.class);

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final Clock clock;
    private final ApiTokenProperties properties;

    ApiTokenAuthenticator(ApiTokenRepository tokens, UserRepository users, Clock clock, ApiTokenProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
        this.properties = properties;
    }

    public Optional<Authentication> authenticate(String presented) {
        // Cheap shape check first: oversized or foreign input is never hashed or looked up.
        if (presented == null || !SHAPE.matcher(presented).matches()) {
            return Optional.empty();
        }
        String hash = TokenCodec.sha256Hex(presented);
        Instant now = clock.instant();
        return tokens.findByTokenHash(hash)
                .filter(t -> TokenCodec.hashesEqual(t.getTokenHash(), hash))
                .filter(t -> !t.isExpiredAt(now))
                .flatMap(t -> users.findById(t.getUserId()).filter(u -> !u.isDisabled())
                        .map(u -> authenticated(t, u, now)));
    }

    private Authentication authenticated(ApiToken token, User user, Instant now) {
        try {
            tokens.touch(token.getId(), now, now.minus(properties.lastUsedResolution()));
        } catch (RuntimeException e) {
            // Bookkeeping only: never fail an authenticated request over it.
            log.warn("Could not update last use of API token {}: {}", token.getPrefix(), e.toString());
        }
        return new ApiTokenAuthentication(user.getUsername(), user.getRole().name(), token.getId());
    }
}
