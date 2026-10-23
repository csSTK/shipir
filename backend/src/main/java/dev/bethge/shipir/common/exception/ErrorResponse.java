package dev.bethge.shipir.common.exception;

import java.util.List;

/** Uniform JSON error body: stable machine-readable {@code code}, human {@code message}, optional {@code details}. */
public record ErrorResponse(String code, String message, List<String> details) {

    public ErrorResponse(String code, String message) {
        this(code, message, List.of());
    }
}
