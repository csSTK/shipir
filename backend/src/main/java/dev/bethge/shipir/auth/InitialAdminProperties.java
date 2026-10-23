package dev.bethge.shipir.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bound from {@code shipir.initial-admin.*}; the password is only used while the users table is empty. */
@ConfigurationProperties("shipir.initial-admin")
public record InitialAdminProperties(@DefaultValue("admin") String username, String password) {
}
