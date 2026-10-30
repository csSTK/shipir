package dev.bethge.shipir.host;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Read access to the last image update check per host (see {@link ImageUpdateScheduler}); no registry calls. */
public interface ImageUpdates {

    /** True when the tagged reference ({@link ImageReference#key()}) had a newer remote digest at the last check. */
    boolean hasUpdate(UUID hostId, String key);

    /** When the host was last checked; null if never (or the check failed every time). */
    Instant checkedAt(UUID hostId);

    /** Drops the update flag of these references after an update was applied; the next check sets it again if needed. */
    void markApplied(UUID hostId, Collection<String> keys);
}
