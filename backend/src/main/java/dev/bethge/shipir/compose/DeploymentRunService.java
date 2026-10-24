package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.AiInvocationContext;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.compose.dto.DeploymentSummaryResponse;
import dev.bethge.shipir.compose.dto.DeploymentVersionResponse;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.compose.validation.ComposeValidationService;
import dev.bethge.shipir.compose.validation.ValidationResult;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    DeploymentRunService(DeploymentService deploymentService, DeploymentRepository deployments, DeploymentVersionRepository versions,
                           UserRepository users, ComposeFileRepository files, ComposeExecutionService execution,
                           ComposeOutputStreamService streams, AuditService audit, SecretCipher cipher, Clock clock,
                           DeploymentLocks locks, PlatformTransactionManager txManager,
                           GitRepositoryService git, ComposeValidationService validation) {
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
                result = execution.execute(hostId, deployment.getName(), ComposeCommand.DOWN, endpoint, line -> { },
                        deploymentService.composeFileOf(deployment));
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
        List<DeploymentVersion> all = versions.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
        Map<UUID, String> names = users.findAllById(all.stream().map(DeploymentVersion::getCreatedBy).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return all.stream()
                .map(v -> new DeploymentVersionResponse(v.getId(), v.getSource(), v.getCommitSha(),
                        names.get(v.getCreatedBy()), v.getCreatedAt(), v.getId().equals(deployment.getCurrentVersionId()),
                        v.isDeployed()))
                .toList();
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

    /** Audit entry of a system-triggered action without user; failures to write it never fail the caller. */
    void recordSystemAudit(String action, AuditLogEntry.Outcome outcome, UUID deploymentId, Map<String, Object> details) {
        try {
            audit.record(new AuditRecord(null, SYSTEM_USER, action, outcome, "DEPLOYMENT", deploymentId.toString(), details));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry {}: {}", action, e.toString());
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
        try {
            DeploymentVersion source = rollbackTo == null ? deploymentService.currentVersion(deployment) : versionOf(deployment, rollbackTo);
            String envText = decryptEnv(source);
            GitCheckout checkout = pullGit ? pull(deployment, endpoint, envText) : null;
            if (!pullGit) {
                alignCheckout(deployment, source);
            }
            String yaml = checkout == null ? source.getYamlContent() : checkout.composeYaml();
            VersionSource versionSource = rollbackTo != null ? VersionSource.ROLLBACK
                    : pullGit ? VersionSource.GIT : source.getSource();
            String commitSha = checkout == null ? source.getCommitSha() : checkout.commitSha();
            DeploymentVersion version = tx.execute(status -> {
                DeploymentVersion v = new DeploymentVersion(deployment.getId(), yaml, source.getEnvCiphertext(), versionSource,
                        userId, clock.instant());
                v.setCommitSha(commitSha);
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
            files.write(hostId, deployment.getName(), version.getYamlContent(), envText);

            String runId = UUID.randomUUID().toString();
            // The ScopedValue is not inherited by the worker thread: capture the AI marker now, write it into the result entry.
            Map<String, Object> aiMarker = AiInvocationContext.active() ? aiMarker() : Map.of();
            Consumer<String> sink = streams.start(runId);
            Thread.ofVirtual().name("deploy-" + runId).start(
                    () -> run(deployment, version, endpoint, runId, sink, SYSTEM_USER.equals(username) ? null : userId,
                            username, resultAction, aiMarker));
            started = true;
            return new DeploymentRunResponse(runId, version.getId());
        } finally {
            if (!started) {
                locks.release(deployment.getId());
            }
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
    private GitCheckout pull(Deployment deployment, DockerEndpoint endpoint, String envText) {
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
        boolean success = false;
        Map<String, Object> details = new java.util.LinkedHashMap<>(aiMarker);
        details.put("runId", runId);
        details.put("versionId", version.getId().toString());
        try {
            ComposeProcessResult result = execution.execute(deployment.getHostId(), deployment.getName(), ComposeCommand.UP,
                    endpoint, sink, deploymentService.composeFileOf(deployment));
            success = result.success();
            details.put("exitCode", result.exitCode());
            details.put("timedOut", result.timedOut());
        } catch (RuntimeException e) {
            log.warn("Deployment {} could not run: {}", runId, e.toString());
            details.put("error", "COMPOSE_UNAVAILABLE");
            sink.accept("docker compose could not be run");
        }
        try {
            boolean ok = success;
            tx.executeWithoutResult(status -> deployments.findById(deployment.getId()).ifPresent(s -> {
                s.setStatus(ok ? DeploymentStatus.RUNNING : DeploymentStatus.ERROR);
                if (ok) {
                    s.setLastDeployedAt(clock.instant());
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
        // Release before announcing the end so a client reacting to DONE/FAILED can start the next deployment.
        locks.release(deployment.getId());
        streams.complete(runId, success ? RunStatus.DONE : RunStatus.FAILED);
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
