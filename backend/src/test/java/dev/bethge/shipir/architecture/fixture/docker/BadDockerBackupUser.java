package dev.bethge.shipir.architecture.fixture.docker;

import dev.bethge.shipir.architecture.fixture.backup.BackupThing;

/** Deliberate violation: docker/ depends on backup/, a module that is not listed by name anywhere. */
public class BadDockerBackupUser {
    BackupThing backup;
}
