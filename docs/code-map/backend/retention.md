# Code map: backend retention

- backend/src/main/java/dev/bethge/shipir/retention/{RetentionSettings,RetentionSettingsRepository,RetentionService,RetentionController}.java, backend/src/main/java/dev/bethge/shipir/retention/dto/*: singleton retention settings, ADMIN GET/PUT /api/settings/retention (1-3650 days), audited with old/new values
- backend/src/main/java/dev/bethge/shipir/retention/{RetentionPurgeService,RetentionPurgeJob}.java: daily purge of audit log, notifications, chat sessions and stats samples; publishes ChatSessionsPurgedEvent, writes a system audit entry
- backend/src/test/java/dev/bethge/shipir/retention/{RetentionTestBase,RetentionApiTest,RetentionPurgeTest}.java: fixed-clock purge tests and settings API
- backend/src/main/resources/db/migration/V11__create_retention_settings.sql: Flyway migration for the retention settings singleton
