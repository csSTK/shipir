package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.HostPortsResponse.UsedPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure logic of the host port report: used ports, free-port suggestion, input parsing. */
class HostPortsServiceTest {

    @Test
    void parsesPublishedPortsIncludingRangesAndProtocols() {
        Set<UsedPort> used = HostPortsService.usedPorts(List.of(
                "8080:80/tcp", "53:53/udp", "9000-9002:9000-9002/tcp", "80/tcp", "0.0.0.0:8443:443/tcp"));
        assertThat(used).containsExactlyInAnyOrder(
                new UsedPort(8080, "tcp"), new UsedPort(53, "udp"),
                new UsedPort(9000, "tcp"), new UsedPort(9001, "tcp"), new UsedPort(9002, "tcp"),
                new UsedPort(8443, "tcp"));
    }

    @Test
    void ignoresUnparsableEntries() {
        assertThat(HostPortsService.usedPorts(List.of("garbage", "x:y/tcp", ""))).isEmpty();
    }

    @Test
    void freePreferredPortIsReturnedUnchanged() {
        assertThat(HostPortsService.suggest(List.of(8080), Set.of(80)))
                .extracting("free").containsExactly(8080);
    }

    @Test
    void takenPortGetsNextFreeOne() {
        assertThat(HostPortsService.suggest(List.of(8080), Set.of(8080, 8081)))
                .extracting("free").containsExactly(8082);
    }

    @Test
    void protocolDoesNotMatterForSuggestionsAndSuggestionsAreDistinct() {
        assertThat(HostPortsService.suggest(List.of(8080, 8080, 8081), Set.of(8080)))
                .extracting("free").containsExactly(8081, 8082, 8083);
    }

    @Test
    void topOfRangeFallsBackDownwardAndExhaustionGivesNull() {
        assertThat(HostPortsService.suggest(List.of(65535), Set.of(65535)))
                .extracting("free").containsExactly(65534);
        assertThat(HostPortsService.suggest(List.of(1), allPorts()))
                .extracting("free").containsExactly((Integer) null);
    }

    private static Set<Integer> allPorts() {
        return java.util.stream.IntStream.rangeClosed(1, 65535).boxed().collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void parsesPreferredList() {
        assertThat(HostPortsService.parsePreferred("8080, 8081")).containsExactly(8080, 8081);
        assertThat(HostPortsService.parsePreferred(null)).isEmpty();
        assertThat(HostPortsService.parsePreferred("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "abc", "80,,81", "80;81", "99999999999"})
    void rejectsInvalidPreferred(String raw) {
        assertThatThrownBy(() -> HostPortsService.parsePreferred(raw))
                .isInstanceOf(HostException.class).hasMessageContaining("port");
    }

    @Test
    void rejectsTooManyPreferred() {
        String raw = java.util.stream.IntStream.rangeClosed(1, 51).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        assertThatThrownBy(() -> HostPortsService.parsePreferred(raw)).isInstanceOf(HostException.class);
    }
}
