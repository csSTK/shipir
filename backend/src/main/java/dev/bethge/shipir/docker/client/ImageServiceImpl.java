package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ImageServiceImpl implements ImageService {

    private static final String NONE_TAG = "<none>:<none>";

    /** A pull streams for as long as the layers download; the read timeout does not apply, this is the overall cap. */
    static final Duration PULL_TIMEOUT = Duration.ofMinutes(30);

    private final DockerCallSupport calls;

    public ImageServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public List<ImageDto> list(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> {
            Set<String> usedIds = client.listContainersCmd().withShowAll(true).exec().stream()
                    .map(Container::getImageId)
                    .collect(Collectors.toSet());
            return client.listImagesCmd().exec().stream()
                    .map(i -> toDto(i, usedIds))
                    .toList();
        });
    }

    private static ImageDto toDto(Image i, Set<String> usedIds) {
        Long created = i.getCreated();
        return new ImageDto(i.getId(), tags(i.getRepoTags()), i.getSize(),
                created == null ? null : Instant.ofEpochSecond(created), usedIds.contains(i.getId()),
                i.getRepoDigests() == null ? List.of() : List.of(i.getRepoDigests()), false);
    }

    /** Dangling images come with null, empty or a {@code <none>:<none>} tag; all mean "no tags". */
    private static List<String> tags(String[] repoTags) {
        if (repoTags == null) return List.of();
        return Arrays.stream(repoTags).filter(t -> t != null && !NONE_TAG.equals(t)).toList();
    }

    @Override
    public void removeImage(UUID hostId, DockerEndpoint endpoint, String id) {
        calls.call(hostId, endpoint, client -> {
            client.removeImageCmd(id).withForce(false).exec();
            return null;
        });
    }

    @Override
    public List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint, boolean includeTagged) {
        return list(hostId, endpoint).stream()
                .filter(i -> !i.inUse() && (includeTagged || i.repoTags().isEmpty()))
                .map(i -> new PruneCandidateDto(i.id(), i.repoTags().isEmpty() ? null : i.repoTags().get(0), i.size(), null))
                .toList();
    }

    @Override
    public void pull(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin auth, Consumer<String> onProgress) {
        try {
            var client = calls.manager().client(hostId, endpoint);
            var cmd = client.pullImageCmd(reference.contains("@") ? reference : repositoryOf(reference));
            if (!reference.contains("@")) {
                cmd.withTag(tagOf(reference));
            }
            if (auth != null) {
                cmd.withAuthConfig(new AuthConfig().withRegistryAddress(auth.registryHost())
                        .withUsername(auth.username()).withPassword(auth.secret()));
            }
            Progress callback = new Progress(onProgress);
            try (callback) {
                cmd.exec(callback);
                if (!callback.awaitCompletion(PULL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image pull took too long.", null);
                }
            }
            if (callback.failure != null) {
                throw new DockerConnectionException(callback.failure, "The image could not be pulled.", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image pull was interrupted.", e);
        } catch (Exception e) {
            throw calls.translate(e);
        }
    }

    private static String repositoryOf(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(0, colon) : reference;
    }

    private static String tagOf(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(colon + 1) : "latest";
    }

    /** Turns pull items into one line per (layer, status) change; the daemon's error text is never forwarded. */
    private static final class Progress extends ResultCallback.Adapter<PullResponseItem> {
        private final Consumer<String> onProgress;
        private final java.util.Map<String, String> lastStatus = new java.util.HashMap<>();
        volatile DockerErrorCode failure;

        Progress(Consumer<String> onProgress) {
            this.onProgress = onProgress;
        }

        @Override
        public void onNext(PullResponseItem item) {
            if (item.getErrorDetail() != null || item.getError() != null) {
                String text = String.valueOf(item.getError()).toLowerCase(java.util.Locale.ROOT);
                failure = text.contains("not found") || text.contains("manifest unknown")
                        ? DockerErrorCode.NOT_FOUND : DockerErrorCode.PULL_FAILED;
                return;
            }
            String status = item.getStatus();
            if (status == null) {
                return;
            }
            String id = item.getId() == null ? "" : item.getId();
            if (status.equals(lastStatus.put(id, status))) {
                return;
            }
            onProgress.accept(id.isEmpty() ? status : id + ": " + status);
        }
    }
}
