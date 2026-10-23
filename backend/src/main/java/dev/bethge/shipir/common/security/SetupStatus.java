package dev.bethge.shipir.common.security;

import java.util.Optional;

/** Port for the setup gate; implemented by the auth feature so {@code common} stays free of feature code. */
public interface SetupStatus {

    /** The step the user still has to complete, or empty when setup is done (or the user is unknown). */
    Optional<SetupStep> currentStep(String username);
}
