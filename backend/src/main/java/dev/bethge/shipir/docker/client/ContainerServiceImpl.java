package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.Volume;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.ContainerResourcesSpec;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
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
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ContainerServiceImpl implements ContainerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContainerServiceImpl.class);

    /** Infix of the temporary name the old container gets during a recreate; {@link #WAS_RUNNING_SUFFIX} may follow. */
    static final String OLD_NAME_MARKER = "-shipir-old-";
    /** Appended to the temporary old name when the container was running, so a crash cannot lose that fact. */
    static final String WAS_RUNNING_SUFFIX = "-r";
    private static final Pattern LEFTOVER_NAME =
            Pattern.compile("(.+)" + OLD_NAME_MARKER + "[0-9a-f]{8}(" + WAS_RUNNING_SUFFIX + ")?");

    static final String PROJECT_LABEL = "com.docker.compose.project";
    static final String SERVICE_LABEL = "com.docker.compose.service";
    static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";

    private final DockerCallSupport calls;
    /** Old-container names of recreates running in this process; the leftover reconciliation must not touch them. */
    final Set<String> recreatesInFlight = ConcurrentHashMap.newKeySet();

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
        return calls.call(hostId, endpoint, client -> {
            InspectContainerResponse r = client.inspectContainerCmd(id).exec();
            return toDetails(r, imageEnv(client, r.getImageId()));
        });
    }

    private static final int MAX_HEALTH_LOGS = 5;
    private static final int MAX_HEALTH_OUTPUT = 1000;

    /** The image's Config.Env; empty when the image is gone or unreadable, so the container inspect still works. */
    private static List<ContainerDetailsDto.EnvVar> imageEnv(DockerClient client, String imageId) {
        if (imageId == null || imageId.isBlank()) return List.of();
        try {
            var imageConfig = client.inspectImageCmd(imageId).exec().getConfig();
            return imageConfig == null ? List.of() : parseEnv(imageConfig.getEnv());
        } catch (RuntimeException e) {
            log.debug("image env of {} unavailable: {}", imageId, e.toString());
            return List.of();
        }
    }

    private static List<ContainerDetailsDto.EnvVar> parseEnv(String[] entries) {
        List<ContainerDetailsDto.EnvVar> env = new ArrayList<>();
        if (entries == null) return env;
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            env.add(eq < 0 ? new ContainerDetailsDto.EnvVar(entry, "")
                    : new ContainerDetailsDto.EnvVar(entry.substring(0, eq), entry.substring(eq + 1)));
        }
        return env;
    }

    static ContainerDetailsDto toDetails(InspectContainerResponse r, List<ContainerDetailsDto.EnvVar> imageEnv) {
        var state = r.getState();
        var config = r.getConfig();
        Map<String, String> labels = config == null || config.getLabels() == null
                ? Map.of() : new TreeMap<>(config.getLabels());
        List<String> command = new ArrayList<>();
        if (r.getPath() != null) command.add(r.getPath());
        if (r.getArgs() != null) command.addAll(Arrays.asList(r.getArgs()));
        List<ContainerDetailsDto.EnvVar> env = config == null ? List.of() : parseEnv(config.getEnv());
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
        List<String> cmd = config == null || config.getCmd() == null ? List.of() : List.of(config.getCmd());
        boolean privileged = r.getHostConfig() != null && Boolean.TRUE.equals(r.getHostConfig().getPrivileged());
        boolean hostNetwork = r.getHostConfig() != null && "host".equals(r.getHostConfig().getNetworkMode());
        RestartPolicy policy = r.getHostConfig() == null ? null : r.getHostConfig().getRestartPolicy();
        return new ContainerDetailsDto(r.getId(), name, config == null ? null : config.getImage(),
                state == null ? null : state.getStatus(),
                instant(r.getCreated()), state == null ? null : instant(state.getStartedAt()),
                r.getRestartCount() == null ? 0 : r.getRestartCount(),
                new ContainerDetailsDto.RestartPolicy(policy == null || policy.getName() == null || policy.getName().isEmpty()
                        ? "no" : policy.getName(), policy == null || policy.getMaximumRetryCount() == null ? 0 : policy.getMaximumRetryCount()),
                command, cmd, privileged, hostNetwork, env, imageEnv, mounts, labels, networks, ports(settings == null ? null : settings.getPorts()),
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
    public CreatedResourceDto createContainer(UUID hostId, DockerEndpoint endpoint, ContainerCreateSpec spec) {
        return calls.call(hostId, endpoint, client -> {
            String id = createFrom(client, spec, spec.name(), HostConfig.newHostConfig(), null, null, null);
            if (spec.start()) {
                try {
                    client.startContainerCmd(id).exec();
                } catch (DockerException e) {
                    try {
                        client.removeContainerCmd(id).withForce(true).exec();
                    } catch (RuntimeException ignored) {
                        // best effort: the start failure is what the caller needs to hear about
                    }
                    throw new DockerConnectionException(DockerErrorCode.START_FAILED,
                            "The container could not be started.", e);
                }
            }
            return new CreatedResourceDto(id, spec.name());
        });
    }

    /**
     * Creates (does not start) a container from {@code spec} under {@code name}. {@code host} is the base host config
     * (fresh for create, the old container's for recreate, so settings the spec does not model survive); everything the
     * spec models is set on it explicitly. {@code old} (recreate only) supplies what the spec does not model: labels,
     * hostname (if it was set explicitly), tty/stdin and, when the image is unchanged, user, working dir, entrypoint
     * and healthcheck (with a new image these would pin the old image's defaults).
     */
    private static String createFrom(DockerClient client, ContainerCreateSpec spec, String name, HostConfig host,
                                     ContainerConfig old, String oldId, List<InspectContainerResponse.Mount> oldMounts) {
        host.withPrivileged(spec.privileged()).withMounts(List.of());
        Ports bindings = new Ports();
        List<ExposedPort> exposed = new ArrayList<>();
        for (ContainerCreateSpec.Port p : spec.ports()) {
            ExposedPort port = new ExposedPort(p.containerPort(),
                    "udp".equals(p.protocol()) ? InternetProtocol.UDP : InternetProtocol.TCP);
            exposed.add(port);
            bindings.bind(port, p.hostPort() == null ? Ports.Binding.empty() : Ports.Binding.bindPort(p.hostPort()));
        }
        host.withPortBindings(bindings);
        List<Bind> binds = new ArrayList<>(spec.mounts().stream()
                .map(m -> new Bind(m.source(), new Volume(m.target()), Boolean.TRUE.equals(m.readOnly()) ? AccessMode.ro : AccessMode.rw))
                .toList());
        binds.addAll(anonymousVolumeBinds(oldMounts, spec));
        host.withBinds(binds);
        host.withRestartPolicy(spec.restartPolicy() == null ? RestartPolicy.noRestart() : RestartPolicy.parse(spec.restartPolicy()));
        if (spec.hostNetwork()) {
            host.withNetworkMode("host");
        } else {
            host.withNetworkMode(spec.networks().isEmpty() ? "default" : spec.networks().get(0).name());
        }
        var cmd = client.createContainerCmd(spec.image()).withHostConfig(host).withExposedPorts(exposed)
                .withEnv(spec.env().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        if (name != null) {
            cmd.withName(name);
        }
        if (old != null) {
            if (old.getLabels() != null) {
                cmd.withLabels(old.getLabels());
            }
            if (old.getHostName() != null && oldId != null && !oldId.startsWith(old.getHostName())) {
                cmd.withHostName(old.getHostName());
            }
            cmd.withTty(old.getTty()).withStdinOpen(old.getStdinOpen());
            if (spec.image().equals(old.getImage())) {
                cmd.withUser(old.getUser()).withWorkingDir(old.getWorkingDir()).withHealthcheck(old.getHealthcheck());
                if (old.getEntrypoint() != null) {
                    cmd.withEntrypoint(old.getEntrypoint());
                }
            }
        }
        if (!spec.command().isEmpty()) {
            cmd.withCmd(spec.command());
        }
        String id;
        try {
            id = cmd.exec().getId();
        } catch (ConflictException e) {
            throw new DockerConnectionException(DockerErrorCode.NAME_TAKEN, "The container name is taken.", e);
        }
        if (!spec.hostNetwork()) {
            try {
                attachNetworks(client, id, spec.networks());
            } catch (RuntimeException e) {
                try {
                    client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
                } catch (RuntimeException ignored) {
                    // best effort: the attach failure is what the caller needs to hear about
                }
                throw e;
            }
        }
        return id;
    }

    /**
     * The first network is the create-time network mode (create cannot carry aliases, so a first network with aliases
     * is re-connected with them); every further network is connected with its aliases. The container is not started yet.
     */
    private static void attachNetworks(DockerClient client, String containerId, List<ContainerCreateSpec.Network> networks) {
        for (int i = 0; i < networks.size(); i++) {
            ContainerCreateSpec.Network n = networks.get(i);
            if (i == 0 && n.aliases().isEmpty()) {
                continue;
            }
            if (i == 0) {
                client.disconnectFromNetworkCmd().withNetworkId(n.name()).withContainerId(containerId).exec();
            }
            var cmd = client.connectToNetworkCmd().withNetworkId(n.name()).withContainerId(containerId);
            if (!n.aliases().isEmpty()) {
                cmd.withContainerNetwork(new ContainerNetwork().withAliases(n.aliases()));
            }
            cmd.exec();
        }
    }

    private static final Pattern ANONYMOUS_VOLUME = Pattern.compile("[0-9a-f]{64}");

    /**
     * Binds the old container's anonymous volumes (image VOLUME declarations, named by a 64-hex id) into the new one at
     * the same paths, so their data survives a recreate. Targets the spec mounts itself are left to the spec.
     */
    private static List<Bind> anonymousVolumeBinds(List<InspectContainerResponse.Mount> oldMounts, ContainerCreateSpec spec) {
        if (oldMounts == null) {
            return List.of();
        }
        Set<String> taken = spec.mounts().stream().map(ContainerCreateSpec.Mount::target).collect(Collectors.toSet());
        return oldMounts.stream()
                .filter(m -> m.getName() != null && ANONYMOUS_VOLUME.matcher(m.getName()).matches())
                .filter(m -> m.getDestination() != null && !taken.contains(m.getDestination().getPath()))
                .map(m -> new Bind(m.getName(), m.getDestination(), Boolean.FALSE.equals(m.getRW()) ? AccessMode.ro : AccessMode.rw))
                .toList();
    }

    private static final int RECREATE_STOP_SECONDS = 10;
    private static final Duration RECREATE_EXTRA = Duration.ofSeconds(RECREATE_STOP_SECONDS + 60);

    @Override
    public CreatedResourceDto recreateContainer(UUID hostId, DockerEndpoint endpoint, String id, ContainerCreateSpec spec) {
        return calls.call(hostId, endpoint, RECREATE_EXTRA, client -> {
            InspectContainerResponse old = client.inspectContainerCmd(id).exec();
            String name = stripSlash(old.getName());
            boolean running = old.getState() != null && Boolean.TRUE.equals(old.getState().getRunning());
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String oldName = name + OLD_NAME_MARKER + suffix + (running ? WAS_RUNNING_SUFFIX : "");
            HostConfig baseHost = old.getHostConfig() == null ? HostConfig.newHostConfig() : old.getHostConfig();
            String newId = createFrom(client, spec, name + "-shipir-new-" + suffix, baseHost, old.getConfig(), old.getId(), old.getMounts());
            List<String> addedVolumes = volumesAddedByNewImage(client, newId, old.getMounts());
            boolean stopped = running; // set before stopping: a failed stop may still have stopped it
            boolean oldRenamed = false;
            recreatesInFlight.add(oldName);
            try {
                if (running) {
                    try {
                        client.stopContainerCmd(id).withTimeout(RECREATE_STOP_SECONDS).exec();
                    } catch (NotModifiedException alreadyStopped) {
                        // fine
                    }
                }
                client.renameContainerCmd(id).withName(oldName).exec();
                oldRenamed = true;
                client.renameContainerCmd(newId).withName(name).exec();
                if (running) {
                    client.startContainerCmd(newId).exec();
                }
            } catch (RuntimeException e) {
                boolean restored = rollback(client, id, newId, name, running, stopped, oldRenamed, addedVolumes);
                throw new DockerConnectionException(DockerErrorCode.RECREATE_FAILED, restored
                        ? "The container could not be recreated; the previous container was restored."
                        : "The container could not be recreated and restoring the previous one failed.", e);
            } finally {
                recreatesInFlight.remove(oldName);
            }
            try {
                client.removeContainerCmd(id).withForce(true).withRemoveVolumes(false).exec();
            } catch (RuntimeException e) {
                log.warn("Recreated container {} but could not remove the old one ({}): {}", name, oldName, e.toString());
            }
            return new CreatedResourceDto(newId, name);
        });
    }

    @Override
    public List<RecreateLeftoverAction> reconcileRecreateLeftovers(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> {
            List<RecreateLeftoverAction> actions = new ArrayList<>();
            for (Container c : client.listContainersCmd().withShowAll(true).exec()) {
                String leftover = primaryName(c);
                if (leftover == null || recreatesInFlight.contains(leftover)) {
                    continue;
                }
                Matcher m = LEFTOVER_NAME.matcher(leftover);
                if (!m.matches()) {
                    continue;
                }
                String name = m.group(1);
                actions.add(new RecreateLeftoverAction(name, leftover,
                        reconcileLeftover(client, c.getId(), name, m.group(2) != null)));
            }
            return actions;
        });
    }

    /** Decides on a fresh look at the original name, not on the listing the leftover came from (it may be stale). */
    private static RecreateLeftoverAction.Outcome reconcileLeftover(DockerClient client, String id, String name,
                                                                    boolean wasRunning) {
        try {
            Container holder = client.listContainersCmd().withShowAll(true).withNameFilter(List.of(name)).exec().stream()
                    .filter(c -> name.equals(primaryName(c)))
                    .findFirst().orElse(null);
            if (holder != null) {
                boolean startHolder = wasRunning && !"running".equals(holder.getState());
                if (startHolder) {
                    client.startContainerCmd(holder.getId()).exec();
                }
                client.removeContainerCmd(id).withForce(true).withRemoveVolumes(false).exec();
                log.warn("Removed leftover container of an interrupted recreate; {} exists{}", name,
                        startHolder ? " and was started" : "");
                return startHolder ? RecreateLeftoverAction.Outcome.ORIGINAL_STARTED : RecreateLeftoverAction.Outcome.REMOVED;
            }
            client.renameContainerCmd(id).withName(name).exec();
            if (wasRunning) {
                client.startContainerCmd(id).exec();
            }
            log.warn("Renamed leftover container of an interrupted recreate back to {}{}", name,
                    wasRunning ? " and started it" : "");
            return wasRunning ? RecreateLeftoverAction.Outcome.RESTORED_AND_STARTED : RecreateLeftoverAction.Outcome.RESTORED;
        } catch (ConflictException | NotFoundException e) {
            log.info("Leftover of {} changed under us, leaving it for the next pass: {}", name, e.toString());
            return RecreateLeftoverAction.Outcome.SKIPPED;
        } catch (RuntimeException e) {
            log.error("Could not reconcile leftover container of {}: {}", name, e.toString());
            return RecreateLeftoverAction.Outcome.FAILED;
        }
    }

    private static String primaryName(Container c) {
        return c.getNames() == null || c.getNames().length == 0 ? null : stripSlash(c.getNames()[0]);
    }

    /**
     * Anonymous volumes the freshly created container got on its own (a VOLUME path only the new image declares), i.e.
     * the ones the old container never had. Only these may be removed on rollback; the old data must stay.
     */
    private static List<String> volumesAddedByNewImage(DockerClient client, String newId,
                                                       List<InspectContainerResponse.Mount> oldMounts) {
        try {
            Set<String> oldNames = oldMounts == null ? Set.of() : oldMounts.stream()
                    .map(InspectContainerResponse.Mount::getName).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<InspectContainerResponse.Mount> fresh = client.inspectContainerCmd(newId).exec().getMounts();
            if (fresh == null) {
                return List.of();
            }
            return fresh.stream().map(InspectContainerResponse.Mount::getName)
                    .filter(n -> n != null && ANONYMOUS_VOLUME.matcher(n).matches() && !oldNames.contains(n))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Could not determine the volumes created for the new container {}: {}", newId, e.toString());
            return List.of();
        }
    }

    /** Best effort, every step on its own: the new container goes, the old one gets its name and state back. */
    private static boolean rollback(DockerClient client, String oldId, String newId, String name, boolean wasRunning,
                                    boolean stopped, boolean oldRenamed, List<String> addedVolumes) {
        boolean ok = true;
        try {
            client.removeContainerCmd(newId).withForce(true).withRemoveVolumes(false).exec();
        } catch (RuntimeException e) {
            ok = false;
            log.error("Rollback: could not remove the new container {}: {}", newId, e.toString());
        }
        for (String volume : addedVolumes) {
            try {
                client.removeVolumeCmd(volume).exec();
            } catch (RuntimeException e) {
                log.warn("Rollback: could not remove the volume {} created for the new container: {}", volume, e.toString());
            }
        }
        if (oldRenamed) {
            try {
                client.renameContainerCmd(oldId).withName(name).exec();
            } catch (RuntimeException e) {
                ok = false;
                log.error("Rollback: could not rename the old container {} back to {}: {}", oldId, name, e.toString());
            }
        }
        if (wasRunning && stopped) {
            try {
                client.startContainerCmd(oldId).exec();
            } catch (NotModifiedException alreadyRunning) {
                // fine
            } catch (RuntimeException e) {
                ok = false;
                log.error("Rollback: could not restart the old container {}: {}", oldId, e.toString());
            }
        }
        return ok;
    }

    @Override
    public void updateResources(UUID hostId, DockerEndpoint endpoint, String id, ContainerResourcesSpec spec) {
        calls.call(hostId, endpoint, client -> {
            var cmd = client.updateContainerCmd(id);
            if (spec.memoryBytes() != null) {
                cmd.withMemory(spec.memoryBytes()).withMemorySwap(spec.memoryBytes() == 0 ? -1L : spec.memoryBytes());
            }
            if (spec.nanoCpus() != null) {
                cmd.withNanoCPUs(spec.nanoCpus());
            }
            if (spec.restartPolicy() != null) {
                cmd.withRestartPolicy(RestartPolicy.parse(spec.restartPolicy()));
            }
            try {
                cmd.exec();
            } catch (BadRequestException | InternalServerErrorException e) {
                throw new DockerConnectionException(DockerErrorCode.UPDATE_REJECTED,
                        "The daemon refused the new resource limits.", e);
            }
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

    @Override
    public List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> client.listContainersCmd().withShowAll(true).withShowSize(true)
                .exec().stream()
                .filter(c -> !Set.of("running", "paused", "restarting").contains(c.getState()))
                .map(c -> new PruneCandidateDto(c.getId(), toDto(c).name(), c.getSizeRw(), label(c, PROJECT_LABEL)))
                .toList());
    }
}
