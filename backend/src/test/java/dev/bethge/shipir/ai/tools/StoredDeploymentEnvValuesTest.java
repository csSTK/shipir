package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.dto.DeploymentEnvResponse;
import dev.bethge.shipir.compose.dto.EnvEntry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The AI redaction lookup goes through {@code DeploymentService.env}, so it gets the same OPERATE check as the REST endpoint. */
class StoredDeploymentEnvValuesTest {

    private final DeploymentService deployments = mock(DeploymentService.class);
    private final StoredDeploymentEnvValues values = new StoredDeploymentEnvValues(deployments);
    private final UUID host = UUID.randomUUID();
    private final UUID deployment = UUID.randomUUID();
    private final Map<String, Object> args = Map.of("hostId", host.toString(), "deploymentId", deployment.toString());

    @Test
    void returnsValuesWhenTheCallerMayOperate() {
        when(deployments.env(host, deployment)).thenReturn(new DeploymentEnvResponse(List.of(new EnvEntry("K", "s3cr3t"))));
        assertThat(values.valuesFor(args)).containsExactly("s3cr3t");
    }

    @Test
    void failsClosedWhenTheCallerHasOnlyViewAccess() {
        when(deployments.env(any(), any())).thenThrow(new DeploymentException(HttpStatus.FORBIDDEN,
                "ENV_VALUES_RESTRICTED", "restricted"));
        assertThatThrownBy(() -> values.valuesFor(args)).isInstanceOf(DeploymentEnvValues.Unavailable.class);
    }
}
