package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Periodically compares the RepoDigests of every host's tagged images with the digest the registry serves now and
 * stores the references with a newer remote digest in {@link ImageUpdateStore}. A failing host or image keeps its
 * previous result (or none) and never stops the others. Disable with {@code shipir.images.update-check-enabled=false}.
 */
@Component
@EnableConfigurationProperties(ImageUpdateProperties.class)
@ConditionalOnProperty(name = "shipir.images.update-check-enabled", matchIfMissing = true)
class ImageUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImageUpdateScheduler.class);

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ImageService images;
    private final RemoteDigestLookup remote;
    private final ImageUpdateStore store;
    private final Clock clock;

    ImageUpdateScheduler(DockerHostRepository hosts, HostEndpointResolver endpoints, ImageService images,
                         RemoteDigestLookup remote, ImageUpdateStore store, Clock clock) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.images = images;
        this.remote = remote;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipir.images.update-check-interval:6h}",
            initialDelayString = "${shipir.images.update-check-interval:6h}")
    synchronized void check() {
        Map<String, Optional<String>> remoteDigests = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (DockerHost host : hosts.findAll()) {
            seen.add(host.getId());
            try {
                List<ImageDto> local = images.list(host.getId(), endpoints.endpointOf(host));
                store.replace(host.getId(), updatable(local, remoteDigests), clock.instant());
            } catch (RuntimeException e) {
                log.debug("Image update check skipped for host {}: {}", host.getId(), e.getClass().getSimpleName());
            }
        }
        store.retainHosts(seen);
    }

    private Set<String> updatable(List<ImageDto> local, Map<String, Optional<String>> remoteDigests) {
        Set<String> result = new HashSet<>();
        for (ImageDto image : local) {
            for (String tag : image.repoTags()) {
                ImageReference ref = ImageReference.parse(tag);
                if (ref == null || result.contains(ref.key())) {
                    continue;
                }
                Set<String> localDigests = digestsOf(image, ref);
                if (localDigests.isEmpty()) {
                    continue; // built or loaded locally: nothing to compare
                }
                try {
                    Optional<String> latest = remoteDigests.computeIfAbsent(ref.key(), k -> remote.digestOf(ref));
                    if (latest.isPresent() && !localDigests.contains(latest.get())) {
                        result.add(ref.key());
                    }
                } catch (RuntimeException e) {
                    log.debug("Remote digest check failed for {}: {}", ref.key(), e.getClass().getSimpleName());
                }
            }
        }
        return result;
    }

    /** Digests (sha256:...) this image holds for the repository of {@code ref}. */
    private static Set<String> digestsOf(ImageDto image, ImageReference ref) {
        return image.repoDigests().stream()
                .filter(d -> {
                    int at = d.indexOf('@');
                    if (at < 0) {
                        return false;
                    }
                    ImageReference r = ImageReference.parse(d.substring(0, at) + ":x");
                    return r != null && r.repositoryKey().equals(ref.repositoryKey());
                })
                .map(d -> d.substring(d.indexOf('@') + 1))
                .collect(Collectors.toSet());
    }
}
