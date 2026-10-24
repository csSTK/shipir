package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageReferenceTest {

    @Test
    void expandsDockerHubShorthands() {
        assertThat(ImageReference.parse("nginx").key()).isEqualTo("docker.io/library/nginx:latest");
        assertThat(ImageReference.parse("grafana/grafana:11").key()).isEqualTo("docker.io/grafana/grafana:11");
        assertThat(ImageReference.parse("index.docker.io/library/redis:7").key()).isEqualTo("docker.io/library/redis:7");
    }

    @Test
    void keepsRegistryWithPort() {
        ImageReference ref = ImageReference.parse("127.0.0.1:5000/team/app:1.2");
        assertThat(ref.registryHost()).isEqualTo("127.0.0.1:5000");
        assertThat(ref.repository()).isEqualTo("team/app");
        assertThat(ref.tag()).isEqualTo("1.2");
    }

    @Test
    void ignoresDigestSuffixAndRejectsDigestOnlyAndGarbage() {
        assertThat(ImageReference.parse("nginx:1@sha256:" + "a".repeat(64)).tag()).isEqualTo("1");
        assertThat(ImageReference.parse("nginx@sha256:" + "a".repeat(64))).isNull();
        assertThat(ImageReference.parse("../evil:1")).isNull();
        assertThat(ImageReference.parse("a b")).isNull();
        assertThat(ImageReference.parse(null)).isNull();
    }
}
