package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageReference#parse} and {@link ImageReferenceRules} validate tags with one shared pattern. Both must accept
 * and reject exactly the same tags, otherwise a reference could be valid for pull but unusable for the stored-image
 * identity (or the reverse).
 */
class ImageReferenceTagParityTest {

    private static final List<String> TAGS = List.of(
            "latest", "1.2", "_private", "a-b_c.d", "A", "a".repeat(128),
            "-leading", ".leading", "a".repeat(129), "a b", "a;b", "");

    @Test
    void parseAndRulesAcceptAndRejectTheSameTags() {
        for (String tag : TAGS) {
            boolean viaParse = ImageReference.parse("nginx:" + tag) != null;
            boolean viaRules = ImageReferenceRules.isValidRepositoryAndTag("nginx", tag);
            assertThat(viaParse).as("tag '%s' accepted by ImageReference.parse", tag).isEqualTo(viaRules);
        }
    }

    @Test
    void theSampleSetContainsBothAcceptedAndRejectedTags() {
        assertThat(ImageReferenceRules.isValidRepositoryAndTag("nginx", "1.2")).isTrue();
        assertThat(ImageReferenceRules.isValidRepositoryAndTag("nginx", "-leading")).isFalse();
    }
}
