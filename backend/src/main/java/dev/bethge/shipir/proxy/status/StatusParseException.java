package dev.bethge.shipir.proxy.status;

/** The proxy's certificate storage could not be read; the message is fixed text and never contains file content. */
class StatusParseException extends RuntimeException {

    StatusParseException(String message) {
        super(message);
    }
}
