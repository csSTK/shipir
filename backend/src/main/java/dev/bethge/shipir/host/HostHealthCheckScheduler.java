package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.host.dto.HostOverviewResponse;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically checks all hosts via {@link HostOverviewService} (parallel, shared deadline) and publishes a
 * {@link HostStatusChangedEvent} on each UP/DOWN transition. The first observation of a host is a baseline
 * without event. Disable with {@code shipir.host.health-check-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "shipir.host.health-check-enabled", matchIfMissing = true)
class HostHealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HostHealthCheckScheduler.class);

    private final HostOverviewService overview;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<UUID, Status> lastKnown = new ConcurrentHashMap<>();

    HostHealthCheckScheduler(HostOverviewService overview, ApplicationEventPublisher events, Clock clock) {
        this.overview = overview;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipir.host.health-check-interval:60s}",
            initialDelayString = "${shipir.host.health-check-interval:60s}")
    synchronized void check() {
        List<HostOverviewResponse> current = SystemScope.call(overview::overview);
        Map<UUID, Status> seen = new HashMap<>();
        for (HostOverviewResponse h : current) {
            seen.put(h.id(), h.status());
            Status previous = lastKnown.put(h.id(), h.status());
            if (previous != null && previous != h.status()) {
                try {
                    events.publishEvent(new HostStatusChangedEvent(h.id(), h.name(), previous, h.status(),
                            h.error(), clock.instant()));
                } catch (RuntimeException e) {
                    log.warn("Publishing the status change of host {} failed", h.id(), e);
                }
            }
        }
        lastKnown.keySet().retainAll(seen.keySet());
    }
}
