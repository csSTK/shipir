package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerDirectoryExecListerTest {

    @Test
    void fifosSocketsAndDevicesAreOtherNotFile() {
        Set<String> all = new LinkedHashSet<>(List.of("dir", "link", "plain", "pipe", "sock"));
        var entries = ContainerDirectoryExecLister.toEntries("/p", all, Set.of("dir"), Set.of("link"), Set.of("plain"));

        assertThat(entries).extracting(ContainerFileEntry::name, ContainerFileEntry::type).containsExactly(
                org.assertj.core.groups.Tuple.tuple("dir", ContainerFileEntry.Type.DIRECTORY),
                org.assertj.core.groups.Tuple.tuple("link", ContainerFileEntry.Type.SYMLINK),
                org.assertj.core.groups.Tuple.tuple("plain", ContainerFileEntry.Type.FILE),
                org.assertj.core.groups.Tuple.tuple("pipe", ContainerFileEntry.Type.OTHER),
                org.assertj.core.groups.Tuple.tuple("sock", ContainerFileEntry.Type.OTHER));
    }

    @Test
    void connectionErrorsSurfaceAsTheirOwnCodeNotDirectoryTooLarge() {
        var manager = new DockerHostConnectionManager(
                new DockerProperties("unix:///nonexistent.sock", Duration.ofSeconds(2), Duration.ofSeconds(2)));
        var lister = new ContainerDirectoryExecLister(manager);

        assertThatThrownBy(() -> lister.list(UUID.randomUUID(), null, "c", "/data"))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.UNREACHABLE));
    }

    @Test
    void noExecStatesMapToExecUnavailable() {
        for (DockerErrorCode noExec : List.of(DockerErrorCode.INVALID_STATE, DockerErrorCode.IN_USE,
                DockerErrorCode.INVALID_RESPONSE)) {
            assertThat(ContainerDirectoryExecLister.mapFailure(new DockerConnectionException(noExec, "x", null)).getCode())
                    .isEqualTo(DockerErrorCode.DIRECTORY_EXEC_UNAVAILABLE);
        }
    }

    @Test
    void connectionErrorsKeepTheirOwnCodeInMapFailure() {
        for (DockerErrorCode keep : List.of(DockerErrorCode.UNREACHABLE, DockerErrorCode.TLS_ERROR,
                DockerErrorCode.NOT_FOUND, DockerErrorCode.TIMEOUT, DockerErrorCode.DIRECTORY_TOO_LARGE)) {
            assertThat(ContainerDirectoryExecLister.mapFailure(new DockerConnectionException(keep, "x", null)).getCode())
                    .isEqualTo(keep);
        }
    }

    @Test
    void findMissingOrNotExecutableExitIsExecUnavailable() {
        for (long exit : List.of(126L, 127L)) {
            assertThatThrownBy(() -> ContainerDirectoryExecLister.checkExit(exit))
                    .isInstanceOfSatisfying(DockerConnectionException.class,
                            e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.DIRECTORY_EXEC_UNAVAILABLE));
        }
    }

    @Test
    void otherNonZeroExitIsStillTooLarge() {
        assertThatThrownBy(() -> ContainerDirectoryExecLister.checkExit(1L))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.DIRECTORY_TOO_LARGE));
    }

    @Test
    void zeroExitPassesTheCheck() {
        ContainerDirectoryExecLister.checkExit(0L);
    }
}
