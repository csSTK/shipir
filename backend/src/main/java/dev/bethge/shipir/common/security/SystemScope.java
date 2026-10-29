package dev.bethge.shipir.common.security;

import java.util.function.Supplier;

/**
 * Marks the current call as made by Shipir itself (scheduler, auto-update, retention job) rather than on behalf of a
 * user. {@code CurrentAccess} lets only calls inside this scope bypass permission checks; a thread with neither an
 * authentication nor this scope is denied everything.
 *
 * <p>Backed by a {@link ScopedValue}: bound only for the duration of {@link #call}/{@link #run}, never leaks to later
 * work on a pooled thread. It is not inherited by threads started inside: code that hands permission-checked work to
 * an executor must re-bind it on the worker (see {@code SearchService}), otherwise the worker is denied everything.
 */
public final class SystemScope {

    private static final ScopedValue<Boolean> SYSTEM = ScopedValue.newInstance();

    private SystemScope() {
    }

    /** Runs {@code action} as the system. */
    public static <T> T call(Supplier<T> action) {
        return ScopedValue.where(SYSTEM, Boolean.TRUE).call(action::get);
    }

    /** Runs {@code action} as the system. */
    public static void run(Runnable action) {
        ScopedValue.where(SYSTEM, Boolean.TRUE).run(action);
    }

    /** True while a system call is running. */
    public static boolean active() {
        return SYSTEM.isBound();
    }
}
