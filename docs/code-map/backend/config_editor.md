# Code map: backend config_editor

- backend/src/main/java/dev/bethge/shipir/config_editor/EnvLikeFiles.java: single matcher for env-like and secret-like file names; their content needs OPERATE, AI tools never get it
- backend/src/main/java/dev/bethge/shipir/config_editor/{ConfigFileService,ConfigFileController,ConfigFileException,ConfigFileProperties}.java, backend/src/main/java/dev/bethge/shipir/config_editor/dto/*: config files in a deployment dir (tree, read, write, backups) via SecureFiles; compose.yaml/.env hidden; staged read for the Git review
- backend/src/test/java/dev/bethge/shipir/config_editor/{ConfigFileApiTest,EnvLikeFilesTest}.java: malicious paths, symlinks, excluded/read-only paths, backups and retention, restore, roles, CSRF, audit
- backend/src/test/java/dev/bethge/shipir/config_editor/{ConfigFileSymlinkSwapTest,ConfigFileSymlinkRaceTest}.java: symlink swap and concurrent directory flip while reading and writing
