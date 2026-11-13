# Code map: backend backup

- backend/src/main/java/dev/bethge/shipir/backup/{BackupService,BackupController,BackupException,TarWriter}.java, backend/src/main/java/dev/bethge/shipir/backup/dto/BackupRequest.java: ADMIN POST /api/backup (step-up) streams an encrypted tar.gz of all DB tables and stacks; format and decrypt steps in the BackupService Javadoc
- backend/src/test/java/dev/bethge/shipir/backup/{BackupApiTest,BackupStreamingTest,BackupSnapshotTest,BackupStepUpTest,BackupTestBase}.java: roles, CSRF, passphrase policy, round trip incl. real openssl, large file chunking, snapshot consistency, step-up, audit
