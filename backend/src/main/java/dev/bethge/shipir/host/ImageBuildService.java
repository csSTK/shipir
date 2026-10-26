package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.BuildSpec;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.git.GitOperationException;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.host.dto.ImageBuildRequest;
import dev.bethge.shipir.host.dto.ImageRunResponse;
import dev.bethge.shipir.host.dto.PushImageRequest;
import dev.bethge.shipir.host.dto.TagImageRequest;
import dev.bethge.shipir.permission.CurrentAccess;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Image build, tag and push on a host.
 *
 * <p><b>Roles.</b> A Dockerfile build executes arbitrary {@code RUN} steps on the daemon, which is root-equivalent on the
 * host, so building is ADMIN only (SecurityConfig). Tag and push are ADMIN/OPERATOR like the neighbouring image pull:
 * they only rename or upload an image that already exists on the host. On top of the role the host-level OPERATE check
 * of every other write applies.
 *
 * <p><b>Secrets.</b> Build arguments end up in the image history, so they must not hold secrets (documented for the UI);
 * their values are never logged, streamed or audited. Registry logins are sent to the daemon for one call only and are
 * not part of any message, log line or audit row. The daemon's own error text is never forwarded, only a code.
 *
 * <p><b>Resources.</b> One build per host at a time (409 BUILD_IN_PROGRESS), the context is capped while it is copied
 * ({@code shipir.images.max-build-context}), the build has a timeout, and every temporary file is deleted in a
 * {@code finally}.
 */
@Service
@EnableConfigurationProperties(ImageBuildProperties.class)
class ImageBuildService {

    private static final Logger log = LoggerFactory.getLogger(ImageBuildService.class);

    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern DOCKERFILE_PATH = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}");
    private static final Pattern BUILD_ARG_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern GIT_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final int MAX_BUILD_ARGS = 50;
    private static final int MAX_BUILD_ARG_VALUE = 4096;
    /** Shown lines per run and characters per line; a build can print megabytes. */
    static final int MAX_OUTPUT_LINES = 5000;
    static final int MAX_LINE_LENGTH = 500;
    private static final String DEFAULT_DOCKERFILE = "Dockerfile";
    /** Prefix of the per-build spool directory; {@link BuildTempSweeper} removes leftovers with this prefix. */
    static final String SPOOL_PREFIX = "shipir-build-";

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ImageService images;
    private final CurrentAccess access;
    private final ComposeOutputStreamService streams;
    private final RegistryAuthSource registryAuths;
    private final GitRepositoryService git;
    private final ImageBuildProperties properties;
    private final Set<UUID> buildingHosts = ConcurrentHashMap.newKeySet();

    ImageBuildService(DockerHostRepository hosts, HostEndpointResolver endpoints, ImageService images, CurrentAccess access,
                      ComposeOutputStreamService streams, RegistryAuthSource registryAuths, GitRepositoryService git,
                      ImageBuildProperties properties) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.images = images;
        this.access = access;
        this.streams = streams;
        this.registryAuths = registryAuths;
        this.git = git;
        this.properties = properties;
    }

    /**
     * Starts a build from an uploaded context or a Git checkout. Everything that can be checked without the daemon or the
     * network is checked before the call returns; the build runs on a virtual thread and streams to
     * {@code /topic/deployments/{runId}} (host-level run).
     */
    @Auditable(action = "IMAGE_BUILD", targetType = "IMAGE", targetId = "#request.auditTarget()",
            details = "{'source': #request.source()}")
    ImageRunResponse build(UUID hostId, ImageBuildRequest request) {
        requireOperate(hostId);
        String tag = validateBuildTag(request.tag());
        String dockerfile = validateDockerfile(request.dockerfile());
        Map<String, String> buildArgs = parseBuildArgs(request.buildArgs());
        boolean upload = request.context() != null;
        boolean fromGit = request.gitUrl() != null && !request.gitUrl().isBlank();
        if (upload == fromGit) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_BUILD_SOURCE",
                    "Provide either a build context upload or a Git repository");
        }
        String gitUrl = null;
        String gitRef = null;
        if (fromGit) {
            gitUrl = request.gitUrl().trim();
            gitRef = validateGitRef(request.gitRef());
            checkGit(gitUrl, request.gitCredentialId());
        }
        long maxBytes = properties.maxBuildContext().toBytes();
        if (upload && request.context().getSize() > maxBytes) {
            throw sizeLimit();
        }
        DockerEndpoint endpoint = call(hostId, (id, ep) -> ep);
        List<RegistryLogin> logins = ImageReferenceRules.allLogins(registryAuths);

        if (!buildingHosts.add(hostId)) {
            throw new HostException(HttpStatus.CONFLICT, "BUILD_IN_PROGRESS", "A build is already running on this host");
        }
        Path work = null;
        boolean handedOver = false;
        try {
            work = Files.createTempDirectory(tempRoot(), SPOOL_PREFIX);
            if (upload) {
                spool(request.context(), work.resolve("context.tar"), maxBytes);
                validateArchive(work.resolve("context.tar"), maxBytes);
            }
            BuildSpec spec = new BuildSpec(dockerfile, tag, buildArgs, request.noCache(), maxBytes,
                    properties.buildTimeout());
            String runId = UUID.randomUUID().toString();
            streams.registerRun(runId, hostId, null);
            Consumer<String> sink = streams.start(runId);
            Path workDir = work;
            String url = gitUrl;
            String ref = gitRef;
            UUID credential = request.gitCredentialId();
            Thread.ofVirtual().name("image-build-" + runId).start(() ->
                    runBuild(hostId, endpoint, workDir, spec, logins, url, ref, credential, runId, sink));
            handedOver = true; // the worker deletes the directory and frees the host slot
            return new ImageRunResponse(runId);
        } catch (IOException e) {
            throw new HostException(HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "The build context could not be read");
        } finally {
            if (!handedOver) {
                if (work != null) {
                    deleteRecursively(work);
                }
                buildingHosts.remove(hostId);
            }
        }
    }

    private void runBuild(UUID hostId, DockerEndpoint endpoint, Path work, BuildSpec spec, List<RegistryLogin> logins,
                          String gitUrl, String gitRef, UUID credential, String runId, Consumer<String> sink) {
        RunStatus status = RunStatus.FAILED;
        Consumer<String> output = new BoundedSink(sink);
        try {
            sink.accept("Building " + spec.tag());
            Path context = work.resolve("context.tar");
            if (gitUrl != null) {
                Path repo = work.resolve("repo");
                sink.accept("Cloning repository");
                git.sync(repo, gitUrl, gitRef, spec.dockerfile(), credential);
                BuildContextArchiver.archive(repo, context, spec.maxContextBytes());
                deleteRecursively(repo);
            }
            images.build(hostId, endpoint, () -> open(context), spec, logins, output);
            sink.accept("Built " + spec.tag());
            status = RunStatus.DONE;
        } catch (DockerConnectionException e) {
            sink.accept("Build failed (" + e.getCode() + ")");
        } catch (GitOperationException e) {
            sink.accept("Build failed (" + gitCodeForBuild(e.code()) + ")");
        } catch (IOException | RuntimeException e) {
            log.warn("Image build {} failed unexpectedly: {}", runId, e.getClass().getSimpleName());
            sink.accept("Build failed (UNKNOWN)");
        } finally {
            deleteRecursively(work);
            buildingHosts.remove(hostId);
            streams.complete(runId, status);
        }
    }

    private static String gitCodeForBuild(String code) {
        return switch (code) {
            case "COMPOSE_FILE_NOT_FOUND" -> "DOCKERFILE_NOT_FOUND";
            case "INVALID_COMPOSE_PATH" -> "INVALID_DOCKERFILE_PATH";
            case "COMPOSE_FILE_TOO_LARGE" -> "DOCKERFILE_TOO_LARGE";
            default -> code;
        };
    }

    private static InputStream open(Path file) {
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds {@code repository:tag} to an image. Audited with the new reference; ADMIN/OPERATOR plus host OPERATE. */
    @Auditable(action = "IMAGE_TAG", targetType = "IMAGE", targetId = "#request.auditTarget()",
            details = "{'imageId': #imageId}")
    void tag(UUID hostId, String imageId, TagImageRequest request) {
        requireOperate(hostId);
        if (imageId == null || !RESOURCE_ID.matcher(imageId).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
        if (request == null || !ImageReferenceRules.isValidRepositoryAndTag(request.repository(), request.tag())) {
            throw invalidReference();
        }
        call(hostId, (id, endpoint) -> {
            images.tag(id, endpoint, imageId, request.repository(), request.tag());
            return null;
        });
    }

    /**
     * Pushes {@code repository[:tag]} (tag defaults to {@code latest}; digests cannot be pushed). The stored registry login
     * whose host matches the reference is used for this push only; progress is streamed like a pull.
     */
    @Auditable(action = "IMAGE_PUSH", targetType = "IMAGE", targetId = "#request.auditTarget()")
    ImageRunResponse push(UUID hostId, PushImageRequest request) {
        requireOperate(hostId);
        String reference = request == null || request.reference() == null ? "" : request.reference().trim();
        if (!ImageReferenceRules.isValidReference(reference) || reference.contains("@")) {
            throw invalidReference();
        }
        if (reference.lastIndexOf(':') <= reference.lastIndexOf('/')) {
            reference = reference + ":latest";
        }
        String pushed = reference;
        DockerEndpoint endpoint = call(hostId, (id, ep) -> ep);
        RegistryLogin login = ImageReferenceRules.loginFor(registryAuths, pushed);
        String runId = UUID.randomUUID().toString();
        streams.registerRun(runId, hostId, null);
        Consumer<String> sink = streams.start(runId);
        Thread.ofVirtual().name("image-push-" + runId).start(() -> runPush(hostId, endpoint, pushed, login, runId, sink));
        return new ImageRunResponse(runId);
    }

    private void runPush(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin login, String runId,
                         Consumer<String> sink) {
        RunStatus status = RunStatus.FAILED;
        try {
            sink.accept("Pushing " + reference);
            images.push(hostId, endpoint, reference, login, new BoundedSink(sink));
            sink.accept("Pushed " + reference);
            status = RunStatus.DONE;
        } catch (DockerConnectionException e) {
            sink.accept("Push failed (" + e.getCode() + ")");
        } catch (RuntimeException e) {
            log.warn("Image push {} failed unexpectedly: {}", runId, e.getClass().getSimpleName());
            sink.accept("Push failed (UNKNOWN)");
        } finally {
            streams.complete(runId, status);
        }
    }

    // ---- validation ----

    private static String validateBuildTag(String tag) {
        String t = tag == null ? "" : tag.trim();
        if (!ImageReferenceRules.isValidReference(t) || t.contains("@")) {
            throw invalidReference();
        }
        return t;
    }

    /** Relative path inside the context: no {@code ..}, no absolute path, no empty or {@code .} segments. */
    static String validateDockerfile(String path) {
        String p = path == null || path.isEmpty() ? DEFAULT_DOCKERFILE : path;
        boolean ok = DOCKERFILE_PATH.matcher(p).matches() && !p.endsWith("/");
        if (ok) {
            for (String segment : p.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_DOCKERFILE_PATH",
                    "The Dockerfile path must be a relative path inside the build context");
        }
        return p;
    }

    /** {@code KEY=VALUE} strings; key identifier-like, value bounded and free of NUL. Errors never echo a value. */
    static Map<String, String> parseBuildArgs(List<String> raw) {
        Map<String, String> args = new LinkedHashMap<>();
        if (raw == null) {
            return args;
        }
        if (raw.size() > MAX_BUILD_ARGS) {
            throw invalidBuildArg();
        }
        for (String entry : raw) {
            int eq = entry == null ? -1 : entry.indexOf('=');
            if (eq <= 0) {
                throw invalidBuildArg();
            }
            String key = entry.substring(0, eq);
            String value = entry.substring(eq + 1);
            if (!BUILD_ARG_KEY.matcher(key).matches() || value.length() > MAX_BUILD_ARG_VALUE || value.indexOf('\0') >= 0
                    || args.put(key, value) != null) {
                throw invalidBuildArg();
            }
        }
        return args;
    }

    /** Branch, tag or commit name as the Git service looks it up: no leading dash, no spaces, no {@code ..}. */
    static String validateGitRef(String ref) {
        String r = ref == null ? "" : ref;
        if (!GIT_REF.matcher(r).matches() || r.contains("..") || r.contains("//") || r.endsWith("/") || r.endsWith(".lock")
                || r.contains("/.")) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_GIT_REF", "Invalid Git branch, tag or commit");
        }
        return r;
    }

    private void checkGit(String url, UUID credentialId) {
        try {
            git.validateUrl(url);
            git.requireCredentialForUrl(credentialId, url);
        } catch (GitOperationException e) {
            throw new HostException(e.status(), e.code(), e.getMessage());
        }
    }

    private void validateArchive(Path file, long maxBytes) {
        try (InputStream in = Files.newInputStream(file)) {
            images.validateBuildContext(in, maxBytes);
        } catch (DockerConnectionException e) {
            throw switch (e.getCode()) {
                case INVALID_ARCHIVE -> new HostException(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE",
                        "The build context is not a safe tar or tar.gz archive");
                case SIZE_LIMIT_EXCEEDED -> sizeLimit();
                default -> new HostException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "Environment could not be reached");
            };
        } catch (IOException e) {
            throw new HostException(HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "The build context could not be read");
        }
    }

    /** Copies the upload to {@code target}, counting while streaming so a lying size header cannot bypass the cap. */
    static void spool(MultipartFile file, Path target, long maxBytes) throws IOException {
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                total += n;
                if (total > maxBytes) {
                    throw sizeLimit();
                }
                out.write(buffer, 0, n);
            }
        }
    }

    private Path tempRoot() throws IOException {
        String configured = properties.buildTempDir();
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
        return Files.createDirectories(Path.of(configured));
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete temporary build file: {}", e.getClass().getSimpleName());
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not clean temporary build directory: {}", e.getClass().getSimpleName());
        }
    }

    private static HostException invalidReference() {
        return new HostException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_REFERENCE", "Invalid image reference");
    }

    private static HostException invalidBuildArg() {
        return new HostException(HttpStatus.BAD_REQUEST, "INVALID_BUILD_ARG", "Invalid build argument");
    }

    private static HostException sizeLimit() {
        return new HostException(HttpStatus.PAYLOAD_TOO_LARGE, "SIZE_LIMIT_EXCEEDED",
                "The build context exceeds the size limit");
    }

    // ---- access and docker call ----

    private void requireOperate(UUID hostId) {
        if (!access.canView(hostId)) {
            throw new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
        if (!access.canOperate(hostId)) {
            throw new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }

    private <T> T call(UUID hostId, java.util.function.BiFunction<UUID, DockerEndpoint, T> query) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return query.apply(host.getId(), endpoints.endpointOf(host));
        } catch (DockerConnectionException e) {
            if (e.getCode() == dev.bethge.shipir.docker.DockerErrorCode.NOT_FOUND) {
                throw new HostException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Image not found");
            }
            throw new HostException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "Environment could not be reached");
        } catch (EncryptionKeyMissingException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        }
    }

    /** Daemon output with bounded line length, no control characters and a cap on the number of lines. */
    private static final class BoundedSink implements Consumer<String> {
        private final Consumer<String> delegate;
        private int lines;

        BoundedSink(Consumer<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(String line) {
            if (lines > MAX_OUTPUT_LINES) {
                return;
            }
            if (lines == MAX_OUTPUT_LINES) {
                lines++;
                delegate.accept("... output truncated (line limit reached)");
                return;
            }
            lines++;
            String clean = line.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
            delegate.accept(clean.length() > MAX_LINE_LENGTH ? clean.substring(0, MAX_LINE_LENGTH) + "..." : clean);
        }
    }
}
