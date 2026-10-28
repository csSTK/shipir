package dev.bethge.shipir.compose;

import dev.bethge.shipir.permission.CurrentAccess;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link DeploymentService#visibleHostId}: a deployment the user may not see behaves as not found. */
class DeploymentServiceVisibilityTest {

    private final DeploymentRepository deployments = mock(DeploymentRepository.class);
    private final CurrentAccess access = mock(CurrentAccess.class);
    private final DeploymentService service = new DeploymentService(deployments, null, null, null, null, null, null,
            null, null, null, mock(PlatformTransactionManager.class), null, null, access, null, null);
    private final UUID hostId = UUID.randomUUID();
    private final Deployment deployment = withId(
            new Deployment("web", hostId, "web/compose.yaml", UUID.randomUUID(), Instant.now()));

    private static Deployment withId(Deployment d) {
        org.springframework.test.util.ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        return d;
    }

    @Test
    void viewableDeploymentReturnsItsHost() {
        when(deployments.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        when(access.canView(hostId, deployment.getId())).thenReturn(true);

        assertThat(service.visibleHostId(deployment.getId())).contains(hostId);
    }

    @Test
    void notViewableDeploymentIsEmpty() {
        when(deployments.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        when(access.canView(hostId, deployment.getId())).thenReturn(false);

        assertThat(service.visibleHostId(deployment.getId())).isEmpty();
    }

    @Test
    void unknownDeploymentIsEmpty() {
        UUID id = UUID.randomUUID();
        when(deployments.findById(id)).thenReturn(Optional.empty());

        assertThat(service.visibleHostId(id)).isEmpty();
    }
}
