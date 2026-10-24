package dev.bethge.shipir.backup;

/** Backup could not be started; messages never contain the passphrase. */
public class BackupException extends RuntimeException {
    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
