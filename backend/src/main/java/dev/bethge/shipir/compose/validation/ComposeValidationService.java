package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Validates a stored stack with {@code docker compose config --quiet}. */
@Service
public class ComposeValidationService {

    private final ComposeExecutionService execution;

    public ComposeValidationService(ComposeExecutionService execution) {
        this.execution = execution;
    }

    public ValidationResult validate(UUID hostId, String stackName, DockerEndpoint endpoint) {
        return validate(hostId, stackName, endpoint, null);
    }

    /** Validates the compose file at {@code composeFile} inside the stack directory (Git checkout); null = default. */
    public ValidationResult validate(UUID hostId, String stackName, DockerEndpoint endpoint, java.nio.file.Path composeFile) {
        ComposeProcessResult result = execution.execute(hostId, stackName, ComposeCommand.CONFIG, endpoint, line -> { },
                composeFile);
        if (result.success()) {
            return new ValidationResult(true, List.of());
        }
        List<String> errors = result.output().stream().filter(l -> !l.isBlank()).toList();
        if (result.timedOut()) {
            errors = List.of("Validation timed out");
        } else if (errors.isEmpty()) {
            errors = List.of("docker compose config failed with exit code " + result.exitCode());
        }
        return new ValidationResult(false, errors);
    }
}
