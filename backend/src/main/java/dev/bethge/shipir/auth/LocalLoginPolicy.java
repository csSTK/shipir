package dev.bethge.shipir.auth;

/** Decides whether username/password login is allowed for a user of the given role (implemented by auth/oidc). */
public interface LocalLoginPolicy {

    boolean allows(Role role);
}
