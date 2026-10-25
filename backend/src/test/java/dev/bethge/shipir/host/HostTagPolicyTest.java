package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostTagPolicyTest {

    @Test
    void nullOrEmptyMeansNoTags() {
        assertThat(HostTagPolicy.normalize(null)).isEmpty();
        assertThat(HostTagPolicy.normalize(List.of())).isEmpty();
    }

    @Test
    void trimsLowercasesDedupesAndSortsTheResult() {
        assertThat(HostTagPolicy.normalize(List.of("staging", "  Prod ", "PROD", "staging")))
                .containsExactly("prod", "staging");
    }

    @Test
    void tenDistinctTagsAreAccepted() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("tag-" + i);
        }
        assertThat(HostTagPolicy.normalize(ten)).hasSize(10);
    }

    @Test
    void elevenDistinctTagsAreRejectedWithTooManyTags() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add("tag-" + i);
        }
        assertThatThrownBy(() -> HostTagPolicy.normalize(eleven))
                .isInstanceOfSatisfying(HostException.class, e -> {
                    assertThat(e.code()).isEqualTo("TOO_MANY_TAGS");
                    assertThat(e.status().value()).isEqualTo(400);
                });
    }

    @Test
    void duplicatesDoNotCountTowardTheLimit() {
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            repeated.add(i % 2 == 0 ? "prod" : "PROD ");
        }
        assertThat(HostTagPolicy.normalize(repeated)).containsExactly("prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "prod", "eu-west-1", "0", "abcdefghijklmnopqrstuvwx"})
    void validNamesAreAccepted(String tag) {
        assertThat(HostTagPolicy.normalize(List.of(tag))).containsExactly(tag);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-prod", "prod_1", "pro d", "prod.eu", "ümlaut", "abcdefghijklmnopqrstuvwxy", " ", ""})
    void invalidNamesAreRejectedWithInvalidTag(String tag) {
        assertThatThrownBy(() -> HostTagPolicy.normalize(List.of("prod", tag)))
                .isInstanceOfSatisfying(HostException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_TAG");
                    assertThat(e.status().value()).isEqualTo(400);
                });
    }

    @Test
    void nullElementIsRejectedWithInvalidTag() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> HostTagPolicy.normalize(withNull))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TAG"));
    }
}
