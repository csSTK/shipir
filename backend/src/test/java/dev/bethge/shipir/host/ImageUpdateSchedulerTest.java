package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stubbed docker and registry ports and a fixed clock: no network, no Spring context. */
class ImageUpdateSchedulerTest {

    private static final String OLD = "sha256:" + "1".repeat(64);
    private static final String NEW = "sha256:" + "2".repeat(64);
    private static final String OTHER = "sha256:" + "3".repeat(64);

    private final Instant now = Instant.parse("2026-01-01T10:00:00Z");
    private final DockerHostRepository repository = mock(DockerHostRepository.class);
    private final HostEndpointResolver resolver = mock(HostEndpointResolver.class);
    private final ImageService images = mock(ImageService.class);
    private final ImageUpdateStore store = new ImageUpdateStore();
    private final List<String> asked = new ArrayList<>();

    private DockerHost host() {
        DockerHost h = mock(DockerHost.class);
        when(h.getId()).thenReturn(UUID.randomUUID());
        when(repository.findAll()).thenReturn(List.of(h));
        when(resolver.endpointOf(h)).thenReturn(new DockerEndpoint.Local("/sock"));
        return h;
    }

    private ImageUpdateScheduler scheduler(RemoteDigestLookup remote) {
        return new ImageUpdateScheduler(repository, resolver, images, remote, store,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static ImageDto image(String tag, String digest) {
        return new ImageDto("sha256:id", List.of(tag), 1L, null, true,
                digest == null ? List.of() : List.of(tag.replaceAll(":[^:/]*$", "") + "@" + digest), false);
    }

    @Test
    void flagsOnlyImagesWhoseRemoteDigestDiffers() {
        DockerHost h = host();
        when(images.list(eq(h.getId()), any())).thenReturn(List.of(
                image("nginx:latest", OLD), image("redis:7", OLD), image("local/built:1", null)));
        scheduler(ref -> {
            asked.add(ref.key());
            return Optional.of(ref.repository().equals("library/nginx") ? NEW : OLD);
        }).check();

        assertThat(store.hasUpdate(h.getId(), "docker.io/library/nginx:latest")).isTrue();
        assertThat(store.hasUpdate(h.getId(), "docker.io/library/redis:7")).isFalse();
        assertThat(asked).doesNotContain("docker.io/local/built:1");
        assertThat(store.checkedAt(h.getId())).isEqualTo(now);
    }

    @Test
    void localDigestOfAnotherRepositoryDoesNotCount() {
        DockerHost h = host();
        ImageDto img = new ImageDto("i", List.of("nginx:latest"), 1L, null, true,
                List.of("other/repo@" + OTHER), false);
        when(images.list(eq(h.getId()), any())).thenReturn(List.of(img));
        scheduler(ref -> Optional.of(NEW)).check();
        assertThat(store.isEmpty(h.getId())).isTrue();
    }

    @Test
    void failingImageLookupIsIsolatedAndUnknownIsNotAnUpdate() {
        DockerHost h = host();
        when(images.list(eq(h.getId()), any())).thenReturn(List.of(
                image("boom:1", OLD), image("gone:1", OLD), image("nginx:latest", OLD)));
        scheduler(ref -> {
            if (ref.repository().equals("library/boom")) {
                throw new IllegalStateException("registry down");
            }
            return ref.repository().equals("library/gone") ? Optional.empty() : Optional.of(NEW);
        }).check();
        assertThat(store.hasUpdate(h.getId(), "docker.io/library/nginx:latest")).isTrue();
        assertThat(store.hasUpdate(h.getId(), "docker.io/library/boom:1")).isFalse();
        assertThat(store.hasUpdate(h.getId(), "docker.io/library/gone:1")).isFalse();
    }

    @Test
    void failingHostKeepsPreviousResultAndDoesNotStopOtherHosts() {
        DockerHost bad = mock(DockerHost.class);
        when(bad.getId()).thenReturn(UUID.randomUUID());
        DockerHost good = mock(DockerHost.class);
        when(good.getId()).thenReturn(UUID.randomUUID());
        when(repository.findAll()).thenReturn(List.of(bad, good));
        when(resolver.endpointOf(any())).thenReturn(new DockerEndpoint.Local("/sock"));
        store.replace(bad.getId(), java.util.Set.of("docker.io/library/old:1"), now.minusSeconds(60));
        when(images.list(eq(bad.getId()), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        when(images.list(eq(good.getId()), any())).thenReturn(List.of(image("nginx:latest", OLD)));

        scheduler(ref -> Optional.of(NEW)).check();

        assertThat(store.hasUpdate(bad.getId(), "docker.io/library/old:1")).isTrue();
        assertThat(store.hasUpdate(good.getId(), "docker.io/library/nginx:latest")).isTrue();
    }

    @Test
    void sharedReferenceIsAskedOncePerRunAndDeletedHostsAreForgotten() {
        DockerHost h = host();
        UUID gone = UUID.randomUUID();
        store.replace(gone, java.util.Set.of("x"), now);
        when(images.list(eq(h.getId()), any())).thenReturn(List.of(
                image("nginx:latest", OLD), image("nginx:latest", OLD)));
        scheduler(ref -> {
            asked.add(ref.key());
            return Optional.of(NEW);
        }).check();
        assertThat(asked).hasSize(1);
        assertThat(store.checkedAt(gone)).isNull();
    }
}
