package dev.bethge.shipir.host;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A tagged image reference split into registry host, repository and tag, with Docker Hub shorthands expanded
 * ({@code nginx} = {@code docker.io/library/nginx:latest}). Digest-only references have no tag and do not parse.
 */
public record ImageReference(String registryHost, String repository, String tag) {

    static final String DOCKER_HUB = "docker.io";
    private static final Set<String> HUB_ALIASES = Set.of("docker.io", "index.docker.io", "registry-1.docker.io");
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9]+(?:[._/-][a-z0-9]+)*");
    private static final Pattern REGISTRY = Pattern.compile("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?");
    private static final Pattern TAG = Pattern.compile(ImageReferenceRules.TAG);

    /** @return the reference, or null when it is blank, digest-only or not a valid tagged reference */
    public static ImageReference parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 300) {
            return null;
        }
        String ref = raw.trim();
        int at = ref.indexOf('@');
        if (at >= 0) {
            ref = ref.substring(0, at);
        }
        String registry = DOCKER_HUB;
        String rest = ref;
        int slash = ref.indexOf('/');
        if (slash > 0) {
            String first = ref.substring(0, slash);
            if (first.contains(".") || first.contains(":") || "localhost".equals(first)) {
                registry = first.toLowerCase(java.util.Locale.ROOT);
                rest = ref.substring(slash + 1);
            }
        }
        String tag = "latest";
        int colon = rest.lastIndexOf(':');
        if (colon >= 0) {
            tag = rest.substring(colon + 1);
            rest = rest.substring(0, colon);
        } else if (at >= 0) {
            return null;
        }
        if (HUB_ALIASES.contains(registry)) {
            registry = DOCKER_HUB;
        }
        if (DOCKER_HUB.equals(registry) && !rest.contains("/")) {
            rest = "library/" + rest;
        }
        if (!REGISTRY.matcher(registry).matches() || registry.contains("..") || !REPOSITORY.matcher(rest).matches()
                || !TAG.matcher(tag).matches()) {
            return null;
        }
        return new ImageReference(registry, rest, tag);
    }

    /** Stable identity of the tagged reference, {@code registry/repository:tag}. */
    public String key() {
        return repositoryKey() + ":" + tag;
    }

    /** {@code registry/repository}, the identity RepoDigests are grouped by. */
    public String repositoryKey() {
        return registryHost + "/" + repository;
    }
}
