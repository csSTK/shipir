package dev.bethge.shipir.ai.tools;

import java.util.Collection;
import java.util.Map;

/**
 * Looks up the env values of the deployment a tool call concerns, so the registry can keep them out of error text
 * that goes to the model (see {@link AiToolRegistry}). Returns an empty collection only when the call names no
 * deployment (nothing to redact). When the values cannot be read it throws {@link Unavailable}, and the registry
 * then drops the error details altogether (fail closed).
 */
@FunctionalInterface
interface DeploymentEnvValues {

    Collection<String> valuesFor(Map<String, Object> toolArguments);

    /** The env of the named deployment could not be read (not found, no access, undecryptable, key missing). */
    class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) {
            super("deployment env unavailable", cause);
        }
    }
}
