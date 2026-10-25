package dev.bethge.shipir.host.dto;

/** New name for an existing image; both parts are validated by the service. */
public record TagImageRequest(String repository, String tag) {

    /** Audit target: the new reference, capped so a hostile value cannot bloat the log. */
    public String auditTarget() {
        String ref = repository + ":" + tag;
        return ref.length() > 255 ? ref.substring(0, 255) : ref;
    }
}
