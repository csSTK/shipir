package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.AiInvocationContext;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.compose.dto.DeploymentSummaryResponse;
import dev.bethge.shipir.compose.dto.DeploymentVersionContentResponse;
import dev.bethge.shipir.compose.dto.DeploymentVersionResponse;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.compose.file.ComposeGraphHasher;
import dev.bethge.shipir.compose.validation.ComposeValidationService;
import dev.bethge.shipir.compose.validation.ValidationResult;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.common.stream.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Deploy, stop, version history and rollback of managed deployments.
 *
 * <p>A deploy snapshots the current draft as a new {@link DeploymentVersion}, writes it to disk and runs
 * {@code docker compose up} on a virtual thread; the HTTP call returns the run id for streaming.
 *
 * <p><b>Audit of async runs:</b> the {@code @Auditable} entry (e.g. {@code DEPLOYMENT_DEPLOY}) records that the request was
 * accepted or rejected (409, key missing, ...). The process result is not known yet at that point, so the worker
 * records a follow-up entry ({@code DEPLOYMENT_DEPLOY_RESULT} / {@code DEPLOYMENT_ROLLBACK_RESULT}) via {@link AuditService}
 * with SUCCESS or FAILURE depending on the compose exit code. Compose output, YAML and env are never audited.
 */
@Service
public class DeploymentRunService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRunService.class);
    private static final String SYSTEM_USER = "system";
    private static final java.util.regex.Pattern PIN_SERVICE = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final java.util.regex.Pattern PIN_IMAGE =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._/:-]*@sha256:[0-9a-f]{64}");

    /** How the pull and up of an automatic image update ended; {@code failedStep} is the compose step or ERROR. */
    record PullUpdateOutcome(boolean success, String failedStep) {
    }

    private final DeploymentService deploymentService;
    private final DeploymentRepository deployments;
    private final DeploymentVersionRepository versions;
    private final UserRepository users;
    private final ComposeFileRepository files;
    private final ComposeExecutionService execution;
    private final ComposeOutputStreamService streams;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final Clock clock;
    private final DeploymentLocks locks;
    private final TransactionTemplate tx;
    private final GitRepositoryService git;
    private final ComposeValidationService validation;
    private final ApplicationEventPublisher events;

    DeploymentRunService(DeploymentService deploymentService, DeploymentRepository deployments, DeploymentVersionRepository versions,
                           UserRepository users, ComposeFileRepository files, ComposeExecutionService execution,
                           ComposeOutputStreamService streams, AuditService audit, SecretCipher cipher, Clock clock,
                           DeploymentLocks locks, PlatformTransactionManager txManager,
                           GitRepositoryService git, ComposeValidationService validation,
                           ApplicationEventPublisher events) {
        this.deploymentService = deploymentService;
        this.deployments = deployments;
        this.versions = versions;
        this.users = users;
        this.files = files;
        this.execution = execution;
        this.streams = streams;
        this.audit = audit;
        this.cipher = cipher;
        this.clock = clock;
        this.locks = locks;
        this.tx = new TransactionTemplate(txManager);
        this.git = git;
        this.validation = validation;
        this.events = events;
    }

    @Auditable(action = "DEPLOYMENT_DEPLOY", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentRunResponse deploy(UUID hostId, UUID id, String username) {
        deploymentService.requireOperate(hostId, id);
        return start(hostId, id, userIdOf(username), username, null, "DEPLOYMENT_DEPLOY_RESULT", false);
    }

    @Auditable(action = "DEPLOYMENT_ROLLBACK", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentRunResponse rollback(UUID hostId, UUID id, UUID versionId, String username) {
        deploymentService.requireOperate(hostId, id);
        return start(hostId, id, userIdOf(username), username, versionId, "DEPLOYMENT_ROLLBACK_RESULT", false);
    }

    /**
     * "Pull &amp; redeploy": fetches the deployment's ref again, records the checked-out commit as a new GIT version and
     * deploys it. Also runs when the commit has not changed (manual trigger). 400 NOT_A_GIT_DEPLOYMENT for FILE deployments.
     */
    @Auditable(action = "DEPLOYMENT_GIT_REDEPLOY", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentRunResponse gitRedeploy(UUID hostId, UUID id, String username) {
        deploymentService.requireOperate(hostId, id);
        return start(hostId, id, userIdOf(username), username, null, "DEPLOYMENT_GIT_REDEPLOY_RESULT", true);
    }

    /**
     * "Pull &amp; update": {@code docker compose pull} then {@code up -d} on the latest successfully deployed version, so tags like
     * {@code latest} pick up new images. Creates no new version. 400 NOT_DEPLOYED if the deployment was never deployed.
     */
    @Auditable(action = "DEPLOYMENT_PULL_UPDATE", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentRunResponse pullUpdate(UUID hostId, UUID id, String username) {
        deploymentService.requireOperate(hostId, id);
        return startPullUpdate(hostId, id, userIdOf(username), username, "DEPLOYMENT_PULL_UPDATE_RESULT", null, false);
    }

    /**
     * Redeploy webhook: git redeploy for GIT deployments, pull &amp; update for FILE deployments, as the system (audit
     * user {@code system}, no user id; a new GIT version is attributed to the deployment's creator like in
     * {@link #autoUpdate}). The webhook token was verified by the caller, so no permission check applies: nothing here
     * asks {@code CurrentAccess}, and the unauthenticated request has no user to ask it about.
     *
     * @throws DeploymentException 409 DEPLOYMENT_IN_PROGRESS if the deployment is busy
     */
    @Auditable(action = "DEPLOYMENT_WEBHOOK_TRIGGER", targetType = "DEPLOYMENT", targetId = "#deploymentId",
            username = "'system'", details = "{'via': 'webhook'}")
    public DeploymentRunResponse webhookTrigger(UUID deploymentId) {
        return SystemScope.call(() -> {
            Deployment deployment = deployments.findById(deploymentId).orElseThrow(
                    () -> new DeploymentException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook not found"));
            if (deployment.getSourceType() == DeploymentSource.GIT) {
                return start(deployment.getHostId(), deploymentId, deployment.getCreatedBy(), SYSTEM_USER, null,
                        "DEPLOYMENT_GIT_REDEPLOY_RESULT", true);
            }
            return startPullUpdate(deployment.getHostId(), deploymentId, null, SYSTEM_USER,
                    "DEPLOYMENT_PULL_UPDATE_RESULT", null, false);
        });
    }

    /**
     * "Start": {@code docker compose up -d} (with the stored profiles) on the latest successfully deployed version, e.g. after a stop.
     * Creates no new version. 400 NOT_DEPLOYED if the deployment was never deployed.
     */
    @Auditable(action = "DEPLOYMENT_START", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentRunResponse startDeployment(UUID hostId, UUID id, String username) {
        deploymentService.requireOperate(hostId, id);
        return startSteps(hostId, id, userIdOf(username), username, "DEPLOYMENT_START_RESULT", null, false,
                List.of(ComposeCommand.UP), null);
    }

    @Auditable(action = "DEPLOYMENT_SERVICE_RESTART", targetType = "DEPLOYMENT", targetId = "#id", details = "{'service': #service}")
    public DeploymentRunResponse restartService(UUID hostId, UUID id, String service, String username) {
        return serviceAction(hostId, id, service, username, ComposeCommand.RESTART, "DEPLOYMENT_SERVICE_RESTART_RESULT");
    }

    @Auditable(action = "DEPLOYMENT_SERVICE_PULL", targetType = "DEPLOYMENT", targetId = "#id", details = "{'service': #service}")
    public DeploymentRunResponse pullService(UUID hostId, UUID id, String service, String username) {
        return serviceAction(hostId, id, service, username, ComposeCommand.PULL, "DEPLOYMENT_SERVICE_PULL_RESULT");
    }

    @Auditable(action = "DEPLOYMENT_SERVICE_UP", targetType = "DEPLOYMENT", targetId = "#id", details = "{'service': #service}")
    public DeploymentRunResponse upService(UUID hostId, UUID id, String service, String username) {
        return serviceAction(hostId, id, service, username, ComposeCommand.UP_SERVICE, "DEPLOYMENT_SERVICE_UP_RESULT");
    }

    /** One compose command for one service of the latest successfully deployed version; 400 INVALID_SERVICE / UNKNOWN_SERVICE / NOT_DEPLOYED. */
    private DeploymentRunResponse serviceAction(UUID hostId, UUID id, String service, String username,
                                                ComposeCommand command, String resultAction) {
        deploymentService.requireOperate(hostId, id);
        if (service == null || !ComposeOptions.NAME.matcher(service).matches()) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_SERVICE", "Invalid service name");
        }
        return startSteps(hostId, id, userIdOf(username), username, resultAction, null, false, List.of(command), service);
    }

    private DeploymentRunResponse startPullUpdate(UUID hostId, UUID id, UUID userId, String username,
                                                  String resultAction, CompletableFuture<PullUpdateOutcome> done,
                                                  boolean lockHeld) {
        return startSteps(hostId, id, userId, username, resultAction, done, lockHeld,
                List.of(ComposeCommand.PULL, ComposeCommand.UP), null);
    }

    /**
     * Runs {@code steps} on the latest successfully deployed version without creating a version; with {@code service} every step is
     * restricted to that service, which must exist in the compose file (checked after the policy gate, on the file that
     * will run).
     */
    private DeploymentRunResponse startSteps(UUID hostId, UUID id, UUID userId, String username,
                                             String resultAction, CompletableFuture<PullUpdateOutcome> done,
                                             boolean lockHeld, List<ComposeCommand> steps, String service) {
        var host = deploymentService.requireHost(hostId);
        Deployment deployment = deploymentService.requireDeployment(hostId, id);
        DockerEndpoint endpoint = deploymentService.endpointOf(host);
        if (!lockHeld) {
            locks.acquire(deployment.getId());
        }
        boolean started = false;
        try {
            DeploymentVersion version = versions.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId()).stream()
                    .filter(DeploymentVersion::isDeploySucceeded).findFirst().orElseThrow(
                            () -> new DeploymentException(HttpStatus.BAD_REQUEST, "NOT_DEPLOYED",
                                    "The deployment has not been deployed yet"));
            String envText = decryptEnv(version);
            alignCheckout(deployment, version);
            DiskState before = DiskState.of(files, deployment);
            files.write(hostId, deployment.getName(), version.getYamlContent(), envText);
            List<String> services = null;
            try {
                enforceRunPolicy(deployment, endpoint, username, userId != null ? userId : version.getCreatedBy(), version);
                if (service != null) {
                    deploymentService.requireService(deployment, endpoint, service);
                    services = List.of(service);
                }
            } catch (RuntimeException e) {
                restoreDisk(deployment, before, userId, username);
                throw e;
            }
            String runId = UUID.randomUUID().toString();
            Map<String, Object> aiMarker = AiInvocationContext.active() ? aiMarker() : Map.of();
            streams.registerRun(runId, hostId, deployment.getId());
            Consumer<String> sink = streams.start(runId);
            List<String> runServices = services;
            Thread.ofVirtual().name("deploy-" + runId).start(
                    () -> run(deployment, version, endpoint, runId, sink, userId, username,
                            resultAction, aiMarker, steps, done, lockHeld, runServices));
            started = true;
            return new DeploymentRunResponse(runId, version.getId());
        } finally {
            if (!started && !lockHeld) {
                locks.release(deployment.getId());
            }
        }
    }

    /** Runs {@code docker compose down} and waits for it; the files and the deployment record stay. */
    @Auditable(action = "DEPLOYMENT_STOP", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentSummaryResponse stop(UUID hostId, UUID id) {
        deploymentService.requireOperate(hostId, id);
        var host = deploymentService.requireHost(hostId);
        Deployment deployment = deploymentService.requireDeployment(hostId, id);
        DockerEndpoint endpoint = deploymentService.endpointOf(host);
        locks.acquire(deployment.getId());
        try {
            ComposeProcessResult result;
            try {
                result = deploymentService.runCompose(deployment, ComposeCommand.DOWN, endpoint, line -> { }, null, null);
            } catch (ComposeExecutionException e) {
                throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                        "docker compose down could not run");
            }
            if (!result.success()) {
                List<String> output = result.output().stream().filter(l -> !l.isBlank()).toList();
                throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                        result.timedOut() ? "docker compose down timed out" : "docker compose down failed",
                        output.subList(Math.max(0, output.size() - 20), output.size()));
            }
            deployment.setStatus(DeploymentStatus.STOPPED);
            deployments.save(deployment);
            return new DeploymentSummaryResponse(deployment.getId(), deployment.getName(), deployment.getStatus(),
                    deployment.getSourceType(), deployment.getLastDeployedAt(), deployment.getCreatedAt());
        } finally {
            locks.release(deployment.getId());
        }
    }

    /** Version history, newest first. */
    public List<DeploymentVersionResponse> versions(UUID hostId, UUID id) {
        deploymentService.requireView(hostId, id);
        deploymentService.requireHost(hostId);
        Deployment deployment = deploymentService.requireDeployment(hostId, id);
        deploymentService.requireAdminIfSystemManaged(deployment);
        List<DeploymentVersion> all = versions.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
        UUID currentId = currentVersionId(all);
        Map<UUID, String> names = users.findAllById(all.stream().map(DeploymentVersion::getCreatedBy).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return all.stream()
                .map(v -> new DeploymentVersionResponse(v.getId(), v.getSource(), v.getCommitSha(),
                        names.get(v.getCreatedBy()), v.getCreatedAt(), v.getId().equals(currentId),
                        v.isDeployed()))
                .toList();
    }

    /**
     * The version the API reports as {@code current}: the latest one whose deploy finished successfully, null when there is
     * none. Derived from the history on purpose and not from {@code Deployment.currentVersionId}: that column is the
     * working version (the editable draft after an update, the snapshot of a run that was only started) which update, deploy
     * and the Git create flow read, so its meaning stays as it is. {@code history} is newest first.
     */
    static UUID currentVersionId(List<DeploymentVersion> history) {
        return history.stream().filter(DeploymentVersion::isDeploySucceeded).findFirst().map(DeploymentVersion::getId)
                .orElse(null);
    }

    /** One version's YAML and env keys, flagged against the currently deployed version; no env values. */
    public DeploymentVersionContentResponse version(UUID hostId, UUID id, UUID versionId) {
        deploymentService.requireView(hostId, id);
        deploymentService.requireHost(hostId);
        Deployment deployment = deploymentService.requireDeployment(hostId, id);
        deploymentService.requireAdminIfSystemManaged(deployment);
        DeploymentVersion version = versionOf(deployment, versionId);
        // current_version_id may point at a newer draft; the baseline is the latest version whose deploy succeeded
        List<DeploymentVersion> history = versions.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
        DeploymentVersion baseline = history.stream().filter(DeploymentVersion::isDeploySucceeded).findFirst().orElse(null);
        UUID currentId = currentVersionId(history);

        Map<String, String> values = envMap(version);
        Map<String, String> base = baseline == null ? Map.of() : envMap(baseline);
        List<DeploymentVersionContentResponse.EnvKey> keys = values.entrySet().stream()
                .map(e -> new DeploymentVersionContentResponse.EnvKey(e.getKey(),
                        baseline != null && !e.getValue().equals(base.get(e.getKey()))))
                .toList();
        List<String> removed = base.keySet().stream().filter(k -> !values.containsKey(k)).toList();
        String createdBy = users.findById(version.getCreatedBy()).map(User::getUsername).orElse(null);
        return new DeploymentVersionContentResponse(version.getId(), version.getSource(), version.getCommitSha(),
                createdBy, version.getCreatedAt(), version.getId().equals(currentId),
                version.isDeployed(), version.getYamlContent(), baseline == null ? null : baseline.getId(), keys, removed);
    }

    private Map<String, String> envMap(DeploymentVersion version) {
        String text = decryptEnv(version);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (text != null) {
            EnvFile.parse(text).forEach(e -> out.put(e.key(), e.value()));
        }
        return out;
    }

    /**
     * Pull &amp; redeploy triggered by {@code GitPollingScheduler} (through {@code DeploymentAutoUpdates}). Runs as the
     * audit user {@code system} (no user id); the new version is attributed to the deployment's creator because
     * {@code deployment_versions.created_by} is mandatory. Not {@code @Auditable} (no request, no authentication): it
     * records {@code DEPLOYMENT_AUTO_UPDATE} itself once the deployment was accepted, {@code DEPLOYMENT_AUTO_UPDATE_RESULT}
     * follows from the worker.
     *
     * @throws DeploymentException 409 DEPLOYMENT_IN_PROGRESS if the deployment is busy
     */
    DeploymentRunResponse autoUpdate(UUID deploymentId) {
        Deployment deployment = deployments.findById(deploymentId).orElseThrow(
                () -> new DeploymentException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "Deployment not found"));
        DeploymentRunResponse response = start(deployment.getHostId(), deploymentId, deployment.getCreatedBy(), SYSTEM_USER, null,
                "DEPLOYMENT_AUTO_UPDATE_RESULT", true);
        recordSystemAudit("DEPLOYMENT_AUTO_UPDATE", AuditLogEntry.Outcome.SUCCESS, deploymentId,
                Map.of("runId", response.runId(), "versionId", response.versionId().toString()));
        return response;
    }

    /**
     * Pull &amp; update triggered by {@code ImageAutoUpdateScheduler}, as the system (audit user {@code system}, no user
     * id, no new version, like the webhook's pull &amp; update). Not {@code @Auditable} (no request): the worker records
     * {@code IMAGE_AUTO_UPDATE_RESULT}, the scheduler the overall {@code IMAGE_AUTO_UPDATE} entry.
     *
     * <p>The caller must hold the deployment lock ({@link DeploymentLocks}) and keeps it until it is done with the
     * whole update (health wait and rollback), so no manual run can start in between. This method and its worker
     * neither acquire nor release it.
     *
     * @return completes when pull and up ended
     * @throws DeploymentException 400 NOT_DEPLOYED
     */
    CompletableFuture<PullUpdateOutcome> autoPullUpdate(UUID deploymentId) {
        return SystemScope.call(() -> {
            Deployment deployment = deployments.findById(deploymentId).orElseThrow(
                    () -> new DeploymentException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "Deployment not found"));
            CompletableFuture<PullUpdateOutcome> done = new CompletableFuture<>();
            startPullUpdate(deployment.getHostId(), deploymentId, null, SYSTEM_USER, "IMAGE_AUTO_UPDATE_RESULT", done, true);
            return done;
        });
    }

    /**
     * Brings the deployment back to the images it ran before an automatic update: a temporary override file pins each
     * service to its previous digest ({@code service name -> repo@sha256:...}) and {@code up} recreates the containers.
     * The stored compose file and the version history stay untouched. The caller must hold the deployment lock (see
     * {@link #autoPullUpdate}); any failure returns false (nothing is thrown).
     *
     * @return true when {@code up} with the pinned images succeeded
     */
    boolean rollbackImages(UUID deploymentId, Map<String, String> pinnedImages) {
        String yaml = rollbackOverride(pinnedImages);
        if (yaml == null) {
            return false;
        }
        return SystemScope.call(() -> {
            Deployment deployment = deployments.findById(deploymentId).orElse(null);
            if (deployment == null) {
                return false;
            }
            try {
                DockerEndpoint endpoint = deploymentService.endpointOf(deploymentService.requireHost(deployment.getHostId()));
                // same gate as every other system run: the compose file on disk must pass the policy for the author's trust
                List<DeploymentVersion> history = versions.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
                DeploymentVersion current = history.stream().filter(DeploymentVersion::isDeploySucceeded).findFirst()
                        .or(() -> history.stream().findFirst()).orElse(null);
                enforceRunPolicy(deployment, endpoint, SYSTEM_USER, current != null ? current.getCreatedBy() : null, current);
                Path override = files.writeRollbackOverride(deployment.getHostId(), deployment.getName(), yaml);
                ComposeProcessResult result = deploymentService.runCompose(deployment, ComposeCommand.UP, endpoint,
                        line -> { }, override, null);
                tx.executeWithoutResult(status -> deployments.findById(deploymentId).ifPresent(s -> {
                    s.setStatus(result.success() ? DeploymentStatus.RUNNING : DeploymentStatus.ERROR);
                    deployments.save(s);
                }));
                return result.success();
            } catch (DeploymentException e) {
                log.warn("Image rollback of deployment {} refused: {}", deploymentId, e.code());
                return false;
            } catch (RuntimeException e) {
                log.warn("Image rollback of deployment {} failed: {}", deploymentId, e.toString());
                return false;
            } finally {
                try {
                    files.deleteRollbackOverride(deployment.getHostId(), deployment.getName());
                } catch (RuntimeException e) {
                    log.warn("Could not remove rollback override of deployment {}: {}", deploymentId, e.toString());
                }
            }
        });
    }

    /** Override compose file pinning services to digests; entries that do not look like a service name / digest reference are dropped. */
    private static String rollbackOverride(Map<String, String> pinnedImages) {
        StringBuilder yaml = new StringBuilder("services:\n");
        boolean any = false;
        for (Map.Entry<String, String> pin : new java.util.TreeMap<>(pinnedImages).entrySet()) {
            if (PIN_SERVICE.matcher(pin.getKey()).matches() && PIN_IMAGE.matcher(pin.getValue()).matches()) {
                yaml.append("  \"").append(pin.getKey()).append("\":\n    image: \"").append(pin.getValue()).append("\"\n");
                any = true;
            }
        }
        return any ? yaml.toString() : null;
    }

    /** Publishes the end of an automatic image update; {@code errorCode} is null when applied. */
    void publishImageUpdate(UUID deploymentId, boolean applied, String errorCode) {
        try {
            deployments.findById(deploymentId).ifPresent(d -> events.publishEvent(new ImageAutoUpdateEvent(d.getHostId(),
                    hostNameOf(d.getHostId()), d.getId(), d.getName(), applied, errorCode, clock.instant())));
        } catch (RuntimeException e) {
            log.warn("Could not publish image update result of deployment {}: {}", deploymentId, e.toString());
        }
    }

    /** Audit entry of a system-triggered action without user; failures to write it never fail the caller. */
    void recordSystemAudit(String action, AuditLogEntry.Outcome outcome, UUID deploymentId, Map<String, Object> details) {
        try {
            audit.record(new AuditRecord(null, SYSTEM_USER, action, outcome, "DEPLOYMENT", deploymentId.toString(), details));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry {}: {}", action, e.toString());
        }
    }

    /**
     * Whether the run may use host-level compose features (ADMIN only). A signed-in caller counts by their own role. A system
     * run (webhook, Git/image auto-update; no user) counts by the role of the user who authored the content being run: the
     * deployed version's author, or the deployment's creator for freshly pulled Git content. Unknown or disabled user: no.
     *
     * <p>A system run additionally needs the whole compose graph to be what that ADMIN approved. Shipir stores only
     * {@code compose.yaml} and {@code .env} of a version, so an ADMIN's deploy also records a hash of the deployment directory
     * ({@link ComposeGraphHasher}: include/extends/env_file targets, Git checkout). The system run stays unrestricted only
     * while the directory still hashes to it; an edited aux file or a new commit (an OPERATOR can change both) makes it use
     * the restricted profile. Versions without a hash (older, or not deployed by an ADMIN) keep the earlier rule: only a FILE
     * deployment whose directory holds nothing but Shipir's own files.
     *
     * @param approved the version whose graph the run executes (its hash is the ADMIN's approval); may be null
     */
    private boolean unrestricted(String username, UUID authorId, Deployment deployment, DeploymentVersion approved) {
        if (!SYSTEM_USER.equals(username)) {
            var caller = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            // an API token never carries ADMIN power, whatever role its user has
            return users.findByUsername(username).filter(u -> !u.isDisabled())
                    .map(u -> dev.bethge.shipir.auth.token.ApiTokenAuthentication.effectiveRole(caller, u.getRole()) == Role.ADMIN)
                    .orElse(false);
        }
        return authorId != null && users.findById(authorId).filter(u -> !u.isDisabled()).map(u -> u.getRole() == Role.ADMIN)
                .orElse(false) && graphIsApproved(deployment, approved);
    }

    private boolean graphIsApproved(Deployment deployment, DeploymentVersion approved) {
        if (approved != null && approved.getGraphHash() != null) {
            return ComposeGraphHasher.hash(files.deploymentDir(deployment.getHostId(), deployment.getName()))
                    .filter(approved.getGraphHash()::equals).isPresent();
        }
        return graphIsOnlyApprovedFiles(deployment);
    }

    private String graphHashOf(Deployment deployment) {
        return ComposeGraphHasher.hash(files.deploymentDir(deployment.getHostId(), deployment.getName())).orElse(null);
    }

    /**
     * Policy gate for a run on the compose file on disk; returns whether the run was unrestricted (ADMIN-approved). A refused
     * system run says why: nobody is signed in to be asked, the notification text is all the ADMIN sees.
     */
    private boolean enforceRunPolicy(Deployment deployment, DockerEndpoint endpoint, String username, UUID authorId,
                                     DeploymentVersion approved) {
        boolean unrestricted = unrestricted(username, authorId, deployment, approved);
        try {
            deploymentService.enforcePolicy(deployment.getHostId(), deployment.getName(), endpoint,
                    deploymentService.composeFileOf(deployment), unrestricted);
        } catch (DeploymentException e) {
            if (SYSTEM_USER.equals(username) && "ADMIN_REQUIRED".equals(e.code())) {
                throw new DeploymentException(e.status(), e.code(), e.getMessage()
                        + ". Automatic runs keep ADMIN privileges only while the compose files are unchanged since an ADMIN "
                        + "deployed them; an ADMIN must deploy again to approve the current files", e.details());
            }
            throw e;
        }
        return unrestricted;
    }

    /** True when the deployment directory holds nothing but the files written from a stored version (FILE deployments only). */
    private boolean graphIsOnlyApprovedFiles(Deployment deployment) {
        if (deployment.getSourceType() != DeploymentSource.FILE) {
            return false;
        }
        Path dir = files.deploymentDir(deployment.getHostId(), deployment.getName());
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (var entries = Files.list(dir)) {
            return entries.allMatch(p -> ComposeFileRepository.SHIPIR_WRITTEN_FILES.contains(p.getFileName().toString()));
        } catch (IOException | RuntimeException e) {
            return false; // cannot tell: fail closed
        }
    }

    private UUID userIdOf(String username) {
        return users.findByUsername(username).orElseThrow().getId();
    }

    private DeploymentRunResponse start(UUID hostId, UUID id, UUID userId, String username, UUID rollbackTo,
                                    String resultAction, boolean pullGit) {
        var host = deploymentService.requireHost(hostId);
        Deployment deployment = deploymentService.requireDeployment(hostId, id);
        DockerEndpoint endpoint = deploymentService.endpointOf(host);
        locks.acquire(deployment.getId());
        boolean started = false;
        DeploymentVersion restoreSource = null;
        String restoreEnv = null;
        DiskState diskBefore = DiskState.of(files, deployment);
        try {
            DeploymentVersion source = rollbackTo == null ? deploymentService.currentVersion(deployment) : versionOf(deployment, rollbackTo);
            String envText = decryptEnv(source);
            restoreSource = source;
            restoreEnv = envText;
            GitCheckout checkout = pullGit ? pull(deployment, endpoint, envText,
                    () -> unrestricted(username, userId, deployment, source)) : null;
            if (!pullGit) {
                alignCheckout(deployment, source);
            }
            String yaml = checkout == null ? source.getYamlContent() : checkout.composeYaml();
            VersionSource versionSource = rollbackTo != null ? VersionSource.ROLLBACK
                    : pullGit ? VersionSource.GIT : source.getSource();
            String commitSha = checkout == null ? source.getCommitSha() : checkout.commitSha();
            files.write(hostId, deployment.getName(), yaml, envText);
            // hashed before the (slow) policy check, so an edit made meanwhile leaves a stale hash that fails safe next run
            String graphHashBeforeGate = graphHashOf(deployment);
            boolean approved = enforceRunPolicy(deployment, endpoint, username, userId, source);
            String graphHash = approved ? graphHashBeforeGate : null;
            DeploymentVersion version = tx.execute(status -> {
                DeploymentVersion v = new DeploymentVersion(deployment.getId(), yaml, source.getEnvCiphertext(), versionSource,
                        userId, clock.instant());
                v.setCommitSha(commitSha);
                v.setGraphHash(graphHash);
                v = versions.save(v);
                v.markDeployed(clock.instant());
                versions.save(v);
                deployment.setCurrentVersionId(v.getId());
                if (checkout != null) {
                    deployment.setLastCommitSha(commitSha);
                }
                deployments.save(deployment);
                return v;
            });

            String runId = UUID.randomUUID().toString();
            // The ScopedValue is not inherited by the worker thread: capture the AI marker now, write it into the result entry.
            Map<String, Object> aiMarker = AiInvocationContext.active() ? aiMarker() : Map.of();
            streams.registerRun(runId, hostId, deployment.getId());
            Consumer<String> sink = streams.start(runId);
            Thread.ofVirtual().name("deploy-" + runId).start(
                    () -> run(deployment, version, endpoint, runId, sink, SYSTEM_USER.equals(username) ? null : userId,
                            username, resultAction, aiMarker));
            started = true;
            return new DeploymentRunResponse(runId, version.getId());
        } catch (RuntimeException e) {
            UUID actor = SYSTEM_USER.equals(username) ? null : userId;
            if (pullGit && restoreSource != null) {
                restoreAfterFailedPull(deployment, restoreSource, restoreEnv, actor, username);
            } else if (!pullGit) {
                restoreDisk(deployment, diskBefore, actor, username);
            }
            throw e;
        } finally {
            if (!started) {
                locks.release(deployment.getId());
            }
        }
    }

    /**
     * A git redeploy that failed after the pull (policy denial, invalid compose, anything else) must not leave the
     * violating commit checked out: later runs and the config editor read the working tree. Puts the checkout and the
     * compose file back to the deployment's current version. Best effort: a failure here is logged and audited
     * ({@code DEPLOYMENT_RESTORE_FAILED}, the checkout may stay on the violating commit until the next run realigns it);
     * the original exception still reaches the caller.
     */
    private void restoreAfterFailedPull(Deployment deployment, DeploymentVersion source, String envText, UUID userId,
                                        String username) {
        try {
            alignCheckout(deployment, source);
            files.write(deployment.getHostId(), deployment.getName(), source.getYamlContent(), envText);
        } catch (RuntimeException e) {
            reportRestoreFailure(deployment, userId, username, "GIT_REDEPLOY", e);
        }
    }

    /** compose.yaml and .env as they were on disk before a run wrote the files of the version it is about to gate. */
    record DiskState(String yaml, String env) {
        /** The .env holds secrets: the generated toString would print it. */
        @Override
        public String toString() {
            return "DiskState[yaml=" + (yaml != null) + ", env=" + (env != null ? "<redacted>" : "absent") + "]";
        }

        static DiskState of(ComposeFileRepository files, Deployment deployment) {
            try {
                return new DiskState(files.readCompose(deployment.getHostId(), deployment.getName()).orElse(null),
                        files.readEnv(deployment.getHostId(), deployment.getName()).orElse(null));
            } catch (RuntimeException e) {
                return new DiskState(null, null); // unreadable: nothing to put back
            }
        }
    }

    /**
     * A run that was refused (policy gate, invalid file) must not leave the refused compose file or .env on disk: the
     * files are written before the gate because the gate reads them from disk. Best effort like
     * {@link #restoreAfterFailedPull}; a failure is audited. Nothing to restore when there was no compose file before.
     */
    private void restoreDisk(Deployment deployment, DiskState before, UUID userId, String username) {
        if (before.yaml() == null) {
            log.warn("Deployment {} was not restored after a refused run: no readable compose file was captured before it",
                    deployment.getId());
            return;
        }
        try {
            files.write(deployment.getHostId(), deployment.getName(), before.yaml(), before.env());
        } catch (RuntimeException e) {
            reportRestoreFailure(deployment, userId, username, "DISK_RESTORE", e);
        }
    }

    private void reportRestoreFailure(Deployment deployment, UUID userId, String username, String phase, RuntimeException e) {
        log.error("Restoring deployment {} after a refused run failed ({}): {}", deployment.getId(), phase, e.toString());
        try {
            audit.record(new AuditRecord(userId, username, "DEPLOYMENT_RESTORE_FAILED", AuditLogEntry.Outcome.FAILURE,
                    "DEPLOYMENT", deployment.getId().toString(),
                    Map.of("phase", phase, "error", e.getClass().getSimpleName())));
        } catch (RuntimeException auditError) {
            log.warn("Could not write audit entry DEPLOYMENT_RESTORE_FAILED for deployment {}: {}", deployment.getId(),
                    auditError.toString());
        }
    }

    /**
     * Deploy and rollback of a Git deployment run the files of the checkout, so the checkout must be at the commit of the
     * version being deployed (a rollback target, or after a failed pull that left a newer commit behind). The stored
     * YAML snapshot alone would not do: build contexts, bind mounts and env_file come from the repository at that commit.
     */
    private void alignCheckout(Deployment deployment, DeploymentVersion version) {
        if (deployment.getSourceType() == DeploymentSource.GIT && version.getCommitSha() != null) {
            git.ensureCommit(files.repoDir(deployment.getHostId(), deployment.getName()), deployment.getGitRepoUrl(),
                    version.getCommitSha(), deployment.getGitComposePath(), deployment.getGitCredentialId());
        }
    }

    /** Fetches the ref into the checkout and validates the compose file; the previous file is restored when invalid. */
    private GitCheckout pull(Deployment deployment, DockerEndpoint endpoint, String envText,
                             java.util.function.BooleanSupplier unrestricted) {
        if (deployment.getSourceType() != DeploymentSource.GIT) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "NOT_A_GIT_DEPLOYMENT", "This deployment is not sourced from Git");
        }
        UUID hostId = deployment.getHostId();
        String name = deployment.getName();
        GitCheckout checkout = git.sync(files.repoDir(hostId, name), deployment.getGitRepoUrl(), deployment.getGitRef(),
                deployment.getGitComposePath(), deployment.getGitCredentialId());
        String previousYaml = files.readCompose(hostId, name).orElse(null);
        files.write(hostId, name, checkout.composeYaml(), envText);
        ValidationResult result;
        try {
            deploymentService.enforceFileAccess(hostId, name, deploymentService.composeFileOf(deployment),
                    unrestricted.getAsBoolean());
        } catch (RuntimeException e) {
            restore(hostId, name, previousYaml, envText);
            throw e;
        }
        try {
            result = validation.validate(hostId, name, endpoint, deploymentService.composeFileOf(deployment));
        } catch (ComposeExecutionException e) {
            restore(hostId, name, previousYaml, envText);
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_UNAVAILABLE", "docker compose could not run");
        }
        if (!result.valid()) {
            restore(hostId, name, previousYaml, envText);
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE", "The compose file is invalid",
                    result.errors());
        }
        return checkout;
    }

    private void restore(UUID hostId, String name, String yaml, String envText) {
        if (yaml == null) {
            files.delete(hostId, name);
            return;
        }
        files.write(hostId, name, yaml, envText);
    }

    private void run(Deployment deployment, DeploymentVersion version, DockerEndpoint endpoint, String runId,
                     Consumer<String> sink, UUID userId, String username, String resultAction,
                     Map<String, Object> aiMarker) {
        run(deployment, version, endpoint, runId, sink, userId, username, resultAction, aiMarker, List.of(ComposeCommand.UP), null, false,
                null);
    }

    private void run(Deployment deployment, DeploymentVersion version, DockerEndpoint endpoint, String runId,
                     Consumer<String> sink, UUID userId, String username, String resultAction,
                     Map<String, Object> aiMarker, List<ComposeCommand> steps,
                     CompletableFuture<PullUpdateOutcome> done, boolean lockHeld, List<String> services) {
        boolean[] lockReleased = {false};
        PullUpdateOutcome outcome = new PullUpdateOutcome(false, "ERROR");
        try {
            outcome = runSteps(deployment, version, endpoint, runId, sink, userId, username, resultAction, aiMarker, steps,
                    done != null, lockHeld, lockReleased, services);
        } finally {
            // every path (also an Error) frees the lock and completes the waiting scheduler
            if (!lockHeld && !lockReleased[0]) {
                locks.release(deployment.getId());
            }
            if (done != null) {
                done.complete(outcome);
            }
        }
    }

    private PullUpdateOutcome runSteps(Deployment deployment, DeploymentVersion version, DockerEndpoint endpoint,
                                       String runId, Consumer<String> sink, UUID userId, String username,
                                       String resultAction, Map<String, Object> aiMarker, List<ComposeCommand> steps,
                                       boolean automatic, boolean lockHeld, boolean[] lockReleased,
                                       List<String> services) {
        boolean success = false;
        String failedStep = null;
        Integer failedExit = null;
        boolean timedOut = false;
        String runError = null;
        Map<String, Object> details = new java.util.LinkedHashMap<>(aiMarker);
        details.put("runId", runId);
        details.put("versionId", version.getId().toString());
        if (services != null) {
            details.put("service", services.get(0));
        }
        try {
            for (ComposeCommand step : steps) {
                ComposeProcessResult result = deploymentService.runCompose(deployment, step, endpoint, sink, null, services);
                success = result.success();
                details.put("exitCode", result.exitCode());
                details.put("timedOut", result.timedOut());
                if (!success) {
                    details.put("failedStep", step.name());
                    failedStep = step.name();
                    failedExit = result.exitCode();
                    timedOut = result.timedOut();
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Deployment {} could not run: {}", runId, e.toString());
            details.put("error", "COMPOSE_UNAVAILABLE");
            runError = "COMPOSE_UNAVAILABLE";
            sink.accept("docker compose could not be run");
        }
        try {
            boolean ok = success;
            // An automatic update whose pull failed touched no container: the deployment keeps its status.
            // A single-service action says nothing about the deployment as a whole: its status stays as it was.
            boolean untouched = automatic && "PULL".equals(failedStep) || services != null;
            tx.executeWithoutResult(status -> deployments.findById(deployment.getId()).ifPresent(s -> {
                if (untouched) {
                    return;
                }
                s.setStatus(ok ? DeploymentStatus.RUNNING : DeploymentStatus.ERROR);
                if (ok) {
                    s.setLastDeployedAt(clock.instant());
                    versions.findById(version.getId()).ifPresent(v -> {
                        v.markDeploySucceeded(clock.instant());
                        versions.save(v);
                    });
                }
                deployments.save(s);
            }));
        } catch (RuntimeException e) {
            log.warn("Could not update status of deployment {}: {}", runId, e.toString());
        }
        try {
            audit.record(new AuditRecord(userId, username, resultAction,
                    success ? AuditLogEntry.Outcome.SUCCESS : AuditLogEntry.Outcome.FAILURE,
                    "DEPLOYMENT", deployment.getId().toString(), details));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry for deployment {}: {}", runId, e.toString());
        }
        // The scheduled image update reports its own, richer IMAGE_UPDATE_FAILED (incl. rollback result) once per
        // attempt, so the run failure is not announced a second time for it.
        if (!success && !automatic) {
            publishRunFailed(deployment, failedStep, failedExit, timedOut, runError);
        }
        // Release before announcing the end so a client reacting to DONE/FAILED can start the next deployment.
        if (!lockHeld) {
            locks.release(deployment.getId());
            lockReleased[0] = true;
        }
        PullUpdateOutcome outcome = new PullUpdateOutcome(success, success ? null : failedStep != null ? failedStep : "ERROR");
        streams.complete(runId, success ? RunStatus.DONE : RunStatus.FAILED);
        return outcome;
    }

    private void publishRunFailed(Deployment deployment, String failedStep, Integer exitCode, boolean timedOut,
                                  String error) {
        try {
            events.publishEvent(new DeploymentRunFailedEvent(deployment.getHostId(), hostNameOf(deployment.getHostId()),
                    deployment.getId(), deployment.getName(), failedStep, exitCode, timedOut, error, clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Could not publish failure event of deployment {}: {}", deployment.getId(), e.toString());
        }
    }

    /** Publishes a failed auto-update check; called by {@link DeploymentAutoUpdates} when the error code changed. */
    void publishAutoUpdateFailed(Deployment deployment, String errorCode) {
        try {
            events.publishEvent(new GitAutoUpdateFailedEvent(deployment.getHostId(), hostNameOf(deployment.getHostId()),
                    deployment.getId(), deployment.getName(), errorCode, clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Could not publish auto-update failure of deployment {}: {}", deployment.getId(), e.toString());
        }
    }

    private String hostNameOf(UUID hostId) {
        try {
            return deploymentService.requireHost(hostId).getName();
        } catch (RuntimeException e) {
            return hostId.toString();
        }
    }

    private static Map<String, Object> aiMarker() {
        Map<String, Object> marker = new java.util.LinkedHashMap<>();
        marker.put("via", "AI");
        AiInvocationContext.chatSessionId().ifPresent(id -> marker.put("chatSessionId", id.toString()));
        return marker;
    }

    private DeploymentVersion versionOf(Deployment deployment, UUID versionId) {
        return versions.findById(versionId).filter(v -> v.getDeploymentId().equals(deployment.getId())).orElseThrow(
                () -> new DeploymentException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Version not found"));
    }

    private String decryptEnv(DeploymentVersion version) {
        if (version.getEnvCiphertext() == null) {
            return null;
        }
        try {
            return cipher.decrypt(version.getEnvCiphertext());
        } catch (EncryptionKeyMissingException e) {
            throw DeploymentService.keyMissing();
        } catch (SecretDecryptionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored environment variables could not be decrypted");
        }
    }
}
