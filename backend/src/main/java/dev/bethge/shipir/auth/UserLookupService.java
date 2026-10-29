package dev.bethge.shipir.auth;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the signed-in username (as carried by the {@code Authentication}) to the user, so controllers do not
 * touch {@link UserRepository}. Deliberately not transactional: {@link User} has no lazy associations and each
 * call is a single read.
 */
@Service
public class UserLookupService {

    private final UserRepository users;

    UserLookupService(UserRepository users) {
        this.users = users;
    }

    public Optional<User> findByUsername(String username) {
        return users.findByUsername(username);
    }

    /** @throws java.util.NoSuchElementException if the user no longer exists */
    public User requireByUsername(String username) {
        return users.findByUsername(username).orElseThrow();
    }

    /** @throws java.util.NoSuchElementException if the user no longer exists */
    public UUID idOf(String username) {
        return requireByUsername(username).getId();
    }
}
