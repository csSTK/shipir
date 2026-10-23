package dev.bethge.shipir.audit;

class InvalidAuditQueryException extends RuntimeException {

    InvalidAuditQueryException(String message) {
        super(message);
    }
}
