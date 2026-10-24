package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Removes a live Compose project Shipir does not manage with {@code docker compose -p <name> down} (no files needed).
 * Managed projects are refused: they are removed through the managed delete, which also cleans up files and history.
 */
@Service
public class UnmanagedDeploymentService {

    private static final int MAX_ERROR_LINES = 20;

    private final DeploymentService deploymentService;
    private final DeploymentRepository deployments;
    private final ComposeExecutionService execution;

    UnmanagedDeploymentService(DeploymentService deploymentService, DeploymentRepository deployments,
                               ComposeExecutionService execution) {
        this.deploymentService = deploymentService;
        this.deployments = deployments;
        this.execution = execution;
    }

    @Auditable(action = "DEPLOYMENT_REMOVE_UNMANAGED", targetType = "DEPLOYMENT", targetId = "#projectName")
    public void remove(UUID hostId, String projectName, boolean removeVolumes) {
        deploymentService.requireHostOperate(hostId);
        var host = deploymentService.requireHost(hostId);
        try {
            PathValidationUtil.validateDeploymentName(projectName);
        } catch (PathValidationException e) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_DEPLOYMENT_NAME",
                    "The name must be 1-63 characters: lowercase letters, digits, - and _, starting with a letter or digit");
        }
        if (deployments.findByHostIdOrderByNameAsc(hostId).stream().anyMatch(d -> d.getName().equals(projectName))) {
            throw new DeploymentException(HttpStatus.CONFLICT, "MANAGED_DEPLOYMENT",
                    "This deployment is managed by Shipir; delete it as a managed deployment");
        }
        DockerEndpoint endpoint = deploymentService.endpointOf(host);
        ComposeProcessResult result;
        try {
            result = execution.executeProject(projectName, removeVolumes ? ComposeCommand.DOWN_VOLUMES : ComposeCommand.DOWN,
                    endpoint, line -> { });
        } catch (ComposeExecutionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED", "docker compose down could not run");
        }
        if (!result.success()) {
            List<String> output = result.output().stream().filter(l -> !l.isBlank()).toList();
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                    result.timedOut() ? "docker compose down timed out" : "docker compose down failed",
                    output.subList(Math.max(0, output.size() - MAX_ERROR_LINES), output.size()));
        }
    }
}
