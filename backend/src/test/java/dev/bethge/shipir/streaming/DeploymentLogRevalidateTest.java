package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link DeploymentLogStreamService#revalidate} / {@code dropHost}: per user, fail closed per stream. */
class DeploymentLogRevalidateTest {

    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final DeploymentLogStreamService service = new DeploymentLogStreamService(mock(ContainerService.class),
            mock(ContainerLogService.class), hosts, mock(HostEndpointResolver.class),
            mock(StompFrameSender.class),
            new DeploymentLogProperties(100, 10, 8192, 100, 1000, 100, Duration.ofHours(1)), Clock.systemUTC(),
            mock(StreamViewers.class));
    {
        // a known host keeps the workers alive (an unknown one ends the stream on its own)
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
    }

    private final UUID host = UUID.randomUUID();
    private final DeploymentLogStreamService.Options options = new DeploymentLogStreamService.Options(100, false);

    private DeploymentLogStreamService.Target target(String project) {
        return new DeploymentLogStreamService.Target(host, project);
    }

    @Test
    void dropsOnlyTheRevokedUsersSubscriptionAndKeepsOthers() {
        var shop = target("shop");
        service.subscribe(shop, options, "alice", "sa", "1");
        service.subscribe(shop, options, "bob", "sb", "1");

        service.revalidate("alice", t -> false);

        assertThat(service.subscriberCount(shop)).isEqualTo(1);
    }

    @Test
    void stillAllowedSubscriptionsStay() {
        var shop = target("shop");
        service.subscribe(shop, options, "alice", "sa", "1");
        service.revalidate("alice", t -> true);
        assertThat(service.subscriberCount(shop)).isEqualTo(1);
    }

    @Test
    void throwingPredicateDropsThatStreamAndStillChecksTheOthers() {
        var broken = target("broken");
        var kept = target("kept");
        var gone = target("gone");
        service.subscribe(broken, options, "alice", "sa", "1");
        service.subscribe(gone, options, "alice", "sa", "2");
        service.subscribe(kept, options, "alice", "sa", "3");

        service.revalidate("alice", t -> {
            if (t.equals(broken)) {
                throw new IllegalStateException("docker unreachable");
            }
            return t.equals(kept);
        });

        assertThat(service.subscriberCount(broken)).isZero();
        assertThat(service.subscriberCount(gone)).isZero();
        assertThat(service.subscriberCount(kept)).isEqualTo(1);
    }

    @Test
    void dropHostRemovesEveryonesSubscriptionsOfThatHostOnly() {
        var other = new DeploymentLogStreamService.Target(UUID.randomUUID(), "shop");
        service.subscribe(target("shop"), options, "alice", "sa", "1");
        service.subscribe(target("shop"), options, "bob", "sb", "1");
        service.subscribe(other, options, "alice", "sa", "2");

        service.dropHost(host);

        assertThat(service.subscriberCount(target("shop"))).isZero();
        assertThat(service.subscriberCount(other)).isEqualTo(1);
    }
}
