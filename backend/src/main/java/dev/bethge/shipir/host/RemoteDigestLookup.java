package dev.bethge.shipir.host;

import java.util.Optional;

/**
 * Port: the digest the registry currently serves for a tag (the manifest or index digest, the same value Docker
 * stores in an image's RepoDigests). Implemented by {@code registry/}; empty on any failure (unreachable,
 * unauthorised, unknown tag), never throws.
 */
@FunctionalInterface
public interface RemoteDigestLookup {

    Optional<String> digestOf(ImageReference reference);
}
