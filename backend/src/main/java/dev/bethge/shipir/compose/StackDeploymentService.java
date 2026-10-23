package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.compose.dto.DeploymentResponse;
import dev.bethge.shipir.compose.dto.StackSummaryResponse;
import dev.bethge.shipir.compose.dto.StackVersionResponse;
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
import dev.bethge.shipir.streaming.DeploymentStatus;
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
 * Deploy, stop, version history and rollback of managed stacks.
 *
 * <p>A deploy snapshots the current draft as a new {@link StackVersion}, writes it to disk and runs
 * {@code docker compose up} on a virtual thread; the HTTP call returns the deployment id for streaming.
 *
 * <p><b>Audit of async runs:</b> the {@code @Auditable} entry (e.g. {@code STACK_DEPLOY}) records that the request was
 * accepted or rejected (409, key missing, ...). The process result is not known yet at that point, so the worker
 * records a follow-up entry ({@code STACK_DEPLOY_RESULT} / {@code STACK_ROLLBACK_RESULT}) via {@link AuditService}
 * with SUCCESS or FAILURE depending on the compose exit code. Compose output, YAML and env are never audited.
 */
@Service
public class StackDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(StackDeploymentService.class);
    private static final String SYSTEM_USER = "system";

    private final StackService stackService;
    private final StackRepository stacks;
    private final StackVersionRepository versions;
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

    StackDeploymentService(StackService stackService, StackRepository stacks, StackVersionRepository versions,
                           UserRepository users, ComposeFileRepository files, ComposeExecutionService execution,
                           ComposeOutputStreamService streams, AuditService audit, SecretCipher cipher, Clock clock,
                           DeploymentLocks locks, PlatformTransactionManager txManager,
                           GitRepositoryService git, ComposeValidationService validation) {
        this.stackService = stackService;
        this.stacks = stacks;
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

    @Auditable(action = "STACK_DEPLOY", targetType = "STACK", targetId = "#id")
    public DeploymentResponse deploy(UUID hostId, UUID id, String username) {
        return start(hostId, id, userIdOf(username), username, null, "STACK_DEPLOY_RESULT", false);
    }

    @Auditable(action = "STACK_ROLLBACK", targetType = "STACK", targetId = "#id")
    public DeploymentResponse rollback(UUID hostId, UUID id, UUID versionId, String username) {
        return start(hostId, id, userIdOf(username), username, versionId, "STACK_ROLLBACK_RESULT", false);
    }

    /**
     * "Pull &amp; redeploy": fetches the stack's ref again, records the checked-out commit as a new GIT version and
     * deploys it. Also runs when the commit has not changed (manual trigger). 400 NOT_A_GIT_STACK for FILE stacks.
     */
    @Auditable(action = "STACK_GIT_REDEPLOY", targetType = "STACK", targetId = "#id")
    public DeploymentResponse gitRedeploy(UUID hostId, UUID id, String username) {
        return start(hostId, id, userIdOf(username), username, null, "STACK_GIT_REDEPLOY_RESULT", true);
    }

    /** Runs {@code docker compose down} and waits for it; the files and the stack record stay. */
    @Auditable(action = "STACK_STOP", targetType = "STACK", targetId = "#id")
    public StackSummaryResponse stop(UUID hostId, UUID id) {
        var host = stackService.requireHost(hostId);
        Stack stack = stackService.requireStack(hostId, id);
        DockerEndpoint endpoint = stackService.endpointOf(host);
        locks.acquire(stack.getId());
        try {
            ComposeProcessResult result;
            try {
                result = execution.execute(hostId, stack.getName(), ComposeCommand.DOWN, endpoint, line -> { },
                        stackService.composeFileOf(stack));
            } catch (ComposeExecutionException e) {
                throw new StackException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                        "docker compose down could not run");
            }
            if (!result.success()) {
                List<String> output = result.output().stream().filter(l -> !l.isBlank()).toList();
                throw new StackException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                        result.timedOut() ? "docker compose down timed out" : "docker compose down failed",
                        output.subList(Math.max(0, output.size() - 20), output.size()));
            }
            stack.setStatus(StackStatus.STOPPED);
            stacks.save(stack);
            return new StackSummaryResponse(stack.getId(), stack.getName(), stack.getStatus(),
                    stack.getSourceType(), stack.getLastDeployedAt(), stack.getCreatedAt());
        } finally {
            locks.release(stack.getId());
        }
    }

    /** Version history, newest first. */
    public List<StackVersionResponse> versions(UUID hostId, UUID id) {
        stackService.requireHost(hostId);
        Stack stack = stackService.requireStack(hostId, id);
        List<StackVersion> all = versions.findByStackIdOrderByCreatedAtDesc(stack.getId());
        Map<UUID, String> names = users.findAllById(all.stream().map(StackVersion::getCreatedBy).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return all.stream()
                .map(v -> new StackVersionResponse(v.getId(), v.getSource(), v.getCommitSha(),
                        names.get(v.getCreatedBy()), v.getCreatedAt(), v.getId().equals(stack.getCurrentVersionId()),
                        v.isDeployed()))
                .toList();
    }

    /**
     * Pull &amp; redeploy triggered by {@code GitPollingScheduler} (through {@code StackAutoUpdates}). Runs as the
     * audit user {@code system} (no user id); the new version is attributed to the stack's creator because
     * {@code stack_versions.created_by} is mandatory. Not {@code @Auditable} (no request, no authentication): it
     * records {@code STACK_AUTO_UPDATE} itself once the deployment was accepted, {@code STACK_AUTO_UPDATE_RESULT}
     * follows from the worker.
     *
     * @throws StackException 409 DEPLOYMENT_IN_PROGRESS if the stack is busy
     */
    DeploymentResponse autoUpdate(UUID stackId) {
        Stack stack = stacks.findById(stackId).orElseThrow(
                () -> new StackException(HttpStatus.NOT_FOUND, "STACK_NOT_FOUND", "Stack not found"));
        DeploymentResponse response = start(stack.getHostId(), stackId, stack.getCreatedBy(), SYSTEM_USER, null,
                "STACK_AUTO_UPDATE_RESULT", true);
        recordSystemAudit("STACK_AUTO_UPDATE", AuditLogEntry.Outcome.SUCCESS, stackId,
                Map.of("deploymentId", response.deploymentId(), "versionId", response.versionId().toString()));
        return response;
    }

    /** Audit entry of a system-triggered action without user; failures to write it never fail the caller. */
    void recordSystemAudit(String action, AuditLogEntry.Outcome outcome, UUID stackId, Map<String, Object> details) {
        try {
            audit.record(new AuditRecord(null, SYSTEM_USER, action, outcome, "STACK", stackId.toString(), details));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry {}: {}", action, e.toString());
        }
    }

    private UUID userIdOf(String username) {
        return users.findByUsername(username).orElseThrow().getId();
    }

    private DeploymentResponse start(UUID hostId, UUID id, UUID userId, String username, UUID rollbackTo,
                                    String resultAction, boolean pullGit) {
        var host = stackService.requireHost(hostId);
        Stack stack = stackService.requireStack(hostId, id);
        DockerEndpoint endpoint = stackService.endpointOf(host);
        locks.acquire(stack.getId());
        boolean started = false;
        try {
            StackVersion source = rollbackTo == null ? stackService.currentVersion(stack) : versionOf(stack, rollbackTo);
            String envText = decryptEnv(source);
            GitCheckout checkout = pullGit ? pull(stack, endpoint, envText) : null;
            if (!pullGit) {
                alignCheckout(stack, source);
            }
            String yaml = checkout == null ? source.getYamlContent() : checkout.composeYaml();
            VersionSource versionSource = rollbackTo != null ? VersionSource.ROLLBACK
                    : pullGit ? VersionSource.GIT : source.getSource();
            String commitSha = checkout == null ? source.getCommitSha() : checkout.commitSha();
            StackVersion version = tx.execute(status -> {
                StackVersion v = new StackVersion(stack.getId(), yaml, source.getEnvCiphertext(), versionSource,
                        userId, clock.instant());
                v.setCommitSha(commitSha);
                v = versions.save(v);
                v.markDeployed(clock.instant());
                versions.save(v);
                stack.setCurrentVersionId(v.getId());
                if (checkout != null) {
                    stack.setLastCommitSha(commitSha);
                }
                stacks.save(stack);
                return v;
            });
            files.write(hostId, stack.getName(), version.getYamlContent(), envText);

            String deploymentId = UUID.randomUUID().toString();
            Consumer<String> sink = streams.start(deploymentId);
            Thread.ofVirtual().name("deploy-" + deploymentId).start(
                    () -> run(stack, version, endpoint, deploymentId, sink, SYSTEM_USER.equals(username) ? null : userId,
                            username, resultAction));
            started = true;
            return new DeploymentResponse(deploymentId, version.getId());
        } finally {
            if (!started) {
                locks.release(stack.getId());
            }
        }
    }

    /**
     * Deploy and rollback of a Git stack run the files of the checkout, so the checkout must be at the commit of the
     * version being deployed (a rollback target, or after a failed pull that left a newer commit behind). The stored
     * YAML snapshot alone would not do: build contexts, bind mounts and env_file come from the repository at that commit.
     */
    private void alignCheckout(Stack stack, StackVersion version) {
        if (stack.getSourceType() == StackSource.GIT && version.getCommitSha() != null) {
            git.ensureCommit(files.repoDir(stack.getHostId(), stack.getName()), stack.getGitRepoUrl(),
                    version.getCommitSha(), stack.getGitComposePath(), stack.getGitCredentialId());
        }
    }

    /** Fetches the ref into the checkout and validates the compose file; the previous file is restored when invalid. */
    private GitCheckout pull(Stack stack, DockerEndpoint endpoint, String envText) {
        if (stack.getSourceType() != StackSource.GIT) {
            throw new StackException(HttpStatus.BAD_REQUEST, "NOT_A_GIT_STACK", "This stack is not sourced from Git");
        }
        UUID hostId = stack.getHostId();
        String name = stack.getName();
        GitCheckout checkout = git.sync(files.repoDir(hostId, name), stack.getGitRepoUrl(), stack.getGitRef(),
                stack.getGitComposePath(), stack.getGitCredentialId());
        String previousYaml = files.readCompose(hostId, name).orElse(null);
        files.write(hostId, name, checkout.composeYaml(), envText);
        ValidationResult result;
        try {
            result = validation.validate(hostId, name, endpoint, stackService.composeFileOf(stack));
        } catch (ComposeExecutionException e) {
            restore(hostId, name, previousYaml, envText);
            throw new StackException(HttpStatus.BAD_GATEWAY, "COMPOSE_UNAVAILABLE", "docker compose could not run");
        }
        if (!result.valid()) {
            restore(hostId, name, previousYaml, envText);
            throw new StackException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE", "The compose file is invalid",
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

    private void run(Stack stack, StackVersion version, DockerEndpoint endpoint, String deploymentId,
                     Consumer<String> sink, UUID userId, String username, String resultAction) {
        boolean success = false;
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("deploymentId", deploymentId);
        details.put("versionId", version.getId().toString());
        try {
            ComposeProcessResult result = execution.execute(stack.getHostId(), stack.getName(), ComposeCommand.UP,
                    endpoint, sink, stackService.composeFileOf(stack));
            success = result.success();
            details.put("exitCode", result.exitCode());
            details.put("timedOut", result.timedOut());
        } catch (RuntimeException e) {
            log.warn("Deployment {} could not run: {}", deploymentId, e.toString());
            details.put("error", "COMPOSE_UNAVAILABLE");
            sink.accept("docker compose could not be run");
        }
        try {
            boolean ok = success;
            tx.executeWithoutResult(status -> stacks.findById(stack.getId()).ifPresent(s -> {
                s.setStatus(ok ? StackStatus.RUNNING : StackStatus.ERROR);
                if (ok) {
                    s.setLastDeployedAt(clock.instant());
                }
                stacks.save(s);
            }));
        } catch (RuntimeException e) {
            log.warn("Could not update status of deployment {}: {}", deploymentId, e.toString());
        }
        try {
            audit.record(new AuditRecord(userId, username, resultAction,
                    success ? AuditLogEntry.Outcome.SUCCESS : AuditLogEntry.Outcome.FAILURE,
                    "STACK", stack.getId().toString(), details));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry for deployment {}: {}", deploymentId, e.toString());
        }
        // Release before announcing the end so a client reacting to DONE/FAILED can start the next deployment.
        locks.release(stack.getId());
        streams.complete(deploymentId, success ? DeploymentStatus.DONE : DeploymentStatus.FAILED);
    }

    private StackVersion versionOf(Stack stack, UUID versionId) {
        return versions.findById(versionId).filter(v -> v.getStackId().equals(stack.getId())).orElseThrow(
                () -> new StackException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Version not found"));
    }

    private String decryptEnv(StackVersion version) {
        if (version.getEnvCiphertext() == null) {
            return null;
        }
        try {
            return cipher.decrypt(version.getEnvCiphertext());
        } catch (EncryptionKeyMissingException e) {
            throw StackService.keyMissing();
        } catch (SecretDecryptionException e) {
            throw new StackException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored environment variables could not be decrypted");
        }
    }
}
