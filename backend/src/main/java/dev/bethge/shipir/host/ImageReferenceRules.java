package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressDeniedException;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.dto.RegistryLogin;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of image references and the registry login selection shared by pull, build and push. A valid reference
 * is {@code [registry[:port]/]path[/path][:tag][@sha256:digest]} - nothing that could be read as a CLI flag or URL.
 */
final class ImageReferenceRules {

    private static final String PATH_PART = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";
    private static final String DOMAIN = "[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?(?::[0-9]{1,5})?";
    /** Shared with {@link ImageReference}, which must accept exactly the same tags. */
    static final String TAG = "[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}";
    private static final String REPOSITORY = "(?:" + DOMAIN + "/)?" + PATH_PART + "(?:/" + PATH_PART + ")*";
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(REPOSITORY + "(?::" + TAG + ")?(?:@sha256:[a-f0-9]{64})?");
    private static final Pattern REPOSITORY_ONLY = Pattern.compile(REPOSITORY);
    private static final Pattern TAG_ONLY = Pattern.compile(TAG);
    private static final Set<String> DOCKER_HUB_ALIASES =
            Set.of("docker.io", "index.docker.io", "registry-1.docker.io", "registry.hub.docker.com");

    private ImageReferenceRules() {
    }

    /** Any pull reference, tag and/or digest optional. */
    static boolean isValidReference(String reference) {
        return reference != null && reference.length() <= 255 && IMAGE_REFERENCE.matcher(reference).matches();
    }

    /** A name that can be applied or pushed: {@code repository:tag}, no digest. */
    static boolean isValidRepositoryAndTag(String repository, String tag) {
        return repository != null && tag != null && repository.length() <= 200 && REPOSITORY_ONLY.matcher(repository).matches()
                && TAG_ONLY.matcher(tag).matches();
    }

    /** The stored login for the registry named by the reference (Docker Hub when it names none), or null. */
    static RegistryLogin loginFor(RegistryAuthSource auths, String reference) {
        String registry = registryOf(reference);
        return auths.auths().stream()
                .filter(a -> registry.equals(canonicalRegistry(a.registryHost())))
                .findFirst()
                .map(a -> new RegistryLogin(a.registryHost(), a.username(), a.secret()))
                .orElse(null);
    }

    /** All stored logins, for the base images a build pulls (the registry is only known to the daemon). */
    static List<RegistryLogin> allLogins(RegistryAuthSource auths) {
        return auths.auths().stream().map(a -> new RegistryLogin(a.registryHost(), a.username(), a.secret())).toList();
    }

    /**
     * The daemon connects to the registry named in the reference, so that host must pass the shared egress policy
     * (internal ranges refused unless {@code shipir.egress.allow-private-ranges}; metadata always). Docker Hub, which a
     * reference without a registry means, is not resolved. Resolved here for a fail-fast answer; the daemon resolves again.
     */
    static void requireAllowedRegistry(EgressPolicy egress, String reference) {
        String registry = registryOf(reference);
        if ("docker.io".equals(registry)) {
            return;
        }
        int colon = registry.lastIndexOf(':');
        String host = colon > 0 ? registry.substring(0, colon) : registry;
        try {
            egress.check(host);
        } catch (EgressDeniedException e) {
            throw new HostException(HttpStatus.BAD_REQUEST, "REGISTRY_NOT_ALLOWED",
                    "The registry of this image reference is not permitted");
        }
    }

    static String registryOf(String reference) {
        int slash = reference.indexOf('/');
        if (slash > 0) {
            String first = reference.substring(0, slash);
            // like the daemon: a first part with a dot or colon, "localhost", or any uppercase letter names a registry
            if (first.contains(".") || first.contains(":") || first.equalsIgnoreCase("localhost")
                    || !first.equals(first.toLowerCase(Locale.ROOT))) {
                return canonicalRegistry(first);
            }
        }
        return "docker.io";
    }

    private static String canonicalRegistry(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return DOCKER_HUB_ALIASES.contains(lower) ? "docker.io" : lower;
    }
}
