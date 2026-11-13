# Code map: backend audit

- backend/src/main/java/dev/bethge/shipir/audit/{AuditLogEntry,AuditRecord,AuditLogRepository,AuditService}.java: `audit_log` entity (details JSONB), input record, repository, REQUIRES_NEW recording service
- backend/src/main/java/dev/bethge/shipir/audit/{Auditable,AuditAspect}.java: `@Auditable` annotation and aspect: writes SUCCESS/FAILURE after the transaction, swallows recording errors, skips throttled (429) requests
- backend/src/main/java/dev/bethge/shipir/audit/{AuditLogController,AuditLogQueryService,InvalidAuditQueryException}.java, backend/src/main/java/dev/bethge/shipir/audit/dto/*: ADMIN-only GET /api/audit-log (paged, newest first, filters action/username/from/to)
- backend/src/test/java/dev/bethge/shipir/audit/AuditLogApiTest.java: audit read API (paging, filters, 400s, role access)
- backend/src/test/java/dev/bethge/shipir/audit/{AuditAspectTest,AuditServiceFailureTest,AuditSelfRowLockTest}.java: rows per action, no secrets, rollback survival, audit failure does not fail the request, no deadlock on own user row
- backend/src/main/resources/db/migration/V8__create_audit_log.sql: Flyway migration for audit_log
