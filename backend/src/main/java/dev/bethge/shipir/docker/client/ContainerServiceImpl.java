package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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
    public List<DeploymentSummaryDto> listDeployments(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> groupDeployments(client.listContainersCmd().withShowAll(true).exec()));
    }

    static List<DeploymentSummaryDto> groupDeployments(List<Container> containers) {
        Map<String, List<Container>> byProject = new LinkedHashMap<>();
        for (Container c : containers) {
            String project = label(c, PROJECT_LABEL);
            if (project != null) {
                byProject.computeIfAbsent(project, k -> new ArrayList<>()).add(c);
            }
        }
        return byProject.entrySet().stream()
                .map(e -> summarize(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(DeploymentSummaryDto::name))
                .toList();
    }

    private static DeploymentSummaryDto summarize(String name, List<Container> members) {
        int running = (int) members.stream().filter(c -> "running".equals(c.getState())).count();
        DeploymentSummaryDto.Status status = running == 0 ? DeploymentSummaryDto.Status.STOPPED
                : running == members.size() ? DeploymentSummaryDto.Status.RUNNING : DeploymentSummaryDto.Status.PARTIAL;
        String workingDir = members.stream().map(c -> label(c, WORKING_DIR_LABEL))
                .filter(w -> w != null).findFirst().orElse(null);
        return new DeploymentSummaryDto(name, members.size(), running, status, workingDir);
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

    @Override
    public void removeContainer(UUID hostId, DockerEndpoint endpoint, String id, boolean force) {
        calls.call(hostId, endpoint, client -> {
            client.removeContainerCmd(id).withForce(force).withRemoveVolumes(false).exec();
            return null;
        });
    }

    @Override
    public void startContainer(UUID hostId, DockerEndpoint endpoint, String id) {
        lifecycle(hostId, endpoint, Duration.ZERO, c -> c.startContainerCmd(id).exec());
    }

    @Override
    public void stopContainer(UUID hostId, DockerEndpoint endpoint, String id, int timeoutSeconds) {
        lifecycle(hostId, endpoint, Duration.ofSeconds(timeoutSeconds), c -> c.stopContainerCmd(id).withTimeout(timeoutSeconds).exec());
    }

    @Override
    public void restartContainer(UUID hostId, DockerEndpoint endpoint, String id, int timeoutSeconds) {
        lifecycle(hostId, endpoint, Duration.ofSeconds(timeoutSeconds), c -> c.restartContainerCmd(id).withtTimeout(timeoutSeconds).exec());
    }

    @Override
    public void pauseContainer(UUID hostId, DockerEndpoint endpoint, String id) {
        lifecycle(hostId, endpoint, Duration.ZERO, c -> c.pauseContainerCmd(id).exec());
    }

    @Override
    public void unpauseContainer(UUID hostId, DockerEndpoint endpoint, String id) {
        lifecycle(hostId, endpoint, Duration.ZERO, c -> c.unpauseContainerCmd(id).exec());
    }

    @Override
    public void killContainer(UUID hostId, DockerEndpoint endpoint, String id) {
        lifecycle(hostId, endpoint, Duration.ZERO, c -> c.killContainerCmd(id).exec());
    }

    /** A 409 on a lifecycle action means "wrong state", not "in use". */
    private void lifecycle(UUID hostId, DockerEndpoint endpoint, Duration extra, Consumer<DockerClient> action) {
        try {
            calls.call(hostId, endpoint, extra, client -> {
                action.accept(client);
                return null;
            });
        } catch (DockerConnectionException e) {
            // The daemon answers unpause on a running container with 500 "Container ... is not paused".
            boolean notPaused = e.getCause() instanceof InternalServerErrorException ise
                    && ise.getMessage() != null && ise.getMessage().contains("is not paused");
            if (e.getCode() == DockerErrorCode.IN_USE || notPaused) {
                throw new DockerConnectionException(DockerErrorCode.INVALID_STATE,
                        "The container is not in a state that allows this action.", e);
            }
            throw e;
        }
    }
}
