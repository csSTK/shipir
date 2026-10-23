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

    StackDeploymentService(StackService stackService, StackRepository stacks, StackVersionRepository versions,
                           UserRepository users, ComposeFileRepository files, ComposeExecutionService execution,
                           ComposeOutputStreamService streams, AuditService audit, SecretCipher cipher, Clock clock,
                           DeploymentLocks locks, PlatformTransactionManager txManager) {
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
    }

    @Auditable(action = "STACK_DEPLOY", targetType = "STACK", targetId = "#id")
    public DeploymentResponse deploy(UUID hostId, UUID id, String username) {
        return start(hostId, id, username, null, "STACK_DEPLOY_RESULT");
    }

    @Auditable(action = "STACK_ROLLBACK", targetType = "STACK", targetId = "#id")
    public DeploymentResponse rollback(UUID hostId, UUID id, UUID versionId, String username) {
        return start(hostId, id, username, versionId, "STACK_ROLLBACK_RESULT");
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
                result = execution.execute(hostId, stack.getName(), ComposeCommand.DOWN, endpoint, line -> { });
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

    private DeploymentResponse start(UUID hostId, UUID id, String username, UUID rollbackTo, String resultAction) {
        var host = stackService.requireHost(hostId);
        Stack stack = stackService.requireStack(hostId, id);
        DockerEndpoint endpoint = stackService.endpointOf(host);
        UUID userId = users.findByUsername(username).orElseThrow().getId();
        locks.acquire(stack.getId());
        boolean started = false;
        try {
            StackVersion source = rollbackTo == null ? stackService.currentVersion(stack) : versionOf(stack, rollbackTo);
            String envText = decryptEnv(source);
            StackVersion version = tx.execute(status -> {
                StackVersion v = versions.save(new StackVersion(stack.getId(), source.getYamlContent(),
                        source.getEnvCiphertext(), rollbackTo == null ? source.getSource() : VersionSource.ROLLBACK,
                        userId, clock.instant()));
                v.markDeployed(clock.instant());
                versions.save(v);
                stack.setCurrentVersionId(v.getId());
                stacks.save(stack);
                return v;
            });
            files.write(hostId, stack.getName(), version.getYamlContent(), envText);

            String deploymentId = UUID.randomUUID().toString();
            Consumer<String> sink = streams.start(deploymentId);
            Thread.ofVirtual().name("deploy-" + deploymentId).start(
                    () -> run(stack, version, endpoint, deploymentId, sink, userId, username, resultAction));
            started = true;
            return new DeploymentResponse(deploymentId, version.getId());
        } finally {
            if (!started) {
                locks.release(stack.getId());
            }
        }
    }

    private void run(Stack stack, StackVersion version, DockerEndpoint endpoint, String deploymentId,
                     Consumer<String> sink, UUID userId, String username, String resultAction) {
        boolean success = false;
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("deploymentId", deploymentId);
        details.put("versionId", version.getId().toString());
        try {
            ComposeProcessResult result = execution.execute(stack.getHostId(), stack.getName(), ComposeCommand.UP,
                    endpoint, sink);
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
