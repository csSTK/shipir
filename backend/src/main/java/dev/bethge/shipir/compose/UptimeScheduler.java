package dev.bethge.shipir.compose;

import dev.bethge.shipir.common.security.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the enabled uptime checks that are due (last check older than the check's interval) on virtual threads, one per
 * check, so a slow or hanging target delays no other. Each probe has its own hard timeout (see {@link UptimeProbe});
 * a failing check never affects the others. The state machine (DOWN after N consecutive failures, UP after one
 * success, notification only on a change) lives in {@link UptimeCheckService#record}. Results older than
 * {@code shipir.uptime.result-retention} are deleted at most once an hour (no {@code retention_settings} column for this).
 * Disable with {@code shipir.uptime.enabled=false}.
 */
@Component
@EnableConfigurationProperties(UptimeProperties.class)
@ConditionalOnProperty(name = "shipir.uptime.enabled", matchIfMissing = true)
class UptimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(UptimeScheduler.class);
    private static final java.time.Duration PURGE_EVERY = java.time.Duration.ofHours(1);

    private final UptimeCheckRepository checks;
    private final UptimeCheckService service;
    private final UptimeProbe probe;
    private final UptimeProperties properties;
    private final Clock clock;
    private Instant lastPurge = Instant.MIN;

    UptimeScheduler(UptimeCheckRepository checks, UptimeCheckService service, UptimeProbe probe,
                    UptimeProperties properties, Clock clock) {
        this.checks = checks;
        this.service = service;
        this.probe = probe;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipir.uptime.tick:10s}", initialDelayString = "${shipir.uptime.tick:10s}")
    synchronized void tick() {
        SystemScope.run(this::runDue);
    }

    private void runDue() {
        Instant now = clock.instant();
        List<UptimeCheck> due;
        try {
            due = checks.findByEnabledTrue().stream().filter(c -> isDue(c, now)).toList();
        } catch (RuntimeException e) {
            log.warn("Uptime checks could not be loaded: {}", e.getClass().getSimpleName());
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (UptimeCheck check : due) {
                running.add(executor.submit(() -> runOne(check, now)));
            }
            for (Future<?> f : running) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException e) {
                    // runOne already logs and swallows; nothing escapes, but never let one future end the loop
                }
            }
        }
        purgeOldResults(now);
    }

    private static boolean isDue(UptimeCheck check, Instant now) {
        return check.getLastCheckedAt() == null
                || !check.getLastCheckedAt().plusSeconds(check.getIntervalSeconds()).isAfter(now);
    }

    private void runOne(UptimeCheck check, Instant now) {
        try {
            UptimeProbe.ProbeResult result = probe.probe(check.getUrl(), check.getExpectedStatus(), check.getTimeoutSeconds());
            service.record(check.getId(), result, now);
        } catch (RuntimeException e) {
            // only the id and the exception type: the message could contain the URL
            log.warn("Uptime check {} failed unexpectedly: {}", check.getId(), e.getClass().getSimpleName());
        }
    }

    private void purgeOldResults(Instant now) {
        if (lastPurge.plus(PURGE_EVERY).isAfter(now)) {
            return;
        }
        try {
            service.purgeResultsOlderThan(now.minus(properties.resultRetention()));
            lastPurge = now;
        } catch (RuntimeException e) {
            log.warn("Uptime result purge failed: {}", e.getClass().getSimpleName());
        }
    }
}
