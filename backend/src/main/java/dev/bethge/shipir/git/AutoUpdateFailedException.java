package dev.bethge.shipir.git;

/** A redeploy triggered by the polling scheduler was rejected; {@code code} is a stable error code, never a secret. */
public class AutoUpdateFailedException extends RuntimeException {

    private final String code;

    public AutoUpdateFailedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
