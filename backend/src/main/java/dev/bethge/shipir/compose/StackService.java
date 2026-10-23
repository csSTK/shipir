package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.dto.AutoUpdateRequest;
import dev.bethge.shipir.compose.dto.GitInfo;
import dev.bethge.shipir.compose.dto.StackCreateRequest;
import dev.bethge.shipir.compose.dto.StackDetailResponse;
import dev.bethge.shipir.compose.dto.StackEnvResponse;
import dev.bethge.shipir.compose.dto.StackSummaryResponse;
import dev.bethge.shipir.compose.dto.StackUpdateRequest;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.compose.validation.ComposeValidationService;
import dev.bethge.shipir.compose.validation.ValidationResult;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * CRUD of managed stacks. The compose file and .env are written to disk (where {@code docker compose} reads them) and
 * validated with {@code docker compose config} before a change counts; the .env content is additionally stored
 * encrypted in the current {@link StackVersion}. A failed validation leaves the previous state untouched. Neither
 * YAML nor env values are ever logged or audited.
 */
@Service
public class StackService {

    private static final int MAX_ERROR_LINES = 20;

    private final StackRepository stacks;
    private final StackVersionRepository versions;
    private final DockerHostRepository hosts;
    private final UserRepository users;
    private final HostEndpointResolver endpoints;
    private final ComposeFileRepository files;
    private final ComposeValidationService validation;
    private final ComposeExecutionService execution;
    private final SecretCipher cipher;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final DeploymentLocks locks;
    private final GitRepositoryService git;

    StackService(StackRepository stacks, StackVersionRepository versions, DockerHostRepository hosts,
                 UserRepository users, HostEndpointResolver endpoints, ComposeFileRepository files,
                 ComposeValidationService validation, ComposeExecutionService execution, SecretCipher cipher,
                 Clock clock, PlatformTransactionManager txManager, DeploymentLocks locks,
                 GitRepositoryService git) {
        this.stacks = stacks;
        this.versions = versions;
        this.hosts = hosts;
        this.users = users;
        this.endpoints = endpoints;
        this.files = files;
        this.validation = validation;
        this.execution = execution;
        this.cipher = cipher;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.locks = locks;
        this.git = git;
    }

    public List<StackSummaryResponse> list(UUID hostId) {
        requireHost(hostId);
        return stacks.findByHostIdOrderByNameAsc(hostId).stream()
                .map(s -> new StackSummaryResponse(s.getId(), s.getName(), s.getStatus(), s.getSourceType(),
                        s.getLastDeployedAt(), s.getCreatedAt()))
                .toList();
    }

    /** Current YAML and the names of the env variables; values stay masked (see {@link #env}). */
    public StackDetailResponse get(UUID hostId, UUID id) {
        requireHost(hostId);
        Stack stack = requireStack(hostId, id);
        String yaml = currentVersion(stack).getYamlContent();
        List<String> keys = files.readEnv(hostId, stack.getName())
                .map(text -> EnvFile.parse(text).stream().map(EnvEntry::key).toList())
                .orElse(List.of());
        return toDetail(stack, yaml, keys);
    }

    /** Unmasked env values from the encrypted store; the caller must restrict this to ADMIN and OPERATOR. */
    public StackEnvResponse env(UUID hostId, UUID id) {
        requireHost(hostId);
        Stack stack = requireStack(hostId, id);
        byte[] ciphertext = currentVersion(stack).getEnvCiphertext();
        if (ciphertext == null) {
            return new StackEnvResponse(List.of());
        }
        try {
            return new StackEnvResponse(EnvFile.parse(cipher.decrypt(ciphertext)));
        } catch (EncryptionKeyMissingException e) {
            throw keyMissing();
        } catch (SecretDecryptionException e) {
            throw new StackException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored environment variables could not be decrypted");
        }
    }

    @Auditable(action = "STACK_CREATE", targetType = "STACK", targetId = "#result?.id")
    public StackDetailResponse create(UUID hostId, StackCreateRequest request, String username) {
        DockerHost host = requireHost(hostId);
        if (request.source() == StackSource.GIT) {
            return createGit(hostId, host, request, username);
        }
        if (request.yaml() == null || request.yaml().isBlank()) {
            throw missing("yaml");
        }
        String name = validName(request.name());
        String envText = renderEnv(request.env());
        UUID userId = users.findByUsername(username).orElseThrow().getId();
        DockerEndpoint endpoint = endpointOf(host);

        Stack stack;
        try {
            stack = tx.execute(status -> {
                Stack s = stacks.saveAndFlush(new Stack(name, hostId,
                        hostId + "/" + name + "/" + ComposeFileRepository.COMPOSE_FILE, userId, clock.instant()));
                StackVersion v = versions.save(new StackVersion(s.getId(), request.yaml(), encrypt(envText), userId,
                        clock.instant()));
                s.setCurrentVersionId(v.getId());
                return stacks.save(s);
            });
        } catch (DataIntegrityViolationException e) {
            throw new StackException(HttpStatus.CONFLICT, "STACK_NAME_TAKEN",
                    "A stack with this name already exists on this environment");
        }
        try {
            writeAndValidate(hostId, name, request.yaml(), envText, endpoint);
        } catch (RuntimeException e) {
            stacks.deleteById(stack.getId());
            files.delete(hostId, name);
            throw e;
        }
        return toDetail(stack, request.yaml(), envKeys(envText));
    }

    /**
     * Creates a stack from a repository: the stack row exists first (claims the name, so a checkout can never clobber
     * another stack's directory), then the ref is fetched and the compose file read and validated. Any failure removes
     * the row and the directory again.
     */
    private StackDetailResponse createGit(UUID hostId, DockerHost host, StackCreateRequest r, String username) {
        String name = validName(r.name());
        requireText(r.repoUrl(), "repoUrl");
        requireText(r.ref(), "ref");
        requireText(r.composePath(), "composePath");
        git.validateUrl(r.repoUrl());
        if (r.credentialId() != null && !git.credentialExists(r.credentialId())) {
            throw new StackException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Git credential not found");
        }
        String envText = renderEnv(r.env());
        UUID userId = users.findByUsername(username).orElseThrow().getId();
        DockerEndpoint endpoint = endpointOf(host);
        String repoUrl = r.repoUrl().trim();
        String ref = r.ref().trim();

        Stack stack;
        try {
            stack = tx.execute(status -> {
                Stack s = new Stack(name, hostId, hostId + "/" + name + "/" + ComposeFileRepository.COMPOSE_FILE,
                        userId, clock.instant());
                s.useGit(repoUrl, ref, r.composePath().trim(), r.credentialId());
                s = stacks.saveAndFlush(s);
                StackVersion v = versions.save(new StackVersion(s.getId(), "", encrypt(envText), VersionSource.GIT,
                        userId, clock.instant()));
                s.setCurrentVersionId(v.getId());
                return stacks.save(s);
            });
        } catch (DataIntegrityViolationException e) {
            throw new StackException(HttpStatus.CONFLICT, "STACK_NAME_TAKEN",
                    "A stack with this name already exists on this environment");
        }
        try {
            GitCheckout checkout = git.sync(files.repoDir(hostId, name), repoUrl, ref, r.composePath().trim(),
                    r.credentialId());
            writeAndValidate(hostId, name, checkout.composeYaml(), envText, endpoint);
            tx.executeWithoutResult(status -> {
                StackVersion v = versions.findById(stack.getCurrentVersionId()).orElseThrow();
                v.replaceContent(checkout.composeYaml(), v.getEnvCiphertext());
                v.setCommitSha(checkout.commitSha());
                versions.save(v);
                Stack s = stacks.findById(stack.getId()).orElseThrow();
                s.setLastCommitSha(checkout.commitSha());
                stacks.save(s);
            });
            return toDetail(stacks.findById(stack.getId()).orElseThrow(), checkout.composeYaml(), envKeys(envText));
        } catch (RuntimeException e) {
            stacks.deleteById(stack.getId());
            files.delete(hostId, name);
            throw e;
        }
    }

    /** Turns Git polling on or off for a Git stack. 400 NOT_A_GIT_STACK / VALIDATION_ERROR (no interval). */
    @Auditable(action = "STACK_AUTO_UPDATE_CONFIG", targetType = "STACK", targetId = "#id")
    public StackDetailResponse configureAutoUpdate(UUID hostId, UUID id, AutoUpdateRequest request) {
        requireHost(hostId);
        Stack stack = requireStack(hostId, id);
        if (stack.getSourceType() != StackSource.GIT) {
            throw new StackException(HttpStatus.BAD_REQUEST, "NOT_A_GIT_STACK", "This stack is not sourced from Git");
        }
        if (request.enabled() && request.intervalSeconds() == null && stack.getAutoUpdateIntervalSeconds() == null) {
            throw missing("intervalSeconds");
        }
        stack.configureAutoUpdate(request.enabled(), request.intervalSeconds());
        stacks.save(stack);
        return get(hostId, id);
    }

    @Auditable(action = "STACK_UPDATE", targetType = "STACK", targetId = "#id")
    public StackDetailResponse update(UUID hostId, UUID id, StackUpdateRequest request, String username) {
        DockerHost host = requireHost(hostId);
        Stack found = requireStack(hostId, id);
        locks.acquire(found.getId());
        try {
            // re-read under the lock: a deployment that just finished may have changed the current version
            return updateLocked(hostId, host, requireStack(hostId, id), request, username);
        } finally {
            locks.release(found.getId());
        }
    }

    private StackDetailResponse updateLocked(UUID hostId, DockerHost host, Stack stack, StackUpdateRequest request,
                                             String username) {
        String name = stack.getName();
        StackVersion version = currentVersion(stack);
        boolean gitManaged = stack.getSourceType() == StackSource.GIT;
        String yaml = request.yaml();
        if (gitManaged) {
            if (yaml != null && !yaml.equals(version.getYamlContent())) {
                throw new StackException(HttpStatus.BAD_REQUEST, "GIT_MANAGED",
                        "The compose file of a Git stack comes from the repository and cannot be edited here");
            }
            yaml = version.getYamlContent();
        } else if (yaml == null || yaml.isBlank()) {
            throw missing("yaml");
        }
        String newYaml = yaml;
        String previousYaml = files.readCompose(hostId, name).orElse(null);
        String previousEnv = files.readEnv(hostId, name).orElse(null);
        String envText = request.env() == null ? previousEnv : renderEnv(request.env());
        byte[] envCiphertext = request.env() == null ? version.getEnvCiphertext() : encrypt(envText);
        DockerEndpoint endpoint = endpointOf(host);

        try {
            writeAndValidate(hostId, name, newYaml, envText, endpoint);
            UUID userId = users.findByUsername(username).orElseThrow().getId();
            tx.executeWithoutResult(status -> {
                if (version.isDeployed()) {
                    // deployed versions are immutable (rollback must restore what really ran): start a new draft
                    StackVersion draft = new StackVersion(stack.getId(), newYaml, envCiphertext,
                            gitManaged ? VersionSource.GIT : VersionSource.MANUAL, userId, clock.instant());
                    draft.setCommitSha(version.getCommitSha());
                    draft = versions.save(draft);
                    stack.setCurrentVersionId(draft.getId());
                    stacks.save(stack);
                } else {
                    version.replaceContent(newYaml, envCiphertext);
                    versions.save(version);
                }
            });
        } catch (RuntimeException e) {
            restore(hostId, name, previousYaml, previousEnv);
            throw e;
        }
        return toDetail(stack, newYaml, envKeys(envText));
    }

    /** Runs {@code docker compose down}, then removes the files and the stack record. */
    @Auditable(action = "STACK_DELETE", targetType = "STACK", targetId = "#id")
    public void delete(UUID hostId, UUID id) {
        DockerHost host = requireHost(hostId);
        Stack stack = requireStack(hostId, id);
        DockerEndpoint endpoint = endpointOf(host);
        locks.acquire(stack.getId());
        try {
            deleteLocked(hostId, stack, endpoint);
        } finally {
            locks.release(stack.getId());
        }
    }

    private void deleteLocked(UUID hostId, Stack stack, DockerEndpoint endpoint) {
        ComposeProcessResult result;
        try {
            result = execution.execute(hostId, stack.getName(), ComposeCommand.DOWN, endpoint, line -> { });
        } catch (ComposeExecutionException e) {
            throw new StackException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED", "docker compose down could not run");
        }
        if (!result.success()) {
            List<String> output = result.output().stream().filter(l -> !l.isBlank()).toList();
            List<String> tail = output.subList(Math.max(0, output.size() - MAX_ERROR_LINES), output.size());
            throw new StackException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                    result.timedOut() ? "docker compose down timed out" : "docker compose down failed", tail);
        }
        stacks.delete(stack);
        stacks.flush();
        files.delete(hostId, stack.getName());
    }

    private void writeAndValidate(UUID hostId, String name, String yaml, String envText, DockerEndpoint endpoint) {
        files.write(hostId, name, yaml, envText);
        ValidationResult result;
        try {
            result = validation.validate(hostId, name, endpoint);
        } catch (ComposeExecutionException e) {
            throw new StackException(HttpStatus.BAD_GATEWAY, "COMPOSE_UNAVAILABLE", "docker compose could not run");
        }
        if (!result.valid()) {
            throw new StackException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE", "The compose file is invalid",
                    result.errors());
        }
    }

    private void restore(UUID hostId, String name, String yaml, String env) {
        if (yaml == null) {
            files.delete(hostId, name);
        } else {
            files.write(hostId, name, yaml, env);
        }
    }

    private String renderEnv(List<EnvEntry> env) {
        if (env == null || env.isEmpty()) {
            return null;
        }
        String text = EnvFile.render(env);
        if (!cipher.isConfigured()) {
            throw keyMissing();
        }
        return text;
    }

    private byte[] encrypt(String envText) {
        return envText == null ? null : cipher.encrypt(envText);
    }

    private static List<String> envKeys(String envText) {
        return envText == null ? List.of() : EnvFile.parse(envText).stream().map(EnvEntry::key).toList();
    }

    private static String validName(String name) {
        try {
            return PathValidationUtil.validateStackName(name);
        } catch (PathValidationException e) {
            throw new StackException(HttpStatus.BAD_REQUEST, "INVALID_STACK_NAME",
                    "The name must be 1-63 characters: lowercase letters, digits, - and _, starting with a letter or digit");
        }
    }

    DockerEndpoint endpointOf(DockerHost host) {
        try {
            return endpoints.endpointOf(host);
        } catch (EncryptionKeyMissingException e) {
            throw keyMissing();
        } catch (SecretDecryptionException e) {
            throw new StackException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        }
    }

    DockerHost requireHost(UUID hostId) {
        return hosts.findById(hostId).orElseThrow(
                () -> new StackException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
    }

    Stack requireStack(UUID hostId, UUID id) {
        return stacks.findByIdAndHostId(id, hostId).orElseThrow(
                () -> new StackException(HttpStatus.NOT_FOUND, "STACK_NOT_FOUND", "Stack not found"));
    }

    StackVersion currentVersion(Stack stack) {
        return versions.findById(stack.getCurrentVersionId()).orElseThrow(
                () -> new StackException(HttpStatus.NOT_FOUND, "STACK_NOT_FOUND", "Stack not found"));
    }

    static StackException keyMissing() {
        return new StackException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                "Environment variables need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to be stored");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw missing(field);
        }
    }

    private static StackException missing(String field) {
        return new StackException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed",
                List.of(field + ": must not be blank"));
    }

    private static StackDetailResponse toDetail(Stack s, String yaml, List<String> envKeys) {
        GitInfo gitInfo = s.getSourceType() == StackSource.GIT
                ? new GitInfo(s.getGitRepoUrl(), s.getGitRef(), s.getGitComposePath(), s.getGitCredentialId(),
                        s.getLastCommitSha(), s.isAutoUpdateEnabled(), s.getAutoUpdateIntervalSeconds(),
                        s.getLastPolledAt(), s.getLastPollError())
                : null;
        return new StackDetailResponse(s.getId(), s.getName(), s.getStatus(), s.getSourceType(),
                s.getLastDeployedAt(), s.getCreatedAt(), yaml, envKeys, gitInfo);
    }
}
