package dev.bethge.shipir.compose.execution;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Compose profiles to activate ({@code --profile <name>}, a global flag before the subcommand) and services to restrict
 * a command to (positional arguments after it). Names are validated here, so nothing that looks like a flag or contains
 * a separator ever reaches the argument list. The profile {@code *} (all profiles) is accepted for internal lookups only.
 */
public record ComposeOptions(List<String> profiles, List<String> services) {

    /** Compose profile and service names: start alphanumeric, then letters, digits, {@code _ . -}. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}");
    public static final String ALL_PROFILES = "*";
    public static final ComposeOptions NONE = new ComposeOptions(List.of(), List.of());

    public ComposeOptions {
        profiles = List.copyOf(profiles);
        services = List.copyOf(services);
        profiles.forEach(p -> require(p, true, "profile"));
        services.forEach(s -> require(s, false, "service"));
    }

    public static ComposeOptions ofProfiles(List<String> profiles) {
        return new ComposeOptions(profiles, List.of());
    }

    public ComposeOptions withServices(List<String> services) {
        return new ComposeOptions(profiles, services);
    }

    public boolean isEmpty() {
        return profiles.isEmpty() && services.isEmpty();
    }

    private static void require(String value, boolean allowAll, String kind) {
        boolean ok = value != null && (NAME.matcher(value).matches() || allowAll && ALL_PROFILES.equals(value));
        if (!ok) {
            throw new IllegalArgumentException("Invalid compose " + kind + " name");
        }
    }
}
