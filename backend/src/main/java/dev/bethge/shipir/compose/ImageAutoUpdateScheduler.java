package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.host.ImageReference;
import dev.bethge.shipir.host.ImageUpdates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;

/**
 * Applies pending image updates to FILE deployments that opted in ({@code image_auto_update}), inside their daily UTC
 * window. The update check itself is not repeated: it reads the result of {@code ImageUpdateScheduler} through
 * {@link ImageUpdates}. A deployment is attempted at most once per check result (the last attempt must be older than the
 * host's last check), so a failed update is not retried every tick but again after the next check.
 *
 * <p>Per deployment: {@code docker compose pull} + {@code up -d} as the system (the deployment lock is respected, a busy
 * deployment is skipped without a failure), then wait until the containers are healthy (or, without healthcheck, running
 * for {@code stableFor}). If they are not, the previous image digests are pinned again ({@link
 * DeploymentRunService#rollbackImages}). The outcome is audited as {@code IMAGE_AUTO_UPDATE} and published as
 * {@link ImageAutoUpdateEvent}. A failing deployment never stops the others. The tick is sequential, so one waiting
 * health check delays the next deployment by at most the health timeout.
 *
 * <p>Git deployments are excluded: they have their own auto-update, which redeploys from the repository.
 */
@Component
@EnableConfigurationProperties(ImageAutoUpdateProperties.class)
@ConditionalOnProperty(name = "shipir.image-auto-update.enabled", matchIfMissing = true)
class ImageAutoUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImageAutoUpdateScheduler.class);
    private static final String ACTION = "IMAGE_AUTO_UPDATE";

    private final DeploymentRepository deployments;
    private final ImageUpdates updates;
    private final ContainerService containers;
    private final ImageService images;
    private final DeploymentService deploymentService;
    private final DeploymentRunService runs;
    private final DeploymentLocks locks;
    private final Clock clock;
    private final ImageAutoUpdateProperties properties;
    private final LongConsumer sleeperMillis;

    @Autowired
    ImageAutoUpdateScheduler(DeploymentRepository deployments, ImageUpdates updates, ContainerService containers,
                             ImageService images, DeploymentService deploymentService, DeploymentRunService runs,
                             DeploymentLocks locks, Clock clock, ImageAutoUpdateProperties properties) {
        this(deployments, updates, containers, images, deploymentService, runs, locks, clock, properties, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Test seam: the pause between health checks. */
    ImageAutoUpdateScheduler(DeploymentRepository deployments, ImageUpdates updates, ContainerService containers,
                             ImageService images, DeploymentService deploymentService, DeploymentRunService runs,
                             DeploymentLocks locks, Clock clock, ImageAutoUpdateProperties properties,
                             LongConsumer sleeperMillis) {
        this.deployments = deployments;
        this.updates = updates;
        this.containers = containers;
        this.images = images;
        this.deploymentService = deploymentService;
        this.runs = runs;
        this.locks = locks;
        this.clock = clock;
        this.properties = properties;
        this.sleeperMillis = sleeperMillis;
    }

    @Scheduled(fixedDelayString = "${shipir.image-auto-update.tick:60s}",
            initialDelayString = "${shipir.image-auto-update.tick:60s}")
    synchronized void tick() {
        for (Deployment deployment : deployments.findByImageAutoUpdateTrue()) {
            if (Thread.currentThread().isInterrupted()) {
                return; // shutting down
            }
            try {
                process(deployment);
            } catch (RuntimeException e) {
                log.warn("Image auto-update of deployment {} skipped: {}", deployment.getId(), e.getClass().getSimpleName());
            }
        }
    }

    private void process(Deployment deployment) {
        if (deployment.getSourceType() != DeploymentSource.FILE || deployment.getStatus() != DeploymentStatus.RUNNING) {
            return;
        }
        Instant now = clock.instant();
        if (!ImageUpdateWindow.open(deployment.imageUpdateWindow(), now)) {
            return;
        }
        UUID hostId = deployment.getHostId();
        Instant checkedAt = updates.checkedAt(hostId);
        Instant lastAttempt = deployment.getImageUpdateLastAttemptAt();
        if (checkedAt == null || (lastAttempt != null && !checkedAt.isAfter(lastAttempt))) {
            return;
        }
        try {
            locks.acquire(deployment.getId());
        } catch (DeploymentException e) {
            return; // busy: not a failure, tried again on the next tick
        }
        // The lock is held from the digest snapshot to the end of a possible rollback, so no manual deploy, rollback or
        // stop can run in between and have its result overwritten by pinning the old digests.
        AtomicBoolean handedOver = new AtomicBoolean();
        try {
            Deployment current = deployments.findById(deployment.getId()).orElse(null);
            if (current != null && current.getStatus() == DeploymentStatus.RUNNING) {
                update(current, now, handedOver);
            }
        } finally {
            // exactly one release per path: here, or from the worker's completion once handed over
            if (!handedOver.get()) {
                locks.release(deployment.getId());
            }
        }
    }

    private void update(Deployment deployment, Instant now, AtomicBoolean handedOver) {
        UUID hostId = deployment.getHostId();
        DockerEndpoint endpoint = deploymentService.endpointOf(deploymentService.requireHost(hostId));
        List<ContainerDto> own = projectContainers(hostId, endpoint, deployment.getName());
        if (own.stream().noneMatch(c -> hasUpdate(hostId, c.image()))) {
            return;
        }
        Map<String, String> previous = previousDigests(hostId, endpoint, own);
        long services = own.stream().map(ContainerDto::composeService).filter(java.util.Objects::nonNull).distinct().count();

        // Recorded before the worker exists, so a failure here cannot leave a running worker behind.
        deployments.recordImageUpdateAttempt(deployment.getId(), now, null);
        CompletableFuture<DeploymentRunService.PullUpdateOutcome> run;
        try {
            run = runs.autoPullUpdate(deployment.getId());
        } catch (DeploymentException e) {
            finish(deployment, now, false, e.code(), false, 0, services, false);
            return;
        }
        try {
            afterStart(deployment, now, run, previous, services, handedOver);
        } catch (RuntimeException | Error e) {
            // The worker may still run compose: the lock must not be released before it ends.
            if (!run.isDone()) {
                handOver(run, deployment.getId(), handedOver);
            }
            throw e;
        }
    }

    /** Everything after the worker started: wait, health check, rollback decision, report. */
    private void afterStart(Deployment deployment, Instant now, CompletableFuture<DeploymentRunService.PullUpdateOutcome> run,
                            Map<String, String> previous, long services, AtomicBoolean handedOver) {
        DeploymentRunService.PullUpdateOutcome outcome = await(run);
        if (!run.isDone()) {
            // The worker still runs compose: a rollback now would be a second concurrent `up`, and releasing the lock
            // would let a manual run start next to it. Report without rollback; the worker's end releases the lock.
            handOver(run, deployment.getId(), handedOver);
            if (outcome != null) {
                finish(deployment, now, false, "TIMEOUT", false, previous.size(), services, true);
            }
            return;
        }
        if (outcome == null || "TIMEOUT".equals(outcome.failedStep())) {
            // timed out or interrupted a moment before the worker ended: use its real result if it is there
            outcome = run.getNow(outcome);
        }
        if (outcome == null) {
            return; // interrupted: shutting down
        }
        UUID hostId = deployment.getHostId();
        DockerEndpoint endpoint = deploymentService.endpointOf(deploymentService.requireHost(hostId));
        if (outcome.success() && awaitHealthy(hostId, endpoint, deployment.getName())) {
            finish(deployment, now, true, null, false, previous.size(), services, true);
            return;
        }
        String reason = outcome.success() ? "UNHEALTHY" : "PULL".equals(outcome.failedStep()) ? "PULL_FAILED" : "UP_FAILED";
        // A failed pull changed nothing; every other failure may have left new or half started containers behind.
        // Only a rollback that pinned every service counts as rolled back.
        boolean rolledBack = !"PULL_FAILED".equals(reason) && runs.rollbackImages(deployment.getId(), previous)
                && previous.size() >= services;
        finish(deployment, now, false, reason, rolledBack, previous.size(), services, true);
    }

    /** Releases the deployment lock when the still running worker ends, so the lock never outlives or precedes it. */
    private void handOver(CompletableFuture<?> run, UUID deploymentId, AtomicBoolean handedOver) {
        if (handedOver.compareAndSet(false, true)) {
            run.whenComplete((outcome, error) -> locks.release(deploymentId));
        }
    }

    /**
     * Maps the outcome to the stored {@code lastResult}: no failure is APPLIED; PULL_FAILED is FAILED (the pull changed
     * nothing, so no rollback was attempted); so is a start error ({@code started} false: the worker never existed, e.g. a
     * lock conflict, so nothing was pulled or changed). Every other reason (UNHEALTHY, UP_FAILED, TIMEOUT, ERROR) is
     * ROLLED_BACK when every service was pinned back, else FAILED_NOT_ROLLED_BACK.
     */
    static String resultOf(boolean applied, String reason, boolean rolledBack, boolean started) {
        if (applied) {
            return "APPLIED";
        }
        if (!started || "PULL_FAILED".equals(reason)) {
            return "FAILED";
        }
        return rolledBack ? "ROLLED_BACK" : "FAILED_NOT_ROLLED_BACK";
    }

    private void finish(Deployment deployment, Instant attemptAt, boolean applied, String reason, boolean rolledBack,
                        int pinnedServices, long services, boolean started) {
        deployments.recordImageUpdateAttempt(deployment.getId(), attemptAt, resultOf(applied, reason, rolledBack, started));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("applied", applied);
        if (!applied) {
            details.put("reason", reason);
            details.put("rolledBack", rolledBack);
            if (pinnedServices < services) {
                details.put("pinnedServices", pinnedServices); // partial: only these services had a known previous digest
                details.put("services", services);
            }
        }
        runs.recordSystemAudit(ACTION, applied ? AuditLogEntry.Outcome.SUCCESS : AuditLogEntry.Outcome.FAILURE,
                deployment.getId(), details);
        String code = applied ? null : "PULL_FAILED".equals(reason) ? reason
                : reason + (rolledBack ? "_ROLLED_BACK" : "_NOT_ROLLED_BACK");
        runs.publishImageUpdate(deployment.getId(), applied, code);
    }

    private DeploymentRunService.PullUpdateOutcome await(CompletableFuture<DeploymentRunService.PullUpdateOutcome> run) {
        try {
            return run.get(properties.runTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new DeploymentRunService.PullUpdateOutcome(false, "TIMEOUT"); // caller checks run.isDone()
        } catch (ExecutionException e) {
            return new DeploymentRunService.PullUpdateOutcome(false, "ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Healthy = every container running and either healthy or, without healthcheck, running for {@code stableFor}.
     * Unhealthy, crashed or restarting containers fail at once; containers still starting (or not listable) are waited
     * for until {@code healthTimeout}. One-shot containers that exited with code 0 are ignored.
     */
    private boolean awaitHealthy(UUID hostId, DockerEndpoint endpoint, String project) {
        Instant start = clock.instant();
        Instant stable = start.plus(properties.stableFor());
        Instant deadline = start.plus(properties.healthTimeout());
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("interrupted while waiting for health");
            }
            Boolean verdict = verdict(hostId, endpoint, project, clock.instant().isBefore(stable));
            if (verdict != null) {
                return verdict;
            }
            if (!clock.instant().isBefore(deadline)) {
                return false;
            }
            sleeperMillis.accept(properties.healthPoll().toMillis());
        }
    }

    /** True = all healthy, false = failed, null = undecided yet. */
    private Boolean verdict(UUID hostId, DockerEndpoint endpoint, String project, boolean stillSettling) {
        List<ContainerDto> current;
        try {
            current = projectContainers(hostId, endpoint, project);
        } catch (RuntimeException e) {
            return null;
        }
        boolean allReady = true;
        int counted = 0;
        for (ContainerDto c : current) {
            if (isFinishedOneShot(c)) {
                continue;
            }
            counted++;
            if ("unhealthy".equals(c.health()) || "restarting".equals(c.state()) || "dead".equals(c.state())
                    || "exited".equals(c.state())) {
                return false;
            }
            boolean ready = "running".equals(c.state())
                    && ("healthy".equals(c.health()) || (c.health() == null && !stillSettling));
            allReady &= ready;
        }
        return counted > 0 && allReady ? Boolean.TRUE : null;
    }

    private static boolean isFinishedOneShot(ContainerDto c) {
        return "exited".equals(c.state()) && c.status() != null && c.status().startsWith("Exited (0)");
    }

    private List<ContainerDto> projectContainers(UUID hostId, DockerEndpoint endpoint, String project) {
        return containers.listContainers(hostId, endpoint).stream()
                .filter(c -> project.equals(c.composeProject()))
                .toList();
    }

    private boolean hasUpdate(UUID hostId, String image) {
        ImageReference ref = ImageReference.parse(image);
        return ref != null && updates.hasUpdate(hostId, ref.key());
    }

    /** Service name to the {@code repo@sha256:...} the service's image has now, for every service whose local image has one. */
    private Map<String, String> previousDigests(UUID hostId, DockerEndpoint endpoint, List<ContainerDto> own) {
        List<ImageDto> local = images.list(hostId, endpoint);
        Map<String, String> pins = new LinkedHashMap<>();
        for (ContainerDto c : own) {
            ImageReference ref = ImageReference.parse(c.image());
            if (ref == null || c.composeService() == null) {
                continue;
            }
            local.stream()
                    .filter(i -> i.repoTags().stream().anyMatch(t -> {
                        ImageReference r = ImageReference.parse(t);
                        return r != null && r.key().equals(ref.key());
                    }))
                    .flatMap(i -> i.repoDigests().stream())
                    .filter(d -> {
                        int at = d.indexOf('@');
                        ImageReference r = at < 0 ? null : ImageReference.parse(d.substring(0, at) + ":x");
                        return r != null && r.repositoryKey().equals(ref.repositoryKey());
                    })
                    .sorted()
                    .findFirst()
                    .ifPresent(d -> pins.putIfAbsent(c.composeService(), d));
        }
        return pins;
    }
}
