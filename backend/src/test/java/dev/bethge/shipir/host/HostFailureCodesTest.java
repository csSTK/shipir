package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HostFailureCodesTest {

    /** Meaningful after a real Docker operation: no oracle for the target's address/port. */
    private static final Set<DockerErrorCode> PASS_THROUGH = EnumSet.of(
            DockerErrorCode.PULL_FAILED, DockerErrorCode.PUSH_FAILED, DockerErrorCode.BUILD_FAILED,
            DockerErrorCode.NAME_TAKEN, DockerErrorCode.START_FAILED, DockerErrorCode.RECREATE_FAILED,
            DockerErrorCode.UPDATE_REJECTED, DockerErrorCode.INVALID_STATE, DockerErrorCode.INVALID_PATH,
            DockerErrorCode.INVALID_VOLUME_NAME, DockerErrorCode.INVALID_ARCHIVE, DockerErrorCode.SIZE_LIMIT_EXCEEDED,
            DockerErrorCode.DIRECTORY_TOO_LARGE, DockerErrorCode.DIRECTORY_EXEC_UNAVAILABLE,
            DockerErrorCode.BUILTIN_NETWORK, DockerErrorCode.IN_USE);

    /** Say something about the connection itself (or what answered): collapsed for non-admins. */
    private static final Set<DockerErrorCode> COLLAPSED = EnumSet.of(
            DockerErrorCode.UNREACHABLE, DockerErrorCode.TIMEOUT, DockerErrorCode.TLS_ERROR,
            DockerErrorCode.PERMISSION_DENIED, DockerErrorCode.INVALID_TLS_MATERIAL, DockerErrorCode.INVALID_RESPONSE,
            DockerErrorCode.NOT_FOUND);

    @Test
    void everyCodeIsClassifiedSoNewCodesAreDecidedOnPurpose() {
        Set<DockerErrorCode> all = EnumSet.copyOf(PASS_THROUGH);
        all.addAll(COLLAPSED);
        assertThat(all).containsExactlyInAnyOrder(DockerErrorCode.values());
        assertThat(PASS_THROUGH).doesNotContainAnyElementsOf(COLLAPSED);
    }

    @ParameterizedTest
    @EnumSource(DockerErrorCode.class)
    void nonAdminsSeeOnlyAllowListedCodesAdminsSeeAll(DockerErrorCode code) {
        String expected = PASS_THROUGH.contains(code) ? code.name() : "UNREACHABLE";
        assertThat(HostFailureCodes.forViewer(code, false)).isEqualTo(expected);
        assertThat(HostFailureCodes.forViewer(code, true)).isEqualTo(code.name());
    }

    @Test
    void stringOverloadCollapsesTimeoutAndUnknownNames() {
        assertThat(HostFailureCodes.forViewer("TIMEOUT", false)).isEqualTo("UNREACHABLE");
        assertThat(HostFailureCodes.forViewer("TIMEOUT", true)).isEqualTo("TIMEOUT");
        assertThat(HostFailureCodes.forViewer("SOMETHING_ELSE", false)).isEqualTo("UNREACHABLE");
        assertThat(HostFailureCodes.forViewer("SOMETHING_ELSE", true)).isEqualTo("SOMETHING_ELSE");
    }
}
