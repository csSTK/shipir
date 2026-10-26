package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.AuthConfigurations;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.PushResponseItem;
import dev.bethge.shipir.docker.dto.BuildSpec;
import com.github.dockerjava.api.model.PullResponseItem;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.common.util.OutputSanitizer;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class ImageServiceImpl implements ImageService {

    private static final String NONE_TAG = "<none>:<none>";

    /** A pull streams for as long as the layers download; the read timeout does not apply, this is the overall cap. */
    static final Duration PULL_TIMEOUT = Duration.ofMinutes(30);

    private final DockerCallSupport calls;

    public ImageServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public List<ImageDto> list(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, client -> {
            Set<String> usedIds = client.listContainersCmd().withShowAll(true).exec().stream()
                    .map(Container::getImageId)
                    .collect(Collectors.toSet());
            return client.listImagesCmd().exec().stream()
                    .map(i -> toDto(i, usedIds))
                    .toList();
        });
    }

    private static ImageDto toDto(Image i, Set<String> usedIds) {
        Long created = i.getCreated();
        return new ImageDto(i.getId(), tags(i.getRepoTags()), i.getSize(),
                created == null ? null : Instant.ofEpochSecond(created), usedIds.contains(i.getId()),
                i.getRepoDigests() == null ? List.of() : List.of(i.getRepoDigests()), false);
    }

    /** Dangling images come with null, empty or a {@code <none>:<none>} tag; all mean "no tags". */
    private static List<String> tags(String[] repoTags) {
        if (repoTags == null) return List.of();
        return Arrays.stream(repoTags).filter(t -> t != null && !NONE_TAG.equals(t)).toList();
    }

    @Override
    public void removeImage(UUID hostId, DockerEndpoint endpoint, String id) {
        calls.call(hostId, endpoint, client -> {
            client.removeImageCmd(id).withForce(false).exec();
            return null;
        });
    }

    @Override
    public List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint, boolean includeTagged) {
        return list(hostId, endpoint).stream()
                .filter(i -> !i.inUse() && (includeTagged || i.repoTags().isEmpty()))
                .map(i -> new PruneCandidateDto(i.id(), i.repoTags().isEmpty() ? null : i.repoTags().get(0), i.size(), null))
                .toList();
    }

    @Override
    public void pull(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin auth, Consumer<String> onProgress) {
        try {
            var client = calls.manager().client(hostId, endpoint);
            var cmd = client.pullImageCmd(reference.contains("@") ? reference : repositoryOf(reference));
            if (!reference.contains("@")) {
                cmd.withTag(tagOf(reference));
            }
            if (auth != null) {
                cmd.withAuthConfig(new AuthConfig().withRegistryAddress(auth.registryHost())
                        .withUsername(auth.username()).withPassword(auth.secret()));
            }
            Progress callback = new Progress(onProgress);
            try (callback) {
                cmd.exec(callback);
                if (!callback.awaitCompletion(PULL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image pull took too long.", null);
                }
            }
            if (callback.failure != null) {
                throw new DockerConnectionException(callback.failure, "The image could not be pulled.", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image pull was interrupted.", e);
        } catch (Exception e) {
            throw calls.translate(e);
        }
    }

    private static String repositoryOf(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(0, colon) : reference;
    }

    private static String tagOf(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(colon + 1) : "latest";
    }

    /** Turns pull items into one line per (layer, status) change; the daemon's error text is never forwarded. */
    private static final class Progress extends ResultCallback.Adapter<PullResponseItem> {
        private final Consumer<String> onProgress;
        private final java.util.Map<String, String> lastStatus = new java.util.HashMap<>();
        volatile DockerErrorCode failure;

        Progress(Consumer<String> onProgress) {
            this.onProgress = onProgress;
        }

        @Override
        public void onNext(PullResponseItem item) {
            if (item.getErrorDetail() != null || item.getError() != null) {
                String text = String.valueOf(item.getError()).toLowerCase(java.util.Locale.ROOT);
                failure = text.contains("not found") || text.contains("manifest unknown")
                        ? DockerErrorCode.NOT_FOUND : DockerErrorCode.PULL_FAILED;
                return;
            }
            String status = item.getStatus();
            if (status == null) {
                return;
            }
            String id = item.getId() == null ? "" : item.getId();
            if (status.equals(lastStatus.put(id, status))) {
                return;
            }
            onProgress.accept(id.isEmpty() ? status : id + ": " + status);
        }
    }

    @Override
    public void validateBuildContext(java.io.InputStream tar, long maxBytes) {
        try (java.io.BufferedInputStream in = new java.io.BufferedInputStream(tar)) {
            in.mark(2);
            int first = in.read();
            int second = in.read();
            in.reset();
            boolean gzip = first == 0x1f && second == 0x8b;
            try (java.io.InputStream content = gzip ? new java.util.zip.GZIPInputStream(in) : in) {
                // the limit counts the expanded bytes, so a small gzip cannot hide a huge context
                VolumeArchiveValidator.validate(content, maxBytes);
            }
        } catch (java.io.IOException e) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "The build context is not a valid tar archive.", e);
        }
    }

    @Override
    public void build(UUID hostId, DockerEndpoint endpoint, Supplier<java.io.InputStream> context, BuildSpec spec,
                      List<RegistryLogin> auths, Consumer<String> onOutput) {
        // always checked here, so no caller can skip it; the context is opened a second time to send it
        try (java.io.InputStream in = context.get()) {
            validateBuildContext(in, spec.maxContextBytes());
        } catch (java.io.IOException e) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "The build context could not be read.", e);
        }
        try (java.io.InputStream tar = context.get()) {
            var client = calls.manager().client(hostId, endpoint);
            var cmd = client.buildImageCmd()
                    .withTarInputStream(tar)
                    .withDockerfilePath(spec.dockerfile())
                    .withTags(Set.of(spec.tag()))
                    .withNoCache(spec.noCache())
                    .withRemove(true)
                    .withForcerm(true);
            spec.buildArgs().forEach(cmd::withBuildArg);
            if (auths != null && !auths.isEmpty()) {
                AuthConfigurations configs = new AuthConfigurations();
                for (RegistryLogin login : auths) {
                    configs.addConfig(new AuthConfig().withRegistryAddress(login.registryHost())
                            .withUsername(login.username()).withPassword(login.secret()));
                }
                cmd.withBuildAuthConfigs(configs);
            }
            BuildOutput callback = new BuildOutput(onOutput);
            try (callback) {
                cmd.exec(callback);
                if (!callback.awaitCompletion(spec.timeout().toSeconds(), TimeUnit.SECONDS)) {
                    throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image build took too long.", null);
                }
            }
            if (callback.failed) {
                throw new DockerConnectionException(DockerErrorCode.BUILD_FAILED, "The image could not be built.", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image build was interrupted.", e);
        } catch (Exception e) {
            throw calls.translate(e);
        }
    }

    @Override
    public void tag(UUID hostId, DockerEndpoint endpoint, String imageId, String repository, String tag) {
        calls.call(hostId, endpoint, client -> {
            client.tagImageCmd(imageId, repository, tag).exec();
            return null;
        });
    }

    @Override
    public void push(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin auth, Consumer<String> onProgress) {
        try {
            var client = calls.manager().client(hostId, endpoint);
            int colon = reference.lastIndexOf(':');
            boolean tagged = colon > reference.lastIndexOf('/');
            var cmd = client.pushImageCmd(tagged ? reference.substring(0, colon) : reference)
                    .withTag(tagged ? reference.substring(colon + 1) : "latest");
            if (auth != null) {
                cmd.withAuthConfig(new AuthConfig().withRegistryAddress(auth.registryHost())
                        .withUsername(auth.username()).withPassword(auth.secret()));
            }
            PushProgress callback = new PushProgress(onProgress);
            try (callback) {
                cmd.exec(callback);
                if (!callback.awaitCompletion(PULL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image push took too long.", null);
                }
            }
            if (callback.failed) {
                throw new DockerConnectionException(DockerErrorCode.PUSH_FAILED, "The image could not be pushed.", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The image push was interrupted.", e);
        } catch (Exception e) {
            throw calls.translate(e);
        }
    }

    /**
     * Turns build items into readable lines: the build log ({@code stream}, split per line, control characters removed) and
     * one status line per (layer, status) change while base images are pulled. The daemon's error text is never forwarded;
     * a failing step has already printed its own output as stream lines.
     */
    static final class BuildOutput extends ResultCallback.Adapter<BuildResponseItem> {
        private final Consumer<String> onOutput;
        private final java.util.Map<String, String> lastStatus = new java.util.HashMap<>();
        volatile boolean failed;

        BuildOutput(Consumer<String> onOutput) {
            this.onOutput = onOutput;
        }

        @Override
        public void onNext(BuildResponseItem item) {
            if (item.isErrorIndicated()) {
                failed = true;
                return;
            }
            String stream = item.getStream();
            if (stream != null) {
                for (String line : stream.split("\\R")) {
                    String clean = OutputSanitizer.neutralize(line).stripTrailing();
                    if (!clean.isBlank()) {
                        onOutput.accept(clean);
                    }
                }
                return;
            }
            String status = item.getStatus();
            if (status != null) {
                String id = item.getId() == null ? "" : item.getId();
                if (!status.equals(lastStatus.put(id, status))) {
                    onOutput.accept(OutputSanitizer.neutralize(id.isEmpty() ? status : id + ": " + status));
                }
            }
        }
    }

    /** Push items as one line per (layer, status) change; any error item fails the push, its text is not forwarded. */
    static final class PushProgress extends ResultCallback.Adapter<PushResponseItem> {
        private final Consumer<String> onProgress;
        private final java.util.Map<String, String> lastStatus = new java.util.HashMap<>();
        volatile boolean failed;

        PushProgress(Consumer<String> onProgress) {
            this.onProgress = onProgress;
        }

        @Override
        public void onNext(PushResponseItem item) {
            if (item.getErrorDetail() != null || item.getError() != null) {
                failed = true;
                return;
            }
            String status = item.getStatus();
            if (status == null) {
                return;
            }
            String id = item.getId() == null ? "" : item.getId();
            if (!status.equals(lastStatus.put(id, status))) {
                onProgress.accept(OutputSanitizer.neutralize(id.isEmpty() ? status : id + ": " + status));
            }
        }
    }
}
