package dev.bethge.shipir.common.security;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Marks the current call as made by the AI assistant on behalf of the signed-in user. {@code AuditAspect} adds
 * {@code {via: "AI", chatSessionId}} to every audit entry written while it is bound.
 *
 * <p>Backed by a {@link ScopedValue}: bound only for the duration of {@link #callAs}, never leaks to later
 * requests on a pooled thread, and is not inherited by threads the audited code starts itself (e.g. the
 * virtual-thread deploy worker), so entries those threads write on their own carry no marker.
 */
public final class AiInvocationContext {

    private static final ScopedValue<UUID> CHAT_SESSION = ScopedValue.newInstance();

    private AiInvocationContext() {
    }

    /** Runs {@code action} with the marker bound to the given chat session. */
    public static <T> T callAs(UUID chatSessionId, Supplier<T> action) {
        return ScopedValue.where(CHAT_SESSION, chatSessionId).call(action::get);
    }

    /** True while an AI call is running. */
    public static boolean active() {
        return CHAT_SESSION.isBound();
    }

    /** The chat session of the running AI call, empty for normal requests (or when none was given). */
    public static Optional<UUID> chatSessionId() {
        return CHAT_SESSION.isBound() ? Optional.ofNullable(CHAT_SESSION.get()) : Optional.empty();
    }
}
