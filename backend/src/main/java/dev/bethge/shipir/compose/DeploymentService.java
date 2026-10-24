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
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import dev.bethge.shipir.compose.dto.DeploymentEnvResponse;
import dev.bethge.shipir.compose.dto.DeploymentSummaryResponse;
import dev.bethge.shipir.compose.dto.DeploymentUpdateRequest;
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
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * CRUD of managed deployments. The compose file and .env are written to disk (where {@code docker compose} reads them) and
 * validated with {@code docker compose config} before a change counts; the .env content is additionally stored
 * encrypted in the current {@link DeploymentVersion}. A failed validation leaves the previous state untouched. Neither
 * YAML nor env values are ever logged or audited.
 */
@Service
public class DeploymentService {

    private static final int MAX_ERROR_LINES = 20;

    private final DeploymentRepository deployments;
    private final DeploymentVersionRepository versions;
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

    DeploymentService(DeploymentRepository deployments, DeploymentVersionRepository versions, DockerHostRepository hosts,
                 UserRepository users, HostEndpointResolver endpoints, ComposeFileRepository files,
                 ComposeValidationService validation, ComposeExecutionService execution, SecretCipher cipher,
                 Clock clock, PlatformTransactionManager txManager, DeploymentLocks locks,
                 GitRepositoryService git) {
        this.deployments = deployments;
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

    /** Validated on-disk directory of a managed deployment (may not exist before the first write); for the config editor. */
    public java.nio.file.Path directoryOf(UUID hostId, UUID id) {
        requireHost(hostId);
        return files.deploymentDir(hostId, requireDeployment(hostId, id).getName());
    }

    public List<DeploymentSummaryResponse> list(UUID hostId) {
        requireHost(hostId);
        return deployments.findByHostIdOrderByNameAsc(hostId).stream()
                .map(s -> new DeploymentSummaryResponse(s.getId(), s.getName(), s.getStatus(), s.getSourceType(),
                        s.getLastDeployedAt(), s.getCreatedAt()))
                .toList();
    }

    /** Current YAML and the names of the env variables; values stay masked (see {@link #env}). */
    public DeploymentDetailResponse get(UUID hostId, UUID id) {
        requireHost(hostId);
        Deployment deployment = requireDeployment(hostId, id);
        String yaml = currentVersion(deployment).getYamlContent();
        List<String> keys = files.readEnv(hostId, deployment.getName())
                .map(text -> EnvFile.parse(text).stream().map(EnvEntry::key).toList())
                .orElse(List.of());
        return toDetail(deployment, yaml, keys);
    }

    /** Unmasked env values from the encrypted store; the caller must restrict this to ADMIN and OPERATOR. */
    public DeploymentEnvResponse env(UUID hostId, UUID id) {
        requireHost(hostId);
        Deployment deployment = requireDeployment(hostId, id);
        byte[] ciphertext = currentVersion(deployment).getEnvCiphertext();
        if (ciphertext == null) {
            return new DeploymentEnvResponse(List.of());
        }
        try {
            return new DeploymentEnvResponse(EnvFile.parse(cipher.decrypt(ciphertext)));
        } catch (EncryptionKeyMissingException e) {
            throw keyMissing();
        } catch (SecretDecryptionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored environment variables could not be decrypted");
        }
    }

    @Auditable(action = "DEPLOYMENT_CREATE", targetType = "DEPLOYMENT", targetId = "#result?.id")
    public DeploymentDetailResponse create(UUID hostId, DeploymentCreateRequest request, String username) {
        DockerHost host = requireHost(hostId);
        if (request.source() == DeploymentSource.GIT) {
            return createGit(hostId, host, request, username);
        }
        if (request.yaml() == null || request.yaml().isBlank()) {
            throw missing("yaml");
        }
        String name = validName(request.name());
        String envText = renderEnv(request.env());
        UUID userId = users.findByUsername(username).orElseThrow().getId();
        DockerEndpoint endpoint = endpointOf(host);

        Deployment deployment;
        try {
            deployment = tx.execute(status -> {
                Deployment s = deployments.saveAndFlush(new Deployment(name, hostId,
                        hostId + "/" + name + "/" + ComposeFileRepository.COMPOSE_FILE, userId, clock.instant()));
                DeploymentVersion v = versions.save(new DeploymentVersion(s.getId(), request.yaml(), encrypt(envText), userId,
                        clock.instant()));
                s.setCurrentVersionId(v.getId());
                return deployments.save(s);
            });
        } catch (DataIntegrityViolationException e) {
            throw new DeploymentException(HttpStatus.CONFLICT, "DEPLOYMENT_NAME_TAKEN",
                    "A deployment with this name already exists on this environment");
        }
        try {
            writeAndValidate(hostId, name, request.yaml(), envText, endpoint, null);
        } catch (RuntimeException e) {
            deployments.deleteById(deployment.getId());
            files.delete(hostId, name);
            throw e;
        }
        return toDetail(deployment, request.yaml(), envKeys(envText));
    }

    /**
     * Creates a deployment from a repository: the deployment row exists first (claims the name, so a checkout can never clobber
     * another deployment's directory), then the ref is fetched and the compose file read and validated. Any failure removes
     * the row and the directory again.
     */
    private DeploymentDetailResponse createGit(UUID hostId, DockerHost host, DeploymentCreateRequest r, String username) {
        String name = validName(r.name());
        requireText(r.repoUrl(), "repoUrl");
        requireText(r.ref(), "ref");
        requireText(r.composePath(), "composePath");
        git.validateUrl(r.repoUrl());
        if (r.credentialId() != null && !git.credentialExists(r.credentialId())) {
            throw new DeploymentException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Git credential not found");
        }
        String envText = renderEnv(r.env());
        UUID userId = users.findByUsername(username).orElseThrow().getId();
        DockerEndpoint endpoint = endpointOf(host);
        String repoUrl = r.repoUrl().trim();
        String ref = r.ref().trim();

        Deployment deployment;
        try {
            deployment = tx.execute(status -> {
                Deployment s = new Deployment(name, hostId, hostId + "/" + name + "/" + ComposeFileRepository.COMPOSE_FILE,
                        userId, clock.instant());
                s.useGit(repoUrl, ref, r.composePath().trim(), r.credentialId());
                s = deployments.saveAndFlush(s);
                DeploymentVersion v = versions.save(new DeploymentVersion(s.getId(), "", encrypt(envText), VersionSource.GIT,
                        userId, clock.instant()));
                s.setCurrentVersionId(v.getId());
                return deployments.save(s);
            });
        } catch (DataIntegrityViolationException e) {
            throw new DeploymentException(HttpStatus.CONFLICT, "DEPLOYMENT_NAME_TAKEN",
                    "A deployment with this name already exists on this environment");
        }
        try {
            GitCheckout checkout = git.sync(files.repoDir(hostId, name), repoUrl, ref, r.composePath().trim(),
                    r.credentialId());
            writeAndValidate(hostId, name, checkout.composeYaml(), envText, endpoint,
                    files.repoComposeFile(hostId, name, r.composePath().trim()));
            tx.executeWithoutResult(status -> {
                DeploymentVersion v = versions.findById(deployment.getCurrentVersionId()).orElseThrow();
                v.replaceContent(checkout.composeYaml(), v.getEnvCiphertext());
                v.setCommitSha(checkout.commitSha());
                versions.save(v);
                Deployment s = deployments.findById(deployment.getId()).orElseThrow();
                s.setLastCommitSha(checkout.commitSha());
                deployments.save(s);
            });
            return toDetail(deployments.findById(deployment.getId()).orElseThrow(), checkout.composeYaml(), envKeys(envText));
        } catch (RuntimeException e) {
            deployments.deleteById(deployment.getId());
            files.delete(hostId, name);
            throw e;
        }
    }

    /** Turns Git polling on or off for a Git deployment. 400 NOT_A_GIT_DEPLOYMENT / VALIDATION_ERROR (no interval). */
    @Auditable(action = "DEPLOYMENT_AUTO_UPDATE_CONFIG", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentDetailResponse configureAutoUpdate(UUID hostId, UUID id, AutoUpdateRequest request) {
        requireHost(hostId);
        Deployment deployment = requireDeployment(hostId, id);
        if (deployment.getSourceType() != DeploymentSource.GIT) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "NOT_A_GIT_DEPLOYMENT", "This deployment is not sourced from Git");
        }
        if (request.enabled() && request.intervalSeconds() == null && deployment.getAutoUpdateIntervalSeconds() == null) {
            throw missing("intervalSeconds");
        }
        deployment.configureAutoUpdate(request.enabled(), request.intervalSeconds());
        deployments.save(deployment);
        return get(hostId, id);
    }

    @Auditable(action = "DEPLOYMENT_UPDATE", targetType = "DEPLOYMENT", targetId = "#id")
    public DeploymentDetailResponse update(UUID hostId, UUID id, DeploymentUpdateRequest request, String username) {
        DockerHost host = requireHost(hostId);
        Deployment found = requireDeployment(hostId, id);
        locks.acquire(found.getId());
        try {
            // re-read under the lock: a deployment that just finished may have changed the current version
            return updateLocked(hostId, host, requireDeployment(hostId, id), request, username);
        } finally {
            locks.release(found.getId());
        }
    }

    private DeploymentDetailResponse updateLocked(UUID hostId, DockerHost host, Deployment deployment, DeploymentUpdateRequest request,
                                             String username) {
        String name = deployment.getName();
        DeploymentVersion version = currentVersion(deployment);
        boolean gitManaged = deployment.getSourceType() == DeploymentSource.GIT;
        String yaml = request.yaml();
        if (gitManaged) {
            if (yaml != null && !yaml.equals(version.getYamlContent())) {
                throw new DeploymentException(HttpStatus.BAD_REQUEST, "GIT_MANAGED",
                        "The compose file of a Git deployment comes from the repository and cannot be edited here");
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
            writeAndValidate(hostId, name, newYaml, envText, endpoint, composeFileOf(deployment));
            UUID userId = users.findByUsername(username).orElseThrow().getId();
            tx.executeWithoutResult(status -> {
                if (version.isDeployed()) {
                    // deployed versions are immutable (rollback must restore what really ran): start a new draft
                    DeploymentVersion draft = new DeploymentVersion(deployment.getId(), newYaml, envCiphertext,
                            gitManaged ? VersionSource.GIT : VersionSource.MANUAL, userId, clock.instant());
                    draft.setCommitSha(version.getCommitSha());
                    draft = versions.save(draft);
                    deployment.setCurrentVersionId(draft.getId());
                    deployments.save(deployment);
                } else {
                    version.replaceContent(newYaml, envCiphertext);
                    versions.save(version);
                }
            });
        } catch (RuntimeException e) {
            restore(hostId, name, previousYaml, previousEnv);
            throw e;
        }
        return toDetail(deployment, newYaml, envKeys(envText));
    }

    /** Runs {@code docker compose down}, then removes the files and the deployment record. */
    @Auditable(action = "DEPLOYMENT_DELETE", targetType = "DEPLOYMENT", targetId = "#id")
    public void delete(UUID hostId, UUID id) {
        DockerHost host = requireHost(hostId);
        Deployment deployment = requireDeployment(hostId, id);
        DockerEndpoint endpoint = endpointOf(host);
        locks.acquire(deployment.getId());
        try {
            deleteLocked(hostId, deployment, endpoint);
        } finally {
            locks.release(deployment.getId());
        }
    }

    private void deleteLocked(UUID hostId, Deployment deployment, DockerEndpoint endpoint) {
        ComposeProcessResult result;
        try {
            result = execution.execute(hostId, deployment.getName(), ComposeCommand.DOWN, endpoint, line -> { },
                    composeFileOf(deployment));
        } catch (ComposeExecutionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED", "docker compose down could not run");
        }
        if (!result.success()) {
            List<String> output = result.output().stream().filter(l -> !l.isBlank()).toList();
            List<String> tail = output.subList(Math.max(0, output.size() - MAX_ERROR_LINES), output.size());
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_DOWN_FAILED",
                    result.timedOut() ? "docker compose down timed out" : "docker compose down failed", tail);
        }
        deployments.delete(deployment);
        deployments.flush();
        files.delete(hostId, deployment.getName());
    }

    /**
     * Compose file Shipir runs for the deployment: {@code null} for FILE deployments (the stored {@code compose.yaml}); for Git
     * deployments the file inside the checkout, so its relative paths resolve against the repository.
     */
    Path composeFileOf(Deployment deployment) {
        if (deployment.getSourceType() != DeploymentSource.GIT) {
            return null;
        }
        try {
            return files.repoComposeFile(deployment.getHostId(), deployment.getName(), deployment.getGitComposePath());
        } catch (PathValidationException e) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE_PATH",
                    "The compose file path must be a relative path inside the repository");
        }
    }

    private void writeAndValidate(UUID hostId, String name, String yaml, String envText, DockerEndpoint endpoint,
                                  Path composeFile) {
        files.write(hostId, name, yaml, envText);
        ValidationResult result;
        try {
            result = validation.validate(hostId, name, endpoint, composeFile);
        } catch (ComposeExecutionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "COMPOSE_UNAVAILABLE", "docker compose could not run");
        }
        if (!result.valid()) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE", "The compose file is invalid",
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
            return PathValidationUtil.validateDeploymentName(name);
        } catch (PathValidationException e) {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_DEPLOYMENT_NAME",
                    "The name must be 1-63 characters: lowercase letters, digits, - and _, starting with a letter or digit");
        }
    }

    DockerEndpoint endpointOf(DockerHost host) {
        try {
            return endpoints.endpointOf(host);
        } catch (EncryptionKeyMissingException e) {
            throw keyMissing();
        } catch (SecretDecryptionException e) {
            throw new DeploymentException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        }
    }

    DockerHost requireHost(UUID hostId) {
        return hosts.findById(hostId).orElseThrow(
                () -> new DeploymentException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
    }

    Deployment requireDeployment(UUID hostId, UUID id) {
        return deployments.findByIdAndHostId(id, hostId).orElseThrow(
                () -> new DeploymentException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "Deployment not found"));
    }

    DeploymentVersion currentVersion(Deployment deployment) {
        return versions.findById(deployment.getCurrentVersionId()).orElseThrow(
                () -> new DeploymentException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "Deployment not found"));
    }

    static DeploymentException keyMissing() {
        return new DeploymentException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                "Environment variables need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to be stored");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw missing(field);
        }
    }

    private static DeploymentException missing(String field) {
        return new DeploymentException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed",
                List.of(field + ": must not be blank"));
    }

    private static DeploymentDetailResponse toDetail(Deployment s, String yaml, List<String> envKeys) {
        GitInfo gitInfo = s.getSourceType() == DeploymentSource.GIT
                ? new GitInfo(s.getGitRepoUrl(), s.getGitRef(), s.getGitComposePath(), s.getGitCredentialId(),
                        s.getLastCommitSha(), s.isAutoUpdateEnabled(), s.getAutoUpdateIntervalSeconds(),
                        s.getLastPolledAt(), s.getLastPollError())
                : null;
        return new DeploymentDetailResponse(s.getId(), s.getName(), s.getStatus(), s.getSourceType(),
                s.getLastDeployedAt(), s.getCreatedAt(), yaml, envKeys, gitInfo);
    }
}
