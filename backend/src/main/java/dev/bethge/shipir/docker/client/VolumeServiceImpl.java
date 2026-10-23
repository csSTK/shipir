package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VolumeServiceImpl implements VolumeService {

    private final DockerCallSupport calls;

    public VolumeServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public List<VolumeDto> list(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> {
            Set<String> mounted = client.listContainersCmd().withShowAll(true).exec().stream()
                    .map(Container::getMounts)
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .map(ContainerMount::getName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            ListVolumesResponse response = client.listVolumesCmd().exec();
            List<InspectVolumeResponse> volumes = response == null ? null : response.getVolumes();
            if (volumes == null) return List.<VolumeDto>of();
            return volumes.stream().map(v -> toDto(v, mounted)).toList();
        });
    }

    private static VolumeDto toDto(InspectVolumeResponse v, Set<String> mounted) {
        return new VolumeDto(v.getName(), v.getDriver(), v.getMountpoint(), created(v), mounted.contains(v.getName()));
    }

    /** docker-java has no typed field for {@code CreatedAt}; read the raw ISO-8601 value, null if absent/unparsable. */
    private static Instant created(InspectVolumeResponse v) {
        Object raw = v.getRawValues() == null ? null : v.getRawValues().get("CreatedAt");
        if (!(raw instanceof String text)) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
