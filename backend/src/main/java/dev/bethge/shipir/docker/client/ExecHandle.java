package dev.bethge.shipir.docker.client;

/** An open interactive (TTY) exec session. Methods are thread-safe; {@link #close()} is idempotent. */
public interface ExecHandle extends AutoCloseable {

    /** Sends raw bytes (keystrokes) to the process' stdin. Ignored after close. */
    void write(byte[] bytes);

    /** Resizes the pseudo terminal. Failures are swallowed (the size is cosmetic). */
    void resize(int cols, int rows);

    /** Closes the session: the stream is dropped and the TTY hangs up, which ends the process. */
    @Override
    void close();
}
