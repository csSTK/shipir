package dev.bethge.shipir.config_editor;

import dev.bethge.shipir.common.exception.CodedException;
import org.springframework.http.HttpStatus;

/** A config file request that cannot be fulfilled; carries HTTP status and a stable error code. */
public class ConfigFileException extends RuntimeException implements CodedException {

    private final HttpStatus status;
    private final String code;

    public ConfigFileException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    @Override
    public String code() { return code; }
}
