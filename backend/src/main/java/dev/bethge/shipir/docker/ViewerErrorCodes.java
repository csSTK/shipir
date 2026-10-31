package dev.bethge.shipir.docker;

import java.util.EnumSet;
import java.util.Set;

/**
 * Error code shown for a Docker failure of a stored environment. Codes that describe the connection itself or what
 * answered (TLS, permission, timeout, invalid response, not found on the info endpoint, ...) tell a reader which of the
 * admin-chosen targets is listening and how, so only ADMINs see them. For everyone else this is an allow-list: only
 * codes that are meaningful after a real Docker operation (pull/build/create failures, name taken, in use, ...) pass
 * through; everything else, including any code added later, becomes {@code UNREACHABLE}.
 */
public final class ViewerErrorCodes {

    private static final Set<DockerErrorCode> PASS_THROUGH = EnumSet.of(
            DockerErrorCode.PULL_FAILED, DockerErrorCode.PUSH_FAILED, DockerErrorCode.BUILD_FAILED,
            DockerErrorCode.NAME_TAKEN, DockerErrorCode.START_FAILED, DockerErrorCode.RECREATE_FAILED,
            DockerErrorCode.UPDATE_REJECTED, DockerErrorCode.INVALID_STATE, DockerErrorCode.INVALID_PATH,
            DockerErrorCode.INVALID_VOLUME_NAME, DockerErrorCode.INVALID_ARCHIVE, DockerErrorCode.SIZE_LIMIT_EXCEEDED,
            DockerErrorCode.DIRECTORY_TOO_LARGE, DockerErrorCode.DIRECTORY_EXEC_UNAVAILABLE,
            DockerErrorCode.BUILTIN_NETWORK, DockerErrorCode.IN_USE);

    private ViewerErrorCodes() {
    }

    public static String forViewer(DockerErrorCode code, boolean admin) {
        return !admin && !PASS_THROUGH.contains(code) ? DockerErrorCode.UNREACHABLE.name() : code.name();
    }

    /** For codes that are not a {@link DockerErrorCode} (e.g. the overview's shared-deadline TIMEOUT). */
    public static String forViewer(String code, boolean admin) {
        try {
            return forViewer(DockerErrorCode.valueOf(code), admin);
        } catch (IllegalArgumentException e) {
            return admin ? code : DockerErrorCode.UNREACHABLE.name();
        }
    }

    /**
     * For frames that carry either a Docker code or one of our own stable codes (UNAVAILABLE, CONTAINER_LIMIT, ...):
     * a {@link DockerErrorCode} name is filtered like {@link #forViewer(DockerErrorCode, boolean)} (except NOT_FOUND, which
     * the stream views map to "no longer exists"), anything else is
     * ours, cannot be influenced by the Docker endpoint, and passes unchanged.
     */
    public static String forStreamFrame(String code, boolean admin) {
        if (code == null) {
            return null;
        }
        if (DockerErrorCode.NOT_FOUND.name().equals(code)) {
            return code; // "the container is gone" is what the log/stats views show; it says nothing about the endpoint
        }
        try {
            return forViewer(DockerErrorCode.valueOf(code), admin);
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}
