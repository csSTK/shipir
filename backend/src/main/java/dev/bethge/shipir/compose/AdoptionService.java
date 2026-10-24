package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.dto.AdoptRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.execution.ComposeProperties;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.host.ConnectionType;
import dev.bethge.shipir.host.DockerHost;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brings a live Compose project that Shipir did not start under management. The project name is kept, so the containers
 * stay untouched and the next deploy reconciles them. The compose file is either pasted by the caller or, on a LOCAL host
 * only, read from the path in the containers' {@code config_files} label, and only when that path lies inside one of
 * {@code shipir.compose.adopt-allowed-dirs} (default none). Every read goes through {@link PathValidationUtil}.
 */
@Service
public class AdoptionService {

    static final String CONFIG_FILES_LABEL = "com.docker.compose.project.config_files";
    static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";
    private static final long MAX_FILE_BYTES = 500_000;

    private final DeploymentService deployments;
    private final DeploymentRepository repository;
    private final ContainerService containers;
    private final ComposeProperties properties;

    AdoptionService(DeploymentService deployments, DeploymentRepository repository, ContainerService containers,
                    ComposeProperties properties) {
        this.deployments = deployments;
        this.repository = repository;
        this.containers = containers;
        this.properties = properties;
    }

    @Auditable(action = "DEPLOYMENT_ADOPT", targetType = "DEPLOYMENT", targetId = "#result?.id")
    public DeploymentDetailResponse adopt(UUID hostId, String projectName, AdoptRequest request, String username) {
        deployments.requireHostOperate(hostId);
        DockerHost host = deployments.requireHost(hostId);
        String name = projectName;
        try {
            PathValidationUtil.validateDeploymentName(name);
        } catch (PathValidationException e) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_DEPLOYMENT_NAME",
                    "The name must be 1-63 characters: lowercase letters, digits, - and _, starting with a letter or digit");
        }
        if (repository.findByHostIdOrderByNameAsc(hostId).stream().anyMatch(d -> d.getName().equals(name))) {
            throw new DeploymentException(HttpStatus.CONFLICT, "DEPLOYMENT_NAME_TAKEN",
                    "A deployment with this name already exists on this environment");
        }
        var endpoint = deployments.endpointOf(host);
        List<ContainerDto> members;
        try {
            members = containers.listContainers(hostId, endpoint).stream()
                    .filter(c -> name.equals(c.composeProject())).toList();
        } catch (DockerConnectionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "The Docker host could not be reached");
        }
        if (members.isEmpty()) {
            throw new DeploymentException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                    "No Compose project with this name is running on this environment");
        }

        String yaml = request == null ? null : request.yaml();
        List<EnvEntry> env = request == null ? null : request.env();
        if (yaml == null || yaml.isBlank()) {
            Source source = readFromHost(hostId, host, endpoint, members.get(0));
            yaml = source.yaml();
            if (env == null) {
                env = source.env();
            }
        }
        return deployments.createFile(hostId, host, name, yaml, env, username, VersionSource.ADOPTED);
    }

    private record Source(String yaml, List<EnvEntry> env) { }

    private Source readFromHost(UUID hostId, DockerHost host, DockerEndpoint endpoint,
                                ContainerDto container) {
        if (host.getConnectionType() != ConnectionType.LOCAL || properties.adoptAllowedDirs().isEmpty()) {
            throw yamlRequired("Paste the compose file; reading it from the host is not enabled for this environment");
        }
        Map<String, String> labels;
        try {
            labels = containers.inspect(hostId, endpoint, container.id()).labels();
        } catch (DockerConnectionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "The Docker host could not be reached");
        }
        String configFiles = labels == null ? null : labels.get(CONFIG_FILES_LABEL);
        if (configFiles == null || configFiles.isBlank()) {
            throw yamlRequired("The project has no recorded compose file; paste it");
        }
        if (configFiles.contains(",")) {
            throw yamlRequired("The project uses several compose files; paste the merged file");
        }
        Path file = readable(configFiles.trim());
        String yaml = readText(file);
        List<EnvEntry> env = null;
        String workingDir = labels.get(WORKING_DIR_LABEL);
        Path envFile = (workingDir == null || workingDir.isBlank()) ? file.resolveSibling(".env")
                : Path.of(workingDir.trim()).resolve(".env");
        try {
            Path guarded = guard(envFile.toString());
            if (Files.isRegularFile(guarded)) {
                env = EnvFile.parse(readText(guarded));
            }
        } catch (DeploymentException e) {
            // an unreadable or disallowed .env is simply not adopted; the caller can paste env values
        }
        return new Source(yaml, env);
    }

    private Path readable(String label) {
        Path file = guard(label);
        if (!Files.isRegularFile(file)) {
            throw yamlRequired("The recorded compose file is not available on this host; paste it");
        }
        return file;
    }

    /** Resolves an absolute path from a label against the allow-list; anything outside, or an escape via symlink, is refused. */
    private Path guard(String raw) {
        Path path;
        try {
            path = Path.of(raw);
        } catch (RuntimeException e) {
            throw notAllowed();
        }
        if (!path.isAbsolute() || raw.indexOf('\0') >= 0) {
            throw notAllowed();
        }
        Path normalized = path.normalize();
        for (Path allowed : properties.adoptAllowedDirs()) {
            Path base = allowed.toAbsolutePath().normalize();
            if (normalized.startsWith(base) && !normalized.equals(base)) {
                try {
                    return PathValidationUtil.resolve(base, base.relativize(normalized).toString());
                } catch (PathValidationException e) {
                    throw notAllowed();
                }
            }
        }
        throw notAllowed();
    }

    private static String readText(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw yamlRequired("The compose file is too large to adopt; paste it");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw yamlRequired("The recorded compose file could not be read; paste it");
        }
    }

    private static DeploymentException notAllowed() {
        return new DeploymentException(HttpStatus.FORBIDDEN, "PATH_NOT_ALLOWED",
                "The project's compose file is outside the directories allowed for adoption; paste it");
    }

    private static DeploymentException yamlRequired(String message) {
        return new DeploymentException(HttpStatus.BAD_REQUEST, "YAML_REQUIRED", message);
    }
}
