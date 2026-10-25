package dev.bethge.shipir.host.dto;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Parsed multipart build request: either an uploaded {@code context} (tar or tar.gz) or a Git source
 * ({@code gitUrl} + {@code gitRef}, optional stored {@code gitCredentialId}). {@code buildArgs} are raw
 * {@code KEY=VALUE} strings; values never reach logs or the audit log. {@link #toString()} prints nothing sensitive.
 */
public record ImageBuildRequest(MultipartFile context, String tag, String dockerfile, List<String> buildArgs,
                                boolean noCache, String gitUrl, String gitRef, UUID gitCredentialId) {

    /** Audit target: the tag, capped so a hostile value cannot bloat the log. */
    public String auditTarget() {
        String t = String.valueOf(tag);
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    /** Audit detail: where the context came from. */
    public String source() {
        return context != null ? "UPLOAD" : "GIT";
    }

    @Override
    public String toString() {
        return "ImageBuildRequest[tag=" + tag + ", source=" + source() + "]";
    }
}
