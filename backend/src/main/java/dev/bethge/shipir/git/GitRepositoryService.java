package dev.bethge.shipir.git;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shallow clone/fetch of one ref through JGit (no git binary, no shell) into a stack's checkout directory, and reading
 * of the compose file from it. The token is decrypted only for the call and handed to a {@link CredentialsProvider};
 * it never ends up in a URL, log line, exception message or the on-disk git config. JGit messages are not forwarded.
 */
@Service
public class GitRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryService.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final String TARGET_REF = "refs/shipir/target";
    private static final long MAX_COMPOSE_BYTES = 500_000;

    private final GitCredentialRepository credentials;
    private final SecretCipher cipher;
    private final GitRemoteChecker checker;
    private final GitProperties properties;

    GitRepositoryService(GitCredentialRepository credentials, SecretCipher cipher, GitRemoteChecker checker,
                         GitProperties properties) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.checker = checker;
        this.properties = properties;
    }

    /** Same rules as the connection test: absolute https URL (http only with the test flag), no userinfo. */
    public void validateUrl(String repoUrl) {
        try {
            checker.validateUrl(repoUrl);
        } catch (GitException e) {
            throw new GitOperationException(e.status(), e.code(), e.getMessage());
        }
    }

    /** True if a credential with this id exists. */
    public boolean credentialExists(UUID credentialId) {
        return credentials.existsById(credentialId);
    }

    /**
     * Fetches {@code ref} (branch, tag or full commit SHA) with depth 1 into {@code repoDir}, checks out that commit
     * and returns it together with the compose file at {@code composePath}. Not transactional: it does network I/O.
     *
     * @param credentialId optional; null for public repositories
     */
    public GitCheckout sync(Path repoDir, String repoUrl, String ref, String composePath, UUID credentialId) {
        validateUrl(repoUrl);
        CredentialsProvider provider = provider(credentialId);
        prepare(repoDir);
        // reject a traversing/absolute path before any network traffic (the final check follows the checkout)
        composeFile(repoDir, composePath, false);

        String commit;
        try (Git git = Git.open(repoDir.toFile())) {
            commit = fetch(git, repoUrl.trim(), ref.trim(), provider);
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit).call();
        } catch (GitAPIException | IOException e) {
            log.warn("Git checkout failed: {}", e.getClass().getSimpleName());
            throw new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "The repository could not be read");
        }
        Path file = composeFile(repoDir, composePath, true);
        return new GitCheckout(commit, read(file));
    }

    /**
     * Makes the checkout match {@code commitSha} (deploy and rollback run the files of a recorded version). If HEAD is
     * already that commit the working tree is only reset locally, without network access; otherwise the commit is
     * fetched like a SHA ref by {@link #sync}.
     */
    public GitCheckout ensureCommit(Path repoDir, String repoUrl, String commitSha, String composePath,
                                    UUID credentialId) {
        if (commitSha != null && SHA.matcher(commitSha).matches() && Files.exists(repoDir.resolve(".git"))) {
            composeFile(repoDir, composePath, false);
            try (Git git = Git.open(repoDir.toFile())) {
                ObjectId head = git.getRepository().resolve("HEAD^{commit}");
                if (head != null && head.name().equalsIgnoreCase(commitSha)) {
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head.name()).call();
                    return new GitCheckout(head.name(), read(composeFile(repoDir, composePath, true)));
                }
            } catch (GitAPIException | IOException e) {
                log.warn("Local git check failed: {}", e.getClass().getSimpleName());
            }
        }
        return sync(repoDir, repoUrl, commitSha, composePath, credentialId);
    }

    /**
     * The commit {@code ref} points to on the remote right now, via {@code ls-remote} only (nothing is downloaded).
     * Annotated tags are peeled to their commit, so the value equals what {@link #sync} would check out. A full commit
     * SHA is returned as is.
     */
    public String remoteCommit(String repoUrl, String ref, UUID credentialId) {
        validateUrl(repoUrl);
        CredentialsProvider provider = provider(credentialId);
        if (SHA.matcher(ref.trim()).matches()) {
            return ref.trim().toLowerCase();
        }
        Ref found = lookupRemoteRef(repoUrl.trim(), ref.trim(), provider);
        ObjectId peeled = found.getPeeledObjectId();
        return (peeled != null ? peeled : found.getObjectId()).name();
    }

    private String fetch(Git git, String url, String ref, CredentialsProvider provider)
            throws GitAPIException, IOException {
        String source = resolveRemoteRef(url, ref, provider);
        try {
            git.fetch().setRemote(url)
                    .setRefSpecs(new RefSpec("+" + source + ":" + TARGET_REF))
                    .setDepth(1)
                    .setTagOpt(TagOpt.NO_TAGS)
                    .setTimeout(timeoutSeconds())
                    .setCredentialsProvider(provider)
                    .call();
        } catch (TransportException e) {
            throw map(e, SHA.matcher(ref).matches());
        }
        ObjectId id = git.getRepository().resolve(TARGET_REF + "^{commit}");
        if (id == null) {
            throw refNotFound();
        }
        return id.name();
    }

    /** Finds the full remote ref name for a branch or tag name; a commit SHA is fetched as is. */
    private String resolveRemoteRef(String url, String ref, CredentialsProvider provider) {
        if (SHA.matcher(ref).matches()) {
            return ref.toLowerCase();
        }
        return lookupRemoteRef(url, ref, provider).getName();
    }

    private Ref lookupRemoteRef(String url, String ref, CredentialsProvider provider) {
        Collection<Ref> remote;
        try {
            remote = Git.lsRemoteRepository().setRemote(url).setHeads(true).setTags(true)
                    .setTimeout(timeoutSeconds()).setCredentialsProvider(provider).call();
        } catch (TransportException e) {
            throw map(e, false);
        } catch (GitAPIException e) {
            log.warn("Git ls-remote failed: {}", e.getClass().getSimpleName());
            throw new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "The repository could not be read");
        }
        for (String candidate : new String[]{ref, Constants.R_HEADS + ref, Constants.R_TAGS + ref}) {
            if (candidate.startsWith("refs/")) {
                for (Ref r : remote) {
                    if (r.getName().equals(candidate)) {
                        return r;
                    }
                }
            }
        }
        throw refNotFound();
    }

    private static GitOperationException map(TransportException e, boolean byCommit) {
        String outcome = GitRemoteChecker.classify(e);
        log.warn("Git transport failed: {}", outcome);
        return switch (outcome) {
            case GitRemoteChecker.AUTH_FAILED -> new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_AUTH_FAILED",
                    "The repository rejected the credential or requires one");
            case GitRemoteChecker.NOT_FOUND -> new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_REPO_NOT_FOUND",
                    "The repository was not found");
            default -> byCommit ? refNotFound()
                    : new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "The repository could not be reached");
        };
    }

    private static GitOperationException refNotFound() {
        return new GitOperationException(HttpStatus.BAD_REQUEST, "GIT_REF_NOT_FOUND",
                "The branch, tag or commit was not found in the repository");
    }

    private CredentialsProvider provider(UUID credentialId) {
        if (credentialId == null) {
            return null;
        }
        GitCredential c = credentials.findById(credentialId).orElseThrow(() -> new GitOperationException(
                HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Git credential not found"));
        try {
            return new UsernamePasswordCredentialsProvider(c.getUsername(), cipher.decrypt(c.getTokenCiphertext()));
        } catch (EncryptionKeyMissingException e) {
            throw new GitOperationException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "Git credentials need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY)");
        } catch (SecretDecryptionException e) {
            throw new GitOperationException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "The stored Git credential could not be decrypted");
        }
    }

    private static void prepare(Path repoDir) {
        try {
            if (!Files.exists(repoDir.resolve(".git"))) {
                Files.createDirectories(repoDir, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                Git.init().setDirectory(repoDir.toFile()).call().close();
            }
        } catch (IOException | GitAPIException e) {
            throw new java.io.UncheckedIOException(new IOException("Could not prepare checkout directory"));
        }
    }

    /** Validated location of the compose file; with {@code mustExist} also a regular file inside the real checkout. */
    private static Path composeFile(Path repoDir, String composePath, boolean mustExist) {
        Path file;
        try {
            file = PathValidationUtil.resolve(repoDir, composePath);
        } catch (PathValidationException e) {
            throw new GitOperationException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE_PATH",
                    "The compose file path must be a relative path inside the repository");
        }
        if (mustExist && !Files.isRegularFile(file)) {
            throw new GitOperationException(HttpStatus.BAD_REQUEST, "COMPOSE_FILE_NOT_FOUND",
                    "The compose file does not exist in the repository at this ref");
        }
        return file;
    }

    private static String read(Path file) {
        try {
            if (Files.size(file) > MAX_COMPOSE_BYTES) {
                throw new GitOperationException(HttpStatus.BAD_REQUEST, "COMPOSE_FILE_TOO_LARGE",
                        "The compose file is larger than 500 KB");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitOperationException(HttpStatus.BAD_REQUEST, "COMPOSE_FILE_NOT_FOUND",
                    "The compose file could not be read");
        }
    }

    private int timeoutSeconds() {
        return (int) Math.max(1, properties.connectTimeout().toSeconds());
    }
}
