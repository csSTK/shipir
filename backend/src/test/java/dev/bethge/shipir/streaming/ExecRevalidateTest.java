package dev.bethge.shipir.streaming;

import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import dev.bethge.shipir.host.ManagedDeploymentLookup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ExecSessionHandler#revalidate}: a throwing predicate ends that console and the others are still checked. */
class ExecRevalidateTest {

    private final ExecService exec = mock(ExecService.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final ExecSessionHandler handler = new ExecSessionHandler(exec, hosts, mock(HostEndpointResolver.class),
            mock(UserRepository.class), mock(StreamAccess.class), mock(ContainerService.class),
            mock(ManagedDeploymentLookup.class), mock(StompFrameSender.class), mock(AuditService.class),
            new ExecProperties(Duration.ofMinutes(15), 5, 1024, 1024, Duration.ofSeconds(10), false),
            JsonMapper.builder().build(), Clock.systemUTC());

    @Test
    void throwingPredicateEndsThatConsoleAndStillChecksTheOthers() {
        ExecHandle broken = mock(ExecHandle.class);
        ExecHandle kept = mock(ExecHandle.class);
        ExecHandle gone = mock(ExecHandle.class);
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(exec.open(any(), any(), anyString(), anyList(), anyInt(), anyInt(), any(), any()))
                .thenReturn(broken, gone, kept);
        UUID brokenHost = UUID.randomUUID();
        UUID goneHost = UUID.randomUUID();
        UUID keptHost = UUID.randomUUID();
        handler.start(brokenHost, "c1", null, null, null, "alice", Role.ADMIN, "h");
        handler.start(goneHost, "c2", null, null, null, "alice", Role.ADMIN, "h");
        handler.start(keptHost, "c3", null, null, null, "alice", Role.ADMIN, "h");

        handler.revalidate("alice", (host, deployment) -> {
            if (host.equals(brokenHost)) {
                throw new IllegalStateException("db down");
            }
            return host.equals(keptHost);
        });

        try {
            verify(broken).close();
            verify(gone).close();
            verify(kept, never()).close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
