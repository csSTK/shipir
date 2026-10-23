package dev.bethge.shipir.ai.tools;

/** Thrown by a tool for an argument the schema cannot express (e.g. a malformed timestamp); becomes an error result. */
public class ToolArgumentException extends RuntimeException {

    public ToolArgumentException(String message) {
        super(message);
    }
}
