package dev.bethge.shipir.common.security;

/** Port for the setup gate; implemented by the auth feature so {@code common} stays free of feature code. */
public interface SetupStatus {

    boolean isSetupRequired(String username);
}
