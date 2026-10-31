package dev.bethge.shipir.docker.dto;

/**
 * One action of the recreate-leftover reconciliation: {@code leftoverName} is the {@code *-shipir-old-*} container,
 * {@code name} the original name it was renamed from.
 */
public record RecreateLeftoverAction(String name, String leftoverName, Outcome outcome) {

    public enum Outcome {
        /** A container with the original name exists: the leftover was removed. */
        REMOVED,
        /**
         * The leftover was running before the recreate, but the container under the original name (the new one, which
         * the crash left unstarted) was not: that container was started, then the leftover removed.
         */
        ORIGINAL_STARTED,
        /** No container with the original name: the leftover was renamed back (it was stopped before the recreate). */
        RESTORED,
        /** Renamed back and started, because the leftover was running before the recreate. */
        RESTORED_AND_STARTED,
        /** Nothing done: the original name was taken meanwhile (or the leftover vanished); a later pass decides. */
        SKIPPED,
        /** The action failed; the leftover is untouched or half-done and is retried on the next pass. */
        FAILED
    }
}
