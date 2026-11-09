package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.common.security.HostRemovedEvent;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.proxy.ProxyCertificateRepository;
import dev.bethge.shipir.proxy.ProxyHostAccess;
import dev.bethge.shipir.proxy.ProxyInstanceRepository;
import dev.bethge.shipir.proxy.ProxyRouteRepository;
import dev.bethge.shipir.proxy.ProxyRouteService;
import dev.bethge.shipir.proxy.ProxySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** A removed host's cached proxy status is dropped by the {@link HostRemovedEvent} listener of {@link ProxyStatusService}. */
class ProxyStatusHostRemovalTest {

    private final UUID host = UUID.randomUUID();
    private final AtomicInteger loads = new AtomicInteger();
    private final ProxyStatusService service = new ProxyStatusService(
            mock(ProxyInstanceRepository.class), mock(ProxyCertificateRepository.class), mock(ProxyRouteService.class),
            mock(ProxyRouteRepository.class), mock(ProxyHostAccess.class), mock(ProxySyncService.class),
            mock(ContainerFileService.class), mock(ContainerLogService.class),
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5));

    private ProxyFacts load() {
        loads.incrementAndGet();
        return new ProxyFacts(List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void hostRemovedEventEvictsItsCachedStatus() {
        StatusCache cache = (StatusCache) ReflectionTestUtils.getField(service, "cache");
        cache.get(host, false, this::load);

        service.onHostRemoved(new HostRemovedEvent(host));

        cache.get(host, false, this::load);
        assertThat(loads).as("the status is read again for a host that is gone").hasValue(2);
    }
}
