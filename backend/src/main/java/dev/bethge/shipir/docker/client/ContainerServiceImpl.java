package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.StackSummaryDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ContainerServiceImpl implements ContainerService {

    static final String PROJECT_LABEL = "com.docker.compose.project";
    static final String SERVICE_LABEL = "com.docker.compose.service";
    static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";

    private final DockerCallSupport calls;

    public ContainerServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public List<ContainerDto> listContainers(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> client.listContainersCmd().withShowAll(true).exec().stream()
                .map(ContainerServiceImpl::toDto)
                .toList());
    }

    @Override
    public List<StackSummaryDto> listStacks(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> groupStacks(client.listContainersCmd().withShowAll(true).exec()));
    }

    static List<StackSummaryDto> groupStacks(List<Container> containers) {
        Map<String, List<Container>> byProject = new LinkedHashMap<>();
        for (Container c : containers) {
            String project = label(c, PROJECT_LABEL);
            if (project != null) {
                byProject.computeIfAbsent(project, k -> new ArrayList<>()).add(c);
            }
        }
        return byProject.entrySet().stream()
                .map(e -> summarize(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(StackSummaryDto::name))
                .toList();
    }

    private static StackSummaryDto summarize(String name, List<Container> members) {
        int running = (int) members.stream().filter(c -> "running".equals(c.getState())).count();
        StackSummaryDto.Status status = running == 0 ? StackSummaryDto.Status.STOPPED
                : running == members.size() ? StackSummaryDto.Status.RUNNING : StackSummaryDto.Status.PARTIAL;
        String workingDir = members.stream().map(c -> label(c, WORKING_DIR_LABEL))
                .filter(w -> w != null).findFirst().orElse(null);
        return new StackSummaryDto(name, members.size(), running, status, workingDir);
    }

    static ContainerDto toDto(Container c) {
        String[] names = c.getNames();
        String name = names == null || names.length == 0 ? null : stripSlash(names[0]);
        Long created = c.getCreated();
        return new ContainerDto(c.getId(), name, c.getImage(), c.getState(), c.getStatus(), health(c.getStatus()),
                created == null ? null : Instant.ofEpochSecond(created), ports(c.getPorts()),
                label(c, PROJECT_LABEL), label(c, SERVICE_LABEL));
    }

    /** The health state is only available as suffix of the status text, e.g. {@code Up 5 seconds (healthy)}. */
    private static String health(String status) {
        if (status == null) return null;
        if (status.contains("(unhealthy)")) return "unhealthy";
        if (status.contains("(healthy)")) return "healthy";
        if (status.contains("(health: starting)")) return "starting";
        return null;
    }

    private static List<String> ports(ContainerPort[] ports) {
        if (ports == null) return List.of();
        return Arrays.stream(ports)
                .map(p -> (p.getPublicPort() != null ? p.getPublicPort() + ":" : "") + p.getPrivatePort() + "/" + p.getType())
                .distinct()
                .toList();
    }

    private static String label(Container c, String key) {
        Map<String, String> labels = c.getLabels();
        String value = labels == null ? null : labels.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static String stripSlash(String name) {
        return name != null && name.startsWith("/") ? name.substring(1) : name;
    }
}
