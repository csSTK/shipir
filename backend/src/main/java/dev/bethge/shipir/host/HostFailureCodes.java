package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerErrorCode;

import java.util.EnumSet;
import java.util.Set;

/**
 * Error code shown for a Docker failure of a stored environment. Codes that describe the connection itself or what
 * answered (TLS, permission, timeout, invalid response, not found on the info endpoint, ...) tell a reader which of the
 * admin-chosen targets is listening and how, so only ADMINs see them. For everyone else this is an allow-list: only
 * codes that are meaningful after a real Docker operation (pull/build/create failures, name taken, in use, ...) pass
 * through; everything else, including any code added later, becomes {@code UNREACHABLE}.
 */
final class HostFailureCodes {

    private static final Set<DockerErrorCode> PASS_THROUGH = EnumSet.of(
            DockerErrorCode.PULL_FAILED, DockerErrorCode.PUSH_FAILED, DockerErrorCode.BUILD_FAILED,
            DockerErrorCode.NAME_TAKEN, DockerErrorCode.START_FAILED, DockerErrorCode.RECREATE_FAILED,
            DockerErrorCode.UPDATE_REJECTED, DockerErrorCode.INVALID_STATE, DockerErrorCode.INVALID_PATH,
            DockerErrorCode.INVALID_VOLUME_NAME, DockerErrorCode.INVALID_ARCHIVE, DockerErrorCode.SIZE_LIMIT_EXCEEDED,
            DockerErrorCode.DIRECTORY_TOO_LARGE, DockerErrorCode.DIRECTORY_EXEC_UNAVAILABLE,
            DockerErrorCode.BUILTIN_NETWORK, DockerErrorCode.IN_USE);

    private HostFailureCodes() {
    }

    static String forViewer(DockerErrorCode code, boolean admin) {
        return !admin && !PASS_THROUGH.contains(code) ? DockerErrorCode.UNREACHABLE.name() : code.name();
    }

    /** For codes that are not a {@link DockerErrorCode} (e.g. the overview's shared-deadline TIMEOUT). */
    static String forViewer(String code, boolean admin) {
        try {
            return forViewer(DockerErrorCode.valueOf(code), admin);
        } catch (IllegalArgumentException e) {
            return admin ? code : DockerErrorCode.UNREACHABLE.name();
        }
    }
}
