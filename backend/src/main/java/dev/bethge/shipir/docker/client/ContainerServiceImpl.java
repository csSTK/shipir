package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.HealthStateLog;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.TreeMap;
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
    public ContainerDetailsDto inspect(UUID hostId, DockerEndpoint endpoint, String id) {
        return calls.call(hostId, endpoint, client -> toDetails(client.inspectContainerCmd(id).exec()));
    }

    private static final int MAX_HEALTH_LOGS = 5;
    private static final int MAX_HEALTH_OUTPUT = 1000;

    static ContainerDetailsDto toDetails(InspectContainerResponse r) {
        var state = r.getState();
        var config = r.getConfig();
        Map<String, String> labels = config == null || config.getLabels() == null
                ? Map.of() : new TreeMap<>(config.getLabels());
        List<String> command = new ArrayList<>();
        if (r.getPath() != null) command.add(r.getPath());
        if (r.getArgs() != null) command.addAll(Arrays.asList(r.getArgs()));
        List<ContainerDetailsDto.EnvVar> env = new ArrayList<>();
        if (config != null && config.getEnv() != null) {
            for (String entry : config.getEnv()) {
                int eq = entry.indexOf('=');
                env.add(eq < 0 ? new ContainerDetailsDto.EnvVar(entry, "")
                        : new ContainerDetailsDto.EnvVar(entry.substring(0, eq), entry.substring(eq + 1)));
            }
        }
        List<ContainerDetailsDto.Mount> mounts = r.getMounts() == null ? List.of() : r.getMounts().stream()
                .map(m -> new ContainerDetailsDto.Mount(m.getName() != null ? "volume" : "bind", m.getName(),
                        m.getSource(), m.getDestination() == null ? null : m.getDestination().getPath(),
                        Boolean.FALSE.equals(m.getRW())))
                .toList();
        var settings = r.getNetworkSettings();
        List<ContainerDetailsDto.Network> networks = new ArrayList<>();
        if (settings != null && settings.getNetworks() != null) {
            new TreeMap<>(settings.getNetworks()).forEach((name, n) -> networks.add(network(name, n)));
        }
        String name = stripSlash(r.getName());
        String project = labels.get(PROJECT_LABEL);
        String service = labels.get(SERVICE_LABEL);
        RestartPolicy policy = r.getHostConfig() == null ? null : r.getHostConfig().getRestartPolicy();
        return new ContainerDetailsDto(r.getId(), name, config == null ? null : config.getImage(),
                state == null ? null : state.getStatus(),
                instant(r.getCreated()), state == null ? null : instant(state.getStartedAt()),
                r.getRestartCount() == null ? 0 : r.getRestartCount(),
                new ContainerDetailsDto.RestartPolicy(policy == null || policy.getName() == null || policy.getName().isEmpty()
                        ? "no" : policy.getName(), policy == null || policy.getMaximumRetryCount() == null ? 0 : policy.getMaximumRetryCount()),
                command, env, mounts, labels, networks, ports(settings == null ? null : settings.getPorts()),
                state == null ? null : health(state.getHealth()),
                project == null || project.isBlank() ? null : project, service == null || service.isBlank() ? null : service);
    }

    private static ContainerDetailsDto.Network network(String name, ContainerNetwork n) {
        List<String> aliases = n.getAliases() == null ? List.of() : n.getAliases();
        return new ContainerDetailsDto.Network(name, blankToNull(n.getIpAddress()), blankToNull(n.getGateway()),
                blankToNull(n.getMacAddress()), aliases);
    }

    private static List<ContainerDetailsDto.Port> ports(Ports ports) {
        if (ports == null || ports.getBindings() == null) return List.of();
        List<ContainerDetailsDto.Port> result = new ArrayList<>();
        for (Map.Entry<ExposedPort, Ports.Binding[]> e : ports.getBindings().entrySet()) {
            ExposedPort exposed = e.getKey();
            String protocol = exposed.getProtocol().toString();
            Ports.Binding[] bindings = e.getValue();
            if (bindings == null || bindings.length == 0) {
                result.add(new ContainerDetailsDto.Port(exposed.getPort(), protocol, null, null));
                continue;
            }
            for (Ports.Binding b : bindings) {
                Integer hostPort = null;
                try {
                    hostPort = b.getHostPortSpec() == null ? null : Integer.valueOf(b.getHostPortSpec());
                } catch (NumberFormatException ignored) {
                    // leave unpublished-looking rather than fail the whole inspect
                }
                result.add(new ContainerDetailsDto.Port(exposed.getPort(), protocol, blankToNull(b.getHostIp()), hostPort));
            }
        }
        result.sort(Comparator.comparingInt(ContainerDetailsDto.Port::containerPort)
                .thenComparing(ContainerDetailsDto.Port::protocol));
        return result;
    }

    private static ContainerDetailsDto.Health health(HealthState h) {
        if (h == null) return null;
        List<HealthStateLog> log = h.getLog() == null ? Collections.emptyList() : h.getLog();
        List<HealthStateLog> last = log.subList(Math.max(0, log.size() - MAX_HEALTH_LOGS), log.size());
        return new ContainerDetailsDto.Health(h.getStatus(), h.getFailingStreak() == null ? 0 : h.getFailingStreak(),
                last.stream().map(l -> new ContainerDetailsDto.HealthLog(instant(l.getStart()), instant(l.getEnd()),
                        l.getExitCode() == null ? 0 : l.getExitCode(), truncate(l.getOutput()))).toList());
    }

    private static String truncate(String output) {
        return output != null && output.length() > MAX_HEALTH_OUTPUT ? output.substring(0, MAX_HEALTH_OUTPUT) : output;
    }

    /** Docker uses {@code 0001-01-01T00:00:00Z} for "never". */
    private static Instant instant(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-")) return null;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
