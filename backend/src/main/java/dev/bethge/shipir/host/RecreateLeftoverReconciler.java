package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cleans up containers an interrupted recreate left behind ({@code *-shipir-old-*}, see
 * {@link ContainerService#reconcileRecreateLeftovers}) on every host: once at application start and again whenever a
 * host turns UP. An unreachable host is skipped; every action is logged and audited as {@code system}. Assumes a
 * single Shipir instance per Docker host (a second instance's running recreate would look like a leftover). Runs on a
 * virtual thread so a slow host never delays startup or the health check. Disable with
 * {@code shipir.host.recreate-reconcile-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "shipir.host.recreate-reconcile-enabled", matchIfMissing = true)
class RecreateLeftoverReconciler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecreateLeftoverReconciler.class);
    private static final String SYSTEM_USER = "system";

    private final DockerHostRepository hosts;
    private final HostEndpointResolver resolver;
    private final ContainerService containers;
    private final AuditService audit;

    RecreateLeftoverReconciler(DockerHostRepository hosts, HostEndpointResolver resolver, ContainerService containers,
                               AuditService audit) {
        this.hosts = hosts;
        this.resolver = resolver;
        this.containers = containers;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        Thread.startVirtualThread(this::reconcileAll);
    }

    @EventListener
    void onHostStatusChanged(HostStatusChangedEvent event) {
        if (event.current() == Status.UP) {
            Thread.startVirtualThread(() -> reconcileOnStatusChange(event));
        }
    }

    void reconcileOnStatusChange(HostStatusChangedEvent event) {
        if (event.current() == Status.UP) {
            hosts.findById(event.hostId()).ifPresent(this::reconcile);
        }
    }

    void reconcileAll() {
        try {
            hosts.findAll().forEach(this::reconcile);
        } catch (RuntimeException e) {
            log.warn("Recreate leftover reconciliation failed: {}", e.toString());
        }
    }

    private void reconcile(DockerHost host) {
        try {
            for (RecreateLeftoverAction action : SystemScope.call(
                    () -> containers.reconcileRecreateLeftovers(host.getId(), resolver.endpointOf(host)))) {
                record(host, action);
            }
        } catch (RuntimeException e) {
            // unreachable, denied or undecryptable: nothing to reconcile now, the next UP transition retries
            log.info("Skipping recreate leftover reconciliation on host {}: {}", host.getName(), e.getClass().getSimpleName());
        }
    }

    private void record(DockerHost host, RecreateLeftoverAction action) {
        if (action.outcome() == RecreateLeftoverAction.Outcome.SKIPPED) {
            log.info("Skipped recreate leftover {} on host {}: original name taken", action.leftoverName(), host.getName());
            return;
        }
        boolean failed = action.outcome() == RecreateLeftoverAction.Outcome.FAILED;
        String auditAction = switch (action.outcome()) {
            case REMOVED -> "CONTAINER_RECREATE_LEFTOVER_REMOVED";
            case ORIGINAL_STARTED -> "CONTAINER_RECREATE_LEFTOVER_ORIGINAL_STARTED";
            case SKIPPED -> throw new IllegalStateException();
            case RESTORED, RESTORED_AND_STARTED -> "CONTAINER_RECREATE_LEFTOVER_RESTORED";
            case FAILED -> "CONTAINER_RECREATE_LEFTOVER_FAILED";
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("host", host.getName());
        details.put("container", action.name());
        details.put("leftover", action.leftoverName());
        if (action.outcome() == RecreateLeftoverAction.Outcome.RESTORED
                || action.outcome() == RecreateLeftoverAction.Outcome.RESTORED_AND_STARTED) {
            details.put("started", action.outcome() == RecreateLeftoverAction.Outcome.RESTORED_AND_STARTED);
        }
        try {
            audit.record(new AuditRecord(null, SYSTEM_USER, auditAction,
                    failed ? AuditLogEntry.Outcome.FAILURE : AuditLogEntry.Outcome.SUCCESS,
                    "HOST", host.getId().toString(), details));
        } catch (RuntimeException e) {
            log.warn("Could not audit recreate leftover action {} on host {}: {}", auditAction, host.getName(), e.toString());
        }
    }
}
