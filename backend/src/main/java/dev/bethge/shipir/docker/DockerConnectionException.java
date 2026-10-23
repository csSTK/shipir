package dev.bethge.shipir.docker;

/** A Docker daemon could not be reached or answered with an error; carries a stable {@link DockerErrorCode}. */
public class DockerConnectionException extends RuntimeException {

    private final DockerErrorCode code;

    public DockerConnectionException(DockerErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DockerErrorCode getCode() {
        return code;
    }
}
