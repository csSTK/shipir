package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ImageServiceImpl implements ImageService {

    private static final String NONE_TAG = "<none>:<none>";

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
                created == null ? null : Instant.ofEpochSecond(created), usedIds.contains(i.getId()));
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
}
