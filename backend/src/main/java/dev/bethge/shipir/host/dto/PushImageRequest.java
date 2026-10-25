package dev.bethge.shipir.host.dto;

/** Image to push, {@code repository:tag}; validated by the service. */
public record PushImageRequest(String reference) {

    /** Audit target: the reference, capped so a hostile value cannot bloat the log. */
    public String auditTarget() {
        String ref = String.valueOf(reference);
        return ref.length() > 255 ? ref.substring(0, 255) : ref;
    }
}
