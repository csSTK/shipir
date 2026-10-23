package dev.bethge.shipir.host.dto;

/** Input limits shared by the request DTOs. */
final class HostLimits {

    /** A certificate chain or key is a few KB; 64 KB leaves room for long chains. */
    static final int MAX_PEM_CHARS = 64 * 1024;

    private HostLimits() {
    }
}
