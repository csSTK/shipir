package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateStoreTest {

    private final ImageUpdateStore store = new ImageUpdateStore();
    private final UUID host = UUID.randomUUID();
    private final Instant checkedAt = Instant.parse("2026-03-10T03:00:00Z");

    @Test
    void markApplied_removesOnlyTheGivenKeysAndKeepsTheCheckTime() {
        store.replace(host, Set.of("a:1", "b:2"), checkedAt);

        store.markApplied(host, List.of("a:1"));

        assertThat(store.hasUpdate(host, "a:1")).isFalse();
        assertThat(store.hasUpdate(host, "b:2")).isTrue();
        assertThat(store.checkedAt(host)).isEqualTo(checkedAt);
    }

    @Test
    void markApplied_forUncheckedHostDoesNotCreateAnEntry() {
        store.markApplied(host, List.of("a:1"));

        assertThat(store.checkedAt(host)).isNull();
    }
}
