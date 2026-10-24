package dev.bethge.shipir.auth.totp.dto;

/** The only time the secret leaves the server. */
public record SetupResponse(String secret, String otpauthUri) {

    @Override
    public String toString() {
        return "SetupResponse[***]";
    }
}
