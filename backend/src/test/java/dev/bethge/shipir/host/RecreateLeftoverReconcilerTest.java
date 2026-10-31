package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction.Outcome;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecreateLeftoverReconcilerTest {

    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver resolver = mock(HostEndpointResolver.class);
    private final ContainerService containers = mock(ContainerService.class);
    private final AuditService audit = mock(AuditService.class);
    private final RecreateLeftoverReconciler reconciler =
            new RecreateLeftoverReconciler(hosts, resolver, containers, audit);

    private DockerHost host(UUID id, String name) {
        DockerHost h = mock(DockerHost.class);
        when(h.getId()).thenReturn(id);
        when(h.getName()).thenReturn(name);
        when(hosts.findById(id)).thenReturn(Optional.of(h));
        when(resolver.endpointOf(h)).thenReturn(new DockerEndpoint.Local("unix:///" + name));
        return h;
    }

    @Test
    void auditsEachActionAsSystemWithTheHostAndBothNames() {
        UUID id = UUID.randomUUID();
        DockerHost h = host(id, "local");
        when(hosts.findAll()).thenReturn(List.of(h));
        when(containers.reconcileRecreateLeftovers(eq(id), any())).thenReturn(List.of(
                new RecreateLeftoverAction("web", "web-shipir-old-1a2b3c4d", Outcome.REMOVED),
                new RecreateLeftoverAction("db", "db-shipir-old-ffffffff", Outcome.RESTORED_AND_STARTED),
                new RecreateLeftoverAction("x", "x-shipir-old-00000000", Outcome.FAILED)));

        reconciler.reconcileAll();

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit, times(3)).record(captor.capture());
        AuditRecord removed = captor.getAllValues().get(0);
        assertThat(removed.userId()).isNull();
        assertThat(removed.username()).isEqualTo("system");
        assertThat(removed.action()).isEqualTo("CONTAINER_RECREATE_LEFTOVER_REMOVED");
        assertThat(removed.outcome()).isEqualTo(AuditLogEntry.Outcome.SUCCESS);
        assertThat(removed.targetType()).isEqualTo("HOST");
        assertThat(removed.targetId()).isEqualTo(id.toString());
        assertThat(removed.details()).containsEntry("container", "web")
                .containsEntry("leftover", "web-shipir-old-1a2b3c4d");
        assertThat(captor.getAllValues().get(1).action()).isEqualTo("CONTAINER_RECREATE_LEFTOVER_RESTORED");
        assertThat(captor.getAllValues().get(1).details()).containsEntry("started", true);
        assertThat(captor.getAllValues().get(2).action()).isEqualTo("CONTAINER_RECREATE_LEFTOVER_FAILED");
        assertThat(captor.getAllValues().get(2).outcome()).isEqualTo(AuditLogEntry.Outcome.FAILURE);
    }

    @Test
    void auditsAStartedOriginalAndDoesNotAuditASkippedLeftover() {
        UUID id = UUID.randomUUID();
        DockerHost h = host(id, "local");
        when(hosts.findAll()).thenReturn(List.of(h));
        when(containers.reconcileRecreateLeftovers(eq(id), any())).thenReturn(List.of(
                new RecreateLeftoverAction("web", "web-shipir-old-1a2b3c4d-r", Outcome.ORIGINAL_STARTED),
                new RecreateLeftoverAction("db", "db-shipir-old-ffffffff", Outcome.SKIPPED)));

        reconciler.reconcileAll();

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit, times(1)).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("CONTAINER_RECREATE_LEFTOVER_ORIGINAL_STARTED");
        assertThat(captor.getValue().username()).isEqualTo("system");
    }

    @Test
    void anUnreachableHostIsSkippedAndTheNextHostIsStillReconciled() {
        UUID down = UUID.randomUUID();
        UUID up = UUID.randomUUID();
        DockerHost downHost = host(down, "down");
        DockerHost upHost = host(up, "up");
        when(hosts.findAll()).thenReturn(List.of(downHost, upHost));
        when(containers.reconcileRecreateLeftovers(eq(down), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "refused", null));
        when(containers.reconcileRecreateLeftovers(eq(up), any())).thenReturn(List.of(
                new RecreateLeftoverAction("web", "web-shipir-old-1a2b3c4d", Outcome.RESTORED)));

        assertThatCode(reconciler::reconcileAll).doesNotThrowAnyException();

        verify(audit, times(1)).record(any());
    }

    @Test
    void anEndpointThatCannotBeResolvedDoesNotCrash() {
        UUID id = UUID.randomUUID();
        DockerHost h = host(id, "bad");
        when(resolver.endpointOf(h)).thenThrow(new IllegalStateException("cannot decrypt"));
        when(hosts.findAll()).thenReturn(List.of(h));

        assertThatCode(reconciler::reconcileAll).doesNotThrowAnyException();
        verify(audit, never()).record(any());
    }

    @Test
    void aHostComingUpIsReconciledButAHostGoingDownIsNot() {
        UUID id = UUID.randomUUID();
        host(id, "local");
        when(containers.reconcileRecreateLeftovers(eq(id), any())).thenReturn(List.of());

        reconciler.reconcileOnStatusChange(new HostStatusChangedEvent(id, "local", Status.UP, Status.DOWN, "UNREACHABLE", Instant.now()));
        verify(containers, never()).reconcileRecreateLeftovers(any(), any());

        reconciler.reconcileOnStatusChange(new HostStatusChangedEvent(id, "local", Status.DOWN, Status.UP, null, Instant.now()));
        verify(containers, times(1)).reconcileRecreateLeftovers(eq(id), any());
    }
}
