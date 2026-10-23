# Shipir

Web platform for managing Docker containers and Compose stacks, similar to a simplified Portainer, with a UI non-experts can use (forms instead of mandatory YAML). Includes editing config files and creating/editing Compose files.

**Purpose: learning and portfolio project for Java backend job applications (Germany/Europe).** Code quality, clean architecture, meaningful tests and traceable decisions matter more than the number of features. No shortcuts that couldn't be explained in an interview. Write clear, focused commits.

Current state: project skeleton in place. Backend in `backend/` with Maven and Spring Boot, base package `dev.bethge.shipir`. Most feature classes are still empty placeholders. Frontend in `frontend/` with React, TypeScript and Vite. `HANDOFF.md` holds remaining planning context and open decisions.

## Working Rules

- **Do not silently deviate from the decisions in this file or in `HANDOFF.md`.** If you want to change something, give your reasoning and ask first.
- **Do not guess open decisions** (listed in `HANDOFF.md`). Ask the user.
- **Out of scope for now:** SaaS control plane with customer-side agent, hosting for third parties (Proxmox provisioning), AI assistant (LLM chat). Don't build them, and don't design in a way that blocks them. Concretely: all Docker access goes through the `docker/` abstraction so another connection type (agent over a tunnel) can be added later. Nothing more than that (YAGNI).

## Database Access

- **Reading is allowed** (e.g. `psql -c "SELECT ..."`, `\d`, `pg_dump`).
- **Writing never happens without the user's explicit consent.** That covers `INSERT`/`UPDATE`/`DELETE`, DDL, running migrations or starting the app against a real database (`spring-boot:run` runs Flyway), restoring dumps, SQL from files or stdin, and deleting database volumes (`docker compose down -v`). Ask first, say exactly what will be written and to which database, and wait for a yes.
- Throwaway Testcontainers databases in tests (`./backend/mvnw test`/`verify`) and the beads tracker (`bd`) are exempt.
- Sub-agents never write to a database. If a task needs a write, they stop and report it so the orchestrator can ask the user.
- Enforced by `.claude/hooks/db-write-guard.py` (PreToolUse on Bash): suspected writes always trigger a permission prompt. It can't see writes hidden inside scripts or app code, so the rule applies regardless of the hook.

## Frontend UI

- **All new or changed frontend UI follows the `apple-design` skill** (`.claude/skills/apple-design/SKILL.md`). Read it before building or reviewing UI: instant press feedback, springs for anything the user can touch, restraint, typography, materials, and `prefers-reduced-motion`.
- New UI libraries (e.g. `motion` for springs) are added only when a bead calls for them, not ad hoc.

## Code Map

**Read this section first when implementing.** Use it to find the exact files to change, and only open code when the map isn't enough.

**Keep it current:** whenever you add, move or remove a file or change what it does, update its entry here in the same change. As short as possible: one line per file or folder, `path: what it is`. No narrative, no history, no change logs.

Backend (`backend/src/main/java/dev/bethge/shipir/`):
- `ShipirApplication.java`: Spring Boot entry point
- `**/package-info.java`: one-line Javadoc per package of the architecture tree
- `config_editor/ConfigFile.java`, `permission/Permission.java`, `streaming/LogStreamHandler.java`: empty placeholders
- `compose/generator/{ComposeGeneratorService,ComposeGeneratorController,GenerateRequest,GenerateResponse,InvalidComposeSpecException}.java`, `compose/generator/model/*`: wizard model (`ComposeSpec`/`ServiceSpec`/ports/env/mounts/networks/volumes records) to Compose YAML (Jackson YAML, fixed key order); `POST /api/compose/generate` (ADMIN/OPERATOR via SecurityConfig matcher), 400 INVALID_COMPOSE_SPEC with all violations; bind paths via `PathValidationUtil`
- `backend/src/test/java/dev/bethge/shipir/compose/generator/`: golden/invalid-input unit tests, API roles test, `docker compose config` check (skipped without CLI)
- `compose/{Stack,StackVersion,StackStatus,StackSource,VersionSource,StackRepository,StackVersionRepository}.java`: `stacks`/`stack_versions` entities (version = YAML + encrypted .env) and repositories
- `compose/{StackService,StackException,EnvFile,ManagedStackIndex}.java`: managed-stack CRUD (files written + `docker compose config` validated, restored/rolled back on failure; delete runs compose down, 502 COMPOSE_DOWN_FAILED keeps stack); `@Auditable` STACK_CREATE/UPDATE/DELETE; `.env` render/parse; `ManagedStackIndex` implements host's `ManagedStackLookup`
- `compose/web/StackController.java`, `compose/dto/*`: `/api/hosts/{hostId}/managed-stacks` GET list, GET `{id}` (yaml + env keys only), GET `{id}/env` (values, ADMIN/OPERATOR), POST, PUT (env null = keep, [] = clear), DELETE; reads all roles, writes ADMIN/OPERATOR; errors 400 INVALID_COMPOSE/INVALID_STACK_NAME/INVALID_ENV, 404 STACK_NOT_FOUND/HOST_NOT_FOUND, 409 STACK_NAME_TAKEN/ENCRYPTION_KEY_MISSING
- `host/{ManagedStackLookup,dto/HostStackResponse}.java`: port + response of live `GET /api/hosts/{id}/stacks` (adds `managed`, `managedStackId`)
- `backend/src/test/java/dev/bethge/shipir/compose/Stack*Test.java`: `StackTestBase` (temp base dir, fake compose execution), `StackApiTest`, `StackNoEncryptionKeyTest`; `host/HostManagedStacksFlagTest`
- `audit/{AuditLogEntry,AuditRecord,AuditLogRepository,AuditService}.java`: `audit_log` entity (details JSONB), input record, repository, `record` in REQUIRES_NEW tx
- `audit/{Auditable,AuditAspect}.java`: `@Auditable(action, targetType, targetId SpEL, username SpEL)`; aspect writes SUCCESS/FAILURE (error-status `ResponseEntity` = FAILURE, exceptions rethrown), user from SecurityContext, recording errors swallowed; used on `AuthController` login/logout/password/reset-confirm and `HostService` create/rename/delete
- `audit/{AuditLogController,AuditLogQueryService,InvalidAuditQueryException}.java`, `audit/dto/{AuditLogEntryResponse,AuditLogPageResponse}.java`: ADMIN-only `GET /api/audit-log?page&size&action&username&from&to` (newest first, size 1..100 else 400 INVALID_PARAMETER, ISO instants)
- `backend/src/test/java/dev/bethge/shipir/audit/AuditLogApiTest.java`: audit read API (paging, filters, 400s, role access)
- `backend/src/test/java/dev/bethge/shipir/audit/`: `AuditAspectTest` (rows per action, null user_id on failed login, rollback survival, no secrets), `AuditServiceFailureTest` (audit exception does not fail request)
- `host/{DockerHost,ConnectionType,DockerHostRepository}.java`: environment entity (TLS columns are ciphertext), LOCAL/TCP enum, repository
- `host/{HostController,HostService,HostException,HostExceptionHandler}.java`: `/api/hosts` list/create/connection-test/rename/delete (create pings first: 422 CONNECTION_FAILED; 400/409 codes via `HostException`)
- `host/{HostOverviewService,HostOverviewProperties}.java`: `GET /api/hosts/overview` (in `HostController`): parallel live `info` per host on virtual threads, shared deadline `shipir.host.overview-timeout` (5s), failures -> DOWN + error code (docker codes, TIMEOUT, DECRYPTION_FAILED, ENCRYPTION_KEY_MISSING, UNKNOWN)
- `host/{HostHealthCheckScheduler,HostStatusChangedEvent}.java`: `@Scheduled` (`shipir.host.health-check-interval`=60s, `health-check-enabled`=true; test properties disable it) reuses `HostOverviewService`, in-memory last status per host (first observation = baseline), publishes `HostStatusChangedEvent` on UP/DOWN flips, forgets deleted hosts
- `common/config/SchedulingConfig.java`: `@EnableScheduling`
- `host/{HostResourcesController,HostResourcesService}.java`: read-only `GET /api/hosts/{id}/{stacks,containers,images,networks,volumes}` (all roles) via docker/ services; 404 HOST_NOT_FOUND, 502 with DockerErrorCode / DECRYPTION_FAILED / ENCRYPTION_KEY_MISSING (errors via `HostExceptionHandler`)
- `compose/{StackDeploymentService,DeploymentLocks}.java`, `compose/dto/{DeploymentResponse,StackVersionResponse}.java`: managed-stack `POST {id}/deploy` (202 + deploymentId; snapshots draft as new `StackVersion`, writes files, `up` on a virtual thread streamed via `ComposeOutputStreamService`), `POST {id}/stop` (sync `down`), `GET {id}/versions`, `POST {id}/versions/{vid}/rollback` (new ROLLBACK version + deploy); one run per stack (409 DEPLOYMENT_IN_PROGRESS, also blocks delete); `@Auditable` STACK_DEPLOY/STOP/ROLLBACK = accepted/rejected, worker records STACK_DEPLOY_RESULT / STACK_ROLLBACK_RESULT with the compose outcome; status RUNNING/ERROR, `last_deployed_at` on success; deploy/rollback versions get `deployed_at` (V14) and are immutable: `StackService.update` edits an undeployed draft in place, otherwise creates a new MANUAL draft
- `backend/src/test/java/dev/bethge/shipir/compose/Stack{DeployApi,RealDeploy}Test.java`: deploy/stop/rollback API (stubbed execution via `StackTestBase.upFails/upGate`, mocked stream service) and one real local-socket deploy (skipped without Docker/compose)
- `backend/src/test/java/dev/bethge/shipir/host/HostResources{Api,Resilience,NoKey}Test.java`: resource endpoints (real local socket; stubbed services for 502 codes; missing key)
- `host/HostEndpointResolver.java`: public, builds `DockerEndpoint` from a `DockerHost` (decrypts TLS)
- `host/HostEncryptionStartupCheck.java`: fails startup if TCP hosts exist without `shipir.encryption-key`
- `host/dto/*.java`: create/test/update requests (toString hides PEMs), `HostResponse` (`tls` boolean only), `HostOverviewResponse` (stats null when DOWN)
- `notification/{NotificationWebhook,NotificationWebhookRepository}.java`: webhook entity (URL ciphertext + `urlHint`, `event_types` as text[]), repository (`recordDelivery`)
- `notification/{WebhookController,WebhookService,WebhookException}.java`: ADMIN `/api/notifications/webhooks` (GET/POST, PUT/DELETE `/{id}`, POST `/{id}/test`); `@Auditable` WEBHOOK_CREATE/UPDATE/DELETE; 400 INVALID_WEBHOOK_URL, 409 WEBHOOK_NAME_TAKEN / ENCRYPTION_KEY_MISSING, 404 WEBHOOK_NOT_FOUND
- `notification/{WebhookDeliveryService,WebhookProperties}.java`: `HostStatusChangedEvent` listener, async (virtual threads) JSON POST per enabled subscribed webhook, no redirects/retries, `shipir.notification.webhook-timeout` (5s), records last delivery status (SUCCESS/HTTP_n/TIMEOUT/FAILED)
- `notification/dto/Webhook{Request,Response,TestResponse}.java`: webhook bodies (request toString hides URL)
- `backend/src/test/java/dev/bethge/shipir/notification/Webhook*Test.java`: `WebhookTestBase` (JDK HttpServer stubs, key set), API, delivery, no-key tests
- `backend/src/test/java/dev/bethge/shipir/host/`: `HostHealthCheckSchedulerTest` (unit, stubbed docker, fixed clock), `HostApiTestBase` (login helpers, local socket), `HostApiTest`, `HostOverview{Api,Resilience}Test` (real LOCAL; stubbed port: failing/hanging/undecryptable hosts), `HostApiWithoutEncryptionKeyTest`, `HostEncryptionStartupCheckTest`
- `auth/{User,Role,UserRepository}.java`: user entity (`isSetupRequired()`), ADMIN/OPERATOR/VIEWER enum, repository
- `auth/InitialAdminSeeder.java`, `auth/InitialAdminProperties.java`: seeds first admin on empty DB from `shipir.initial-admin.*` (fails startup without password)
- `auth/AuthController.java`: `/api/auth/{login,logout,me,password,password-reset/{request,confirm}}` (reset: request always 202, confirm 204 / 400 INVALID_RESET_CODE or PASSWORD_POLICY, no login; password: 204, rotates session id + CSRF, local handlers for 400 INVALID_CURRENT_PASSWORD / PASSWORD_POLICY); login rotates session id + CSRF token, same 401 body for any failure
- `auth/PasswordPolicy.java`: 15-128 chars (String.length), upper/lower/digit/symbol, not username (case-insens.), not current; returns all `Violation` codes
- `auth/UserService.java`: `changePassword` (current check, policy, new hash + `passwordChangedAt`), package-private exceptions
- `auth/UserSetupStatus.java`: implements `SetupStatus`: step PASSWORD (password unchanged) / ENVIRONMENT (ADMIN, no host via `EnvironmentPresence`) / none
- `auth/{PasswordResetCode,PasswordResetCodeRepository}.java`: reset code entity (SHA-256 hex hash only) + `findLatestOpen`/`invalidateOpenCodes`
- `auth/{PasswordResetCodeSender,NoOpPasswordResetCodeSender,PasswordResetProperties}.java`: delivery port (default drops code), `shipir.password-reset.{code-ttl=5m,max-attempts=5}`
- `auth/{PasswordResetService,InvalidResetCodeException}.java`: `requestReset` (unknown user no-op), `confirmReset` (constant-time compare, attempt counter, policy, single use; one uniform exception)
- `auth/DatabaseUserDetailsService.java`: `UserDetailsService` backed by `UserRepository` (`ROLE_<role>`)
- `auth/dto/{LoginRequest,PasswordResetRequest,PasswordResetConfirmRequest,PasswordChangeRequest,CurrentUserResponse,PasswordPolicyErrorResponse}.java`: request bodies (toString hides passwords), `{username, role, setupRequired, setupStep}`, `{code, message, violations}`
- `common/config/SecurityConfig.java`: session-based filter chain: cookie CSRF (`XSRF-TOKEN` on every response), JSON 401/403, `/api/**` authenticated except login/logout/password-reset request+confirm, `/api/hosts` writes ADMIN-only, `AuthenticationManager`, wires `SetupGateFilter`
- `common/security/{SetupGateFilter,SetupStatus,SetupStep,EnvironmentPresence}.java`: gate: users with a pending step get 403 SETUP_REQUIRED on `/api/**` except GET me, POST logout/password (+ POST /api/hosts, /api/hosts/connection-test during ENVIRONMENT); ports implemented by auth (`SetupStatus`) and host (`EnvironmentPresence`)
- `host/HostEnvironmentPresence.java`: `EnvironmentPresence` adapter (`hasAnyHost` = repository count > 0)
- `common/exception/{ErrorResponse,GlobalExceptionHandler}.java`: `{code, message, details}` record; handler for validation/malformed body (400)
- `common/security/{SecretCipher,EncryptionProperties}.java`: AES-256-GCM for secrets at rest (`version+IV+ciphertext`); key `shipir.encryption-key` (env `SHIPIR_ENCRYPTION_KEY`, base64 32 bytes, `openssl rand -base64 32`), optional (empty/blank = `isConfigured()` false); invalid key fails startup
- `common/security/{EncryptionKeyMissing,InvalidEncryptionKey,SecretDecryption}Exception.java`: cipher exceptions, messages never contain key or plaintext
- `backend/src/test/java/dev/bethge/shipir/common/security/SecretCipherTest.java`: round trip, tamper, wrong key, key validation, empty key, no logging
- `retention/{RetentionSettings,RetentionSettingsRepository,RetentionService,RetentionController}.java`, `retention/dto/*`: singleton `retention_settings` (V11), ADMIN `GET/PUT /api/settings/retention` (1-3650, PUT `@Auditable` RETENTION_UPDATE)
- `retention/{RetentionPurgeService,RetentionPurgeJob}.java`: purge (rows strictly older than now-days, audit_log + notifications via bulk deletes, writes RETENTION_PURGE audit entry as `system`); cron `shipir.retention.purge-cron` (03:00 UTC), off with `shipir.retention.purge-enabled=false`
- `backend/src/test/java/dev/bethge/shipir/retention/`: `RetentionTestBase` (fixed Clock), `RetentionApiTest`, `RetentionPurgeTest`
- `common/util/{PathValidationUtil,PathValidationException}.java`: central path guard: `resolve(base, relative)` (rejects empty/absolute/`..`/NUL/backslash/percent-encoding, symlink escape via real path of nearest existing ancestor), `validateStackName` (`[a-z0-9][a-z0-9_-]{0,62}`); test `PathValidationUtilTest`
- `git/{GitCredential,GitCredentialRepository,GitCredentialService,GitCredentialController,GitException}.java`, `git/dto/*`: ADMIN `/api/git/credentials` GET/POST, PUT/DELETE `/{id}`, POST `/{id}/test` (body `repoUrl`, https only; `{status}` OK/AUTH_FAILED/NOT_FOUND/UNREACHABLE); token via `SecretCipher` + `tokenHint`, `@Auditable` GIT_CREDENTIAL_CREATE/UPDATE/DELETE; 409 CREDENTIAL_IN_USE (native count on `stacks`) / CREDENTIAL_NAME_TAKEN / ENCRYPTION_KEY_MISSING, 400 INVALID_REPO_URL / TOKEN_REQUIRED, 404 CREDENTIAL_NOT_FOUND
- `git/{GitRemoteChecker,GitProperties}.java`: JGit `ls-remote` + URL validation; `shipir.git.{connect-timeout=10s,allow-insecure-http=false}` (http only for test stubs)
- `backend/src/test/java/dev/bethge/shipir/git/`: `GitTestBase`, `GitCredentialApiTest`, `GitCredentialConnectionTest` (JDK HttpServer smart-HTTP stub), `GitCredentialWithoutKeyTest`
- `git/{GitRepositoryService,GitCheckout,GitOperationException}.java`: public API for compose/: `sync(repoDir, url, ref, composePath, credentialId)` shallow fetch (branch/tag/SHA) + hard reset + compose file read via `PathValidationUtil` (traversal/symlink out -> INVALID_COMPOSE_PATH); codes GIT_AUTH_FAILED, GIT_REF_NOT_FOUND, COMPOSE_FILE_NOT_FOUND, GIT_UNREACHABLE; token only in `CredentialsProvider`, never in URL/logs/messages
- `compose/StackService.java` (Git): `create` with `source=GIT` (`repoUrl,ref,composePath,credentialId`; row first, cleanup on failure); `update` of GIT stacks: yaml change -> 400 GIT_MANAGED, env editable; `Stack` git fields, `dto/GitInfo` in detail
- `compose/StackDeploymentService.java` (Git): `POST {id}/git/redeploy` (`gitRedeploy`, audit STACK_GIT_REDEPLOY[_RESULT]): sync under lock, validate, new GIT version with `commit_sha`; deploy/rollback copies keep `commit_sha`; 400 NOT_A_GIT_STACK
- `git/{GitPollingScheduler,AutoUpdateStacks,AutoUpdateFailedException}.java`: `@Scheduled` tick (`shipir.git.poll-tick`=60s, `poll-enabled`=true; test props disable) over `AutoUpdateStacks` port (defined in git/, implemented by compose/, so git/ never imports compose/): per stack interval check (`last_polled_at`), `GitRepositoryService.remoteCommit` (ls-remote, annotated tags peeled), redeploy only if SHA != `last_commit_sha`; BUSY skipped, failures stored as `last_poll_error` code, logged only on change, one stack never stops others
- `compose/StackAutoUpdates.java`: `AutoUpdateStacks` adapter; failed check audited once per distinct error as `STACK_AUTO_UPDATE` FAILURE user `system`; `StackDeploymentService.autoUpdate` (package-private) runs the git redeploy as `system` (version `created_by` = stack creator), audit `STACK_AUTO_UPDATE` + `STACK_AUTO_UPDATE_RESULT`
- `compose/web/StackController.java` + `StackService.configureAutoUpdate` + `compose/dto/AutoUpdateRequest.java`: `PUT {id}/git/auto-update` `{enabled, intervalSeconds>=60}` (ADMIN/OPERATOR, 400 NOT_A_GIT_STACK / VALIDATION_ERROR, audit `STACK_AUTO_UPDATE_CONFIG`); `GitInfo` also carries `autoUpdateEnabled, autoUpdateIntervalSeconds, lastPolledAt, lastPollError`; V15 adds `stacks.last_polled_at/last_poll_error`
- `backend/src/test/java/dev/bethge/shipir/git/GitPollingSchedulerTest.java`, `compose/{AutoUpdateApi,StackAutoUpdates}Test.java`: scheduler (fixed Clock, stubbed port/git), settings API, port adapter
- `backend/src/test/java/dev/bethge/shipir/compose/GitStackApiTest.java`: Git stacks against JGit `UploadPack` behind JDK HttpServer (Basic auth): create, redeploy, unchanged commit, rollback sha, path/symlink rejection, errors, token leak check, roles
- `common/config/PasswordEncoderConfig.java`: Argon2id `PasswordEncoder` bean (DelegatingPasswordEncoder, bcrypt match-only)
- `common/config/ClockConfig.java`: `Clock` bean (system UTC)
- `backend/src/test/resources/application.properties`: test initial-admin password
- `backend/src/test/java/dev/bethge/shipir/auth/AuthEndpointsTest.java`: MockMvc + Testcontainers: login/me/logout, CSRF, session fixation, identical 401, JSON 401
- `backend/src/test/java/dev/bethge/shipir/auth/PasswordPolicyTest.java`, `PasswordChangeTest.java`, `SetupEnvironmentStepTest.java`: policy rules (unit); setup gate + password change flow, PASSWORD/ENVIRONMENT steps (MockMvc, own DB via `@DirtiesContext`)
- `backend/src/test/java/dev/bethge/shipir/auth/PasswordResetEndpointsTest.java`: reset endpoints (MockMvc, capturing sender, CSRF, errors, toString)
- `backend/src/test/java/dev/bethge/shipir/auth/InitialAdminSeeder{,Startup}Test.java`: seeding, no-op rerun, missing password, no password in logs
- `backend/src/test/java/dev/bethge/shipir/auth/PasswordResetServiceTest.java`: reset flow (Testcontainers, capturing sender, mutable clock, hash-only row, log capture)
- `compose/execution/{ComposeExecutionService,ComposeCommand,ComposeProcessResult,ComposeProperties,ComposeExecutionException}.java`: `docker compose -p -f --project-directory` subprocess (args list, no shell), `DOCKER_HOST` per `DockerEndpoint`, TLS PEMs in 0700 temp dir deleted in finally, line callback, `shipir.compose.timeout` (10m) kills process tree
- `compose/validation/{ComposeValidationService,ValidationResult}.java`: `config --quiet` -> `{valid, errors}`
- `backend/src/test/java/dev/bethge/shipir/compose/{execution,validation}/`: fake-script unit tests, real `docker compose` integration (up/down, config)
- `docker/{DockerEndpoint,DockerErrorCode,DockerConnectionException,DockerProperties}.java`: own types: `Local|Tcp(TlsMaterial, toString hides PEMs)`, UNREACHABLE/TIMEOUT/TLS_ERROR/PERMISSION_DENIED/INVALID_TLS_MATERIAL, `shipir.docker.{local-socket,connect-timeout,read-timeout}`
- `docker/DockerHostConnectionManager.java`: cached docker-java client per host id (`client`/`createClient` internal to docker/), `evict(hostId)`, `localEndpoint()`, `readTimeout()`
- `docker/{DockerClientFactory,DefaultDockerClientFactory}.java`: package-private seam + httpclient5 factory (Local socket; Tcp, with `PemSslConfig` when TLS material is set)
- `docker/tls/{PemSslConfig,TlsMaterialParser,TlsMaterialValidator}.java`: in-memory PEM (CA, client chain, RSA/EC key PKCS#8/PKCS#1/SEC1) to docker-java `SSLConfig`; invalid PEM -> `DockerConnectionException(INVALID_TLS_MATERIAL)`; `TlsMaterialValidator.validate` for host/
- `docker/client/DockerSystemService{,Impl}.java`: `ping(endpoint)` (uncached, never throws) and `info(hostId, endpoint)`; enforces read timeout itself (Unix transport ignores it)
- `docker/client/Container{Service,ServiceImpl}.java`: `listContainers` (all states) and `listStacks` (grouped by `com.docker.compose.project`, RUNNING/PARTIAL/STOPPED); `docker/client/DockerCallSupport.java`: package-private read-timeout deadline + `DockerConnectionException` mapping, shared by docker/client services
- `docker/dto/{ContainerDto,StackSummaryDto}.java`: container (health from status text, ports `pub:priv/type`, compose labels) and stack summary
- `docker/client/{Image,Network,Volume}Service{,Impl}.java`: `list(hostId, endpoint)`; inUse/containerCount derived from the container list in the same call; dangling image = empty `repoTags`; volume `created` is currently always null (docker-java drops `CreatedAt`)
- `docker/dto/{ImageDto,NetworkDto,VolumeDto}.java`: image (id, repoTags, size, created, inUse), network (subnet, internal, containerCount), volume (mountpoint, created, inUse)
- `docker/dto/{ConnectionTestResult,DockerSystemInfoDto}.java`: ping result, host overview counts incl. healthy/unhealthy
- `backend/src/test/java/dev/bethge/shipir/docker/**`: endpoint, manager (fake factory), properties defaults, no-docker-java-in-API check, service unit (missing/hanging socket), Docker integration tests, dind TLS integration test, PEM parsing (`tls/PemSslConfigTest`, `tls/TestPki` generates certs)
- `compose/StackProperties.java`: `shipir.stacks.base-dir` (env `SHIPIR_STACKS_BASE_DIR`, default `./data/stacks`)
- `compose/file/ComposeFileRepository.java`: `<base>/<hostId>/<stackName>/{compose.yaml,.env}` write (temp + atomic move)/read/delete, dirs 0700, files 0600, `stackDir`/`repoDir` (Git checkout `repo/`), all paths via `PathValidationUtil`, contents never logged; test `ComposeFileRepositoryTest`
- `common/config/WebSocketConfig.java`: STOMP at `/ws` (plain WebSocket, simple broker `/topic`, same-origin check); handshake authenticated by the session-cookie filter chain (401 otherwise)
- `streaming/{StompSecurityInterceptor,CsrfHandshakeInterceptor}.java`: inbound frame policy: CONNECT needs user + `X-XSRF-TOKEN` header equal to handshake `XSRF-TOKEN` cookie, SUBSCRIBE only `/topic/deployments/{id}`, client SEND refused
- `streaming/{ComposeOutputStreamService,StreamMessage,DeploymentStatus,StreamProperties}.java`: `start(deploymentId)` returns `Consumer<String>` sink for `ComposeExecutionService.execute(onLine)`, `complete(id, DONE|FAILED)`; frames `{seq,type LINE|STATUS,line,status}` sent per subscription with bounded replay buffer (`shipir.streaming.{buffer-lines=2000,retention=10m,max-streams=200}`)
- `backend/src/test/java/dev/bethge/shipir/streaming/ComposeOutputStreamTest.java`: real STOMP client (unauth, CSRF, lines + status, late subscriber, buffer cap, forbidden destination/SEND)
- `backend/pom.xml`: Maven build, Java 25, Spring Boot 4.1.1 parent, docker-java 3.7.1 (core + httpclient5), JGit, starters web/websocket/security/validation/data-jpa/flyway/test, spring-security-test, flyway-database-postgresql, postgresql driver, Testcontainers (test)
- `backend/mvnw`, `backend/.mvn/wrapper/`: Maven wrapper
- `backend/Dockerfile`: multi-stage image: Temurin 25 build (tests skipped), JRE alpine runtime + docker-cli/docker-cli-compose, user uid 10001, `/data/stacks` (`SHIPIR_STACKS_BASE_DIR`), env-only config
- `backend/.dockerignore`: excludes `target/`, `.env`, keys, IDE and git files from the image build
- `backend/src/main/resources/application.yml`: app name, datasource (`DB_HOST/PORT/NAME/USER` with local defaults, `${DB_PASSWORD}` no default), JPA validate/no OSIV, Flyway
- `backend/src/main/resources/db/migration/V{1..4}__create_{users,docker_hosts,stacks,stack_versions}.sql`, `V5__add_users_password_changed_at.sql`, `V6__add_docker_hosts_tls_columns.sql`, `V7__create_password_reset_codes.sql`, `V9__create_notifications.sql`, `V10__create_notification_webhooks.sql`, `V11__create_retention_settings.sql`, `V12__create_git_credentials.sql`, `V13__extend_stacks_for_deploy.sql`: Flyway schema (TIMESTAMPTZ, FKs to users RESTRICT, host/stack FKs CASCADE; V5 NULL = first-start setup pending; V6 docker_hosts: encrypted TLS BYTEA columns, connection shape/TLS/name constraints; V7 password_reset_codes: hashed codes, attempt counter, CASCADE with user; V8 audit_log: username snapshot, outcome check, user FK SET NULL, indexes on occurred_at and (action, occurred_at)); V9 notifications: type CHECK HOST_DOWN/HOST_UP, host_id SET NULL keeps host_name, preferences PK (user_id, type); V10 notification_webhooks: URL as ciphertext + hint only, event_types text[], created_by RESTRICT; V11 retention_settings: singleton (id CHECK 1), days 1-3650, seeded 365/30, updated_by SET NULL; V12 git_credentials: unique name, token as ciphertext + hint, created_by RESTRICT; V13 stacks: source_type FILE/GIT with git columns iff GIT, credential FK RESTRICT, auto-update interval >=60, current_version_id FK SET NULL; stack_versions: env_ciphertext, commit_sha, source MANUAL/GIT/ROLLBACK
- `backend/src/test/java/dev/bethge/shipir/ShipirApplicationTests.java`: context-load + Postgres 16 test
- `backend/src/test/java/dev/bethge/shipir/DatasourcePropertiesTest.java`: resolves application.yml datasource defaults and `DB_*` overrides (no context, no DB)
- `notification/{Notification,NotificationPreference,NotificationType,*Repository}.java`: notification + per-user preference entities (missing preference row = enabled), HOST_DOWN/HOST_UP
- `notification/NotificationService.java`: `@EventListener` on `HostStatusChangedEvent` (one notification per opted-in user), list/unread-count/mark-read (own rows only)/preferences
- `notification/NotificationController.java`: `/api/notifications` list (`unreadOnly,page,size`, size max 100), `unread-count`, `{id}/read` (other user's id -> 404), `read-all`, `preferences` GET/PUT; `notification/dto/*`
- `backend/src/test/java/dev/bethge/shipir/notification/`: `NotificationTestBase`, `NotificationApiTest` (MockMvc), `NotificationListenerTest` (event -> rows)
- `backend/src/test/java/dev/bethge/shipir/migration/SchemaMigrationTest.java`: V1-V13 schema test (git_credentials, stacks deploy columns/checks, retention_settings singleton/range/seed/updated_by SET NULL, notification_webhooks columns/unique/no plaintext URL/created_by RESTRICT, timestamptz, unique, cascade, restrict, password_changed_at, docker_hosts checks, password_reset_codes columns/checks/cascade, audit_log columns/outcome check/user SET NULL/indexes, notifications/notification_preferences), `@Transactional` rollback
- `backend/src/test/java/dev/bethge/shipir/PostgresTestConfiguration.java`: reusable `@TestConfiguration`, `@ServiceConnection` postgres:16-alpine; integration tests `@Import` it

Frontend (`frontend/`):
- `vite.config.ts`: dev proxy (`/api`, `/ws` → :8080), Vitest config
- `src/main.tsx`: entry, mounts `App`
- `src/app/App.tsx`: root component, `Providers` + `BrowserRouter` + auth gate (loading, `LoginPage` on 401, `SetupPage` while `setupRequired`, routes `/` overview, `/account`, `/notifications`, `/settings/*`, `/environments/:id` dashboard and `/environments/:id/{stacks,containers,images,networks,volumes}` pages, sign out)
- `src/app/providers.tsx`, `src/app/queryClient.ts`: React Query setup
- `src/shared/api/client.ts`: `apiFetch<T>` + `ApiError` (status, code, violations, details), JSON fetch against `/api`, same-origin credentials, `X-XSRF-TOKEN` from cookie on non-GET
- `src/features/auth/{api.ts,LoginPage.tsx,LoginPage.css,index.ts}`: `useCurrentUser/useLogin/useLogout/useChangePassword/useRequestPasswordReset/useConfirmPasswordReset` hooks, login form with `Forgot password?` view switch + success notice
- `src/features/auth/PasswordResetPage.{tsx,css,test.tsx}`: reset flow (username -> neutral message + 6-digit code, new password with live rules; INVALID_RESET_CODE / PASSWORD_POLICY inline)
- `src/features/auth/{SetupPage.tsx,SetupPage.css,passwordPolicy.ts}`: first-start setup, 2 steps with indicator (`step` prop = `setupStep`: PASSWORD form, ENVIRONMENT embeds hosts `EnvironmentForm` then refetches me); client mirror of backend password rules
- `src/app/layout/{AppShell,Sidebar,UserMenu}.tsx` (+ `.css`, `AppShell.test.tsx`): app shell: top bar (brand, user menu with Settings -> /account and Sign out), sidebar (Environments, Notifications, env section with Dashboard + resources while on `/environments/:id/*`, ADMIN-only Settings group), narrow-screen toggle
- `src/app/layout/PlaceholderPages.tsx`: `PlaceholderPage`, `NotAllowedPage` (non-ADMIN on /settings/*)
- `e2e/shell.spec.ts`: sidebar/user menu navigation, viewer not allowed on settings
- `src/features/notifications/{api.ts,NotificationsPage.tsx,.css,index.ts}`: `useNotifications(unreadOnly)`, `useUnreadCount` (30 s + focus), `useMarkRead/useMarkAllRead`; `/notifications` page (assumed API: Page `{content,totalElements}`, `{count}`, POST `/{id}/read`, `/read-all`); Sidebar shows the unread badge
- `src/features/account/AccountPage.{tsx,css,test.tsx}`: `/account` (props `user`): read-only username/role, password change (reuses `useChangePassword` + `passwordPolicy.ts`), notification preference switches (optimistic save)
- `src/features/notifications/api.ts`: also `useNotificationPreferences`/`useSavePreferences` (GET/PUT `/notifications/preferences`)
- `src/features/settings/{api.ts,WebhooksSettingsPage.tsx,.css,.test.tsx,index.ts}`: ADMIN webhook management at `/settings/notifications` (list with urlHint, create/edit dialog with never-prefilled URL, delete, send test)
- `src/features/settings/{RetentionSettingsPage.tsx,.css,.test.tsx}`: ADMIN retention form at `/settings/retention` (audit log / notification days 1-3650, client check, inline errors); hooks `useRetentionSettings`/`useSaveRetentionSettings` in `api.ts`
- `src/features/settings/AuditLogPage.{tsx,css,test.tsx}`: ADMIN audit log at `/settings/audit-log` (table, action/user/date filters + page in URL query, empty/error states); `useAuditLog`, `AUDIT_ACTION_LABELS` in `api.ts`
- `e2e/webhooks.spec.ts`: admin creates webhook + test, operator blocked
- `frontend/src/features/settings/GitCredentialsSettingsPage.{tsx,css,test.tsx}`: ADMIN git credentials at `/settings/git` (list with tokenHint, create/edit dialog with never-prefilled token, test-connection dialog, delete; CREDENTIAL_IN_USE/NAME_TAKEN inline); hooks `useGitCredentials` etc. in settings `api.ts`; Sidebar Settings link
- `frontend/e2e/git-credentials.spec.ts`: add + test connection, in-use delete conflict, operator blocked
- `src/shared/ui/{ResourceTable.tsx,.css,index.ts}`: shared list table for environment resources: `ResourceTable<T>` (columns with optional `sortValue`, search, loading skeleton, error + Retry, empty/no-match states; props `label`, `rows`, `isPending`, `error` (mapped message), `toolbar`) and `Badge` (tone ok/warn/bad/muted); reuse for images/networks/volumes
- `src/features/containers/{api.ts,ContainersPage.tsx,.css,index.ts}`: `useContainers(hostId)`; `/environments/:id/containers` table, `?stack=<project>` filter chip
- `src/features/{images,networks,volumes}/{api.ts,*Page.tsx,.css,index.ts}`: `useImages/useNetworks/useVolumes(hostId)`; `/environments/:id/{images,networks,volumes}` tables on `ResourceTable` (usage/internal badges, missing values as `–`); `formatSize` in `hosts/format.ts`
- `e2e/images-networks-volumes.spec.ts`: images/networks/volumes pages (search, sort, badges, error + retry)
- `src/features/stacks/editor/{YamlEditor,MonacoYamlEditor,EnvEditor}.tsx`, `envFile.ts`, `compose-spec.json`, `editor.css`: `YamlEditor` (lazy Monaco + monaco-yaml, local Compose schema, Vite workers, no CDN; `error` prop shows server details; Vitest globally mocks `MonacoYamlEditor` as a textarea in `src/test/setup.ts`), `EnvEditor` (masked rows, reveal, paste .env), `.env` parse/serialize/validate/merge; `StackContentPanel` edit/save (PUT, env null keeps values) and `AddStackDialog` use them; monaco-editor pinned 0.52.2 (monaco-yaml 5.5 breaks on 0.57 exports)
- `src/features/stacks/{api.ts,StacksPage.tsx,.css,index.ts}`: `useStacks(hostId)` + managed-stack hooks (`useManagedStacks/useManagedStack/useStackVersions/useCreateStack/useDeleteStack/useDeployStack/useStopStack/useRollbackStack`); `/environments/:id/stacks` table joins live + managed stacks (unmanaged name links to containers filtered by stack, managed to detail), Add stack (ADMIN/OPERATOR), row Deploy/Stop
- `src/features/stacks/{StackDetailPage,AddStackDialog,ConfirmDialog,StackDialog}.tsx`, `StackUi.css`, `StackDetailPage.css`, `stackErrors.ts`: `/environments/:id/stacks/:stackId` (status, Deploy/Stop/Delete, versions + Rollback confirm, writes hidden for VIEWER); add form (textarea + env rows), confirm/modal shell, API error mapping
- `src/features/stacks/StackContentPanel.tsx`: stack YAML + env keys, Edit/Save draft (uses `editor/`)
- `src/features/stacks/DeploymentPanel.tsx`: `{deployment, onFinished}` wrapper around `deploy-output/DeployOutput`; `StackDetailPage` refetches `['hosts', hostId]` queries when the stream ends
- `src/features/stacks/deploy-output/{DeployOutput.tsx,.css,useDeploymentStream.ts}`: live deploy log (`role=log`, autoscroll pauses on scroll-up + Jump to latest, Succeeded/Failed badge, reconnect state); hook subscribes `/topic/deployments/{id}`, de-duplicates by `seq`, unsubscribes on finish/unmount
- `src/shared/realtime/stomp.ts`: `subscribeToTopic(destination, {onMessage,onState})` over `@stomp/stompjs` (`/ws`, `X-XSRF-TOKEN` on CONNECT, auto reconnect); tests mock this module
- `e2e/deploy-output.spec.ts`: deploy run via `page.routeWebSocket` STOMP mock: success/failed badge, scroll pause, reconnect without duplicates
- `e2e/stack-detail.spec.ts`: operator create -> deploy -> rollback -> delete, viewer read-only
- `src/features/stacks/wizard/{model.ts,ComposeWizard.tsx,wizard.css,index.ts}`: Compose form wizard (services: image/command/restart/ports/env literal or `.env` ref/volumes/networks/depends_on; top-level networks/volumes); `model.ts` = form state, `validateWizard`, `buildSpec` (generate payload), `referencedEnvKeys`; one-way (no YAML -> form); preview via `useGenerateCompose` (`api.ts`, `POST /compose/generate`)
- `src/features/stacks/AddStackDialog.tsx`: also 'Form wizard' / 'YAML' mode switch; 'Continue in editor' fills the YAML editor and adds empty env rows for referenced keys
- `e2e/compose-wizard.spec.ts`: wizard builds 2 services + network + env ref, preview, continue, create; local and server (INVALID_COMPOSE_SPEC) errors
- `e2e/resources.spec.ts`: stacks/containers pages: search, sort, stack filter, UNREACHABLE/TIMEOUT, empty
- `src/features/hosts/api.ts`: host types, `HOSTS_KEY`/`HOSTS_OVERVIEW_KEY`, `useHosts/useHostsOverview` (30 s + focus refetch, `HostOverview`), `useCreateHost/useTestConnection/useRenameHost/useDeleteHost` (create/delete also invalidate current user)
- `src/features/hosts/EnvironmentForm.{tsx,css}`: Local / Remote (TCP+TLS) form, PEM file pickers (names only shown), connection test, inline server errors; props `onCreated`, `submitLabel`
- `src/features/hosts/{EnvironmentsOverview.tsx,.css}`: home screen: environment rows (status, stats), search, refresh, skeleton/error/empty states, ADMIN add dialog, renders `EnvironmentActions` per row for ADMIN
- `src/features/hosts/EnvironmentActions.{tsx,css}`: ADMIN row Rename/Delete buttons + dialogs (HOST_NAME_TAKEN inline, delete warns about stacks)
- `src/features/hosts/EnvironmentDashboard.{tsx,css,test.tsx}`: `/environments/:id`: status, Docker version, resource tiles (counts from overview) linking to resource routes; DOWN error, not-found
- `src/features/hosts/resources.ts`: `ENV_RESOURCES` (stacks/containers/images/networks/volumes) for sidebar, tiles
- `src/features/hosts/format.ts`: memory/plural formatting, overview error code to message, `describeResourceError(err)` for `/hosts/{id}/...` failures
- `src/features/hosts/index.ts`: exports form, overview, dashboard, `ENV_RESOURCES`, hooks, types
- `src/test/setup.ts`: jest-dom matchers for Vitest
- `playwright.config.ts`: Playwright config (Chromium, `e2e/` dir, starts Vite dev server)
- `tsconfig.e2e.json`: type-checks `e2e/` and `playwright.config.ts` (referenced from `tsconfig.json`)
- `e2e/fixtures/api.ts`: Playwright `test` with auto `api` fixture: mocks `/api/**` per method+path, fails on unmocked calls (own spec: `api.spec.ts`)
- `e2e/account.spec.ts`: user menu > Settings, password change, preference toggle
- `e2e/retention.spec.ts`: admin changes retention and saves, server 400 inline
- `e2e/audit-log.spec.ts`: admin filters by action and pages, operator not allowed
- `e2e/app.spec.ts`: smoke spec, Shipir heading (signed in)
- `e2e/auth.spec.ts`: login happy path + sign out, wrong password
- `e2e/environments.spec.ts`: overview list/search, viewer vs admin add, admin rename/delete, taken name, operator no actions, error, dashboard navigation
- `e2e/environment-dashboard.spec.ts`: overview -> dashboard -> sidebar items, tile counts, DOWN, not found, narrow screen
- `e2e/password-reset.spec.ts`: forgot password happy path, invalid code, unknown user neutral message
- `e2e/setup.spec.ts`: setup flow (password -> environment step -> app), reload on step 2, connection failure, server error, no setup when done
- `Dockerfile`: multi-stage image (pnpm build, nginx-unprivileged on :8080; `BACKEND_URL`, `NGINX_RESOLVER`)
- `nginx/default.conf.template`: envsubst nginx config: SPA fallback, asset caching, `/api` + `/ws` proxy (re-resolving upstream)
- `.dockerignore`: keeps node_modules, dist, test output out of the build context

Repo root:
- `docker-compose.dev.yml`: local PostgreSQL 16 for development (healthcheck, `DB_*` from `.env`)
- `docker-compose.yml`: hosting stack, backend + frontend (`ghcr.io/csstk/shipir-*` or local build), external PostgreSQL, only frontend port published, passes `SHIPIR_INITIAL_ADMIN_PASSWORD` and `SHIPIR_ENCRYPTION_KEY`, mounts Docker socket (`group_add: DOCKER_GID`), named volume `shipir-stacks` at `/data/stacks`
- `.env.example`: documents `DB_*` (`DB_PASSWORD` required), `SHIPIR_INITIAL_ADMIN_PASSWORD` (empty DB only), `DOCKER_GID` (required, hosting), `SHIPIR_ENCRYPTION_KEY`, `SHIPIR_TAG`, `SHIPIR_PORT`; `.env` is gitignored

CI:
- `.github/workflows/tests.yml`: reusable tests (`workflow_call`/`workflow_dispatch`, inputs `backend`/`frontend`/`e2e`): Maven verify, pnpm lint/test/build, Playwright (report artifact on failure)
- `.github/workflows/ci.yml`: push to `dev` and PRs: paths-filter (`backend/**`, `frontend/**`, CI files) calls `tests.yml` with `e2e: false`; concurrency cancels superseded runs
- `.github/workflows/release.yml`: on push to `prd`, runs `tests.yml` (all suites), then builds and pushes backend/frontend images (matrix) to ghcr.io/csstk/shipir-{backend,frontend} tagged `sha-<full sha>` and `latest`
- `.github/workflows/tag.yml`: on `v*` tag push, re-tags the existing `sha-<commit>` images with semver tags (`imagetools create`, no build); fails if the sha image is missing

Repo tooling:
- `.claude/agents/implementer-{sonnet,haiku}.md`: implementation subagents (see Model Policy); follow `next-task-implement`, never commit or close beads
- `.claude/hooks/db-write-guard.py`: PreToolUse hook, forces a permission prompt for database writes
- `.claude/skills/apple-design/SKILL.md`: design and motion guidelines every frontend UI change follows
- `.claude/skills/text-to-task/SKILL.md`: `/text-to-task`, turns a text request into a beads epic + child tasks after a scope check
- `.claude/skills/next-task/SKILL.md`: `/next-task <epic-id> [--watch-ci]`, orchestrates an epic: branch → deps → plan → sub-agents → finish → optional CI watch
- `.claude/skills/next-task-{branch,deps,plan,finish}/SKILL.md`: orchestrator steps of `/next-task` (finish doesn't watch CI)
- `.claude/skills/next-task-implement/SKILL.md`: sub-agent sequence, calling `next-task-{write-tests,code,e2e,test}` (also usable on their own)
- `.claude/skills/watch-ci/SKILL.md`: `/watch-ci [run-id|branch]`, read-only: watches GitHub Actions runs via `gh`, reports result, offers a bug bead on failure

## Git Commits & Attribution

- **Never add Claude/AI attribution to git history.** No `Co-Authored-By: Claude ...` trailers, no "Generated with Claude Code" lines, no session links, in commit messages or PR descriptions. Only the user (or whoever is configured as the git committer) appears as author.
- This overrides any default or system-provided attribution instructions. Attribution is also disabled in `.claude/settings.json` (`attribution`).
- Don't change `git config user.name` / `user.email`.

## Branches

- `dev`: default branch. All work (epic branches, `/next-task`) branches from and merges into `dev`.
- `prd`: release branch, only ever a fast-forward of `dev`. Release: `git push origin dev:prd`, wait until `release.yml` is green, then tag `v*` on that commit (`tag.yml` re-tags the tested image). If `tag.yml` failed because the image was missing, re-run it (`gh run rerun <id>`). Never commit to `prd` directly; GitHub blocks force-push and deletion.
- Agents never push `prd` or tags without the user asking.

## Model Policy

- **Planning uses Opus.** Planning means architecture and stack decisions, breaking work into epics and tasks, writing issue descriptions and acceptance criteria, and setting dependencies. Run planning sessions on Opus (`/model opus`).
- **Implementation is done only by Sonnet or Haiku.** Opus does not write production code. When the session runs on Opus, delegate each implementation task to the `implementer-sonnet` or `implementer-haiku` subagent (`.claude/agents/`), passing the bead ID.
- **Pick the implementation model by scope:**
  - **Haiku**: small, well-specified, low-risk changes, such as boilerplate, renames, simple DTOs or entities, config tweaks, docs, and straightforward tests.
  - **Sonnet**: everything else, such as multi-file features, non-trivial logic, debugging, refactors, concurrency/streaming, and anything touching auth, permissions, remote host access or the audit log.
  - When unsure, use Sonnet.
- Before starting implementation, a task should have a bead with clear acceptance criteria. If it doesn't, stop and plan it (on Opus) first.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:46cd31e7 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

## Tech Stack

| Area | Decision |
|---|---|
| Backend | Java (current LTS), Spring Boot |
| Frontend | React: feature-oriented folders (`features/containers`, `features/stacks`, `features/hosts`, ...), Monaco Editor for YAML/configs, React Query |
| Database | PostgreSQL 16 |
| Migrations | Flyway. Hibernate never changes the schema (`ddl-auto: validate`) |
| Docker access | `docker-java`, wrapped behind our own interfaces |
| Compose execution | `docker compose` as a subprocess (`ProcessBuilder`). No reimplementation of the Compose spec |
| Real-time | WebSocket with STOMP (logs, events, compose output, exec terminal) |
| Auth | Spring Security, server-side session (HttpOnly cookie + CSRF), RBAC |
| Tests | JUnit 5, Testcontainers (Postgres + Docker), ArchUnit later |

## Build & Test

### Backend

Maven wrapper, no local Maven needed:
```bash
./backend/mvnw -f backend/pom.xml compile   # Compile
./backend/mvnw -f backend/pom.xml test      # Run tests
./backend/mvnw -f backend/pom.xml verify    # Full build incl. tests
```
**Docker must be running for backend tests** (Testcontainers starts a throwaway Postgres 16). No local Postgres or `DB_PASSWORD` needed.

### Frontend

From `frontend/`:
```bash
pnpm install                       # Install dependencies
pnpm dev                           # Run dev server
pnpm test                          # Run tests once
pnpm test:watch                    # Run tests in watch mode
pnpm lint                          # Lint with oxlint
pnpm build                         # Build for production
pnpm exec playwright install chromium   # One-time: browser for e2e tests
pnpm e2e                           # Run Playwright e2e tests
pnpm e2e:ui                        # Run e2e tests in Playwright UI mode
```

Local dev database: `docker compose -f docker-compose.dev.yml up -d` (`postgres:16-alpine`, named volume, `DB_NAME`/`DB_USER`/`DB_PORT`/`DB_PASSWORD` from `.env`, defaults `shipir`/`shipir`/`5432`). The app reads the same `DB_*` variables from its environment (export `.env` first, the app does not read `.env`); use `DB_PORT=5433` if a native Postgres occupies 5432.

Hosting: `docker compose -f docker-compose.yml build` builds both images locally; `up` needs an external database and runs Flyway against it (DB write, ask first). Images are published to GHCR by `.github/workflows/release.yml` on pushes to `prd`; details in the README.

## Architecture

**Package-by-feature**, not `controller/service/repository` at the top level. Each feature contains its own entity, repository, service, DTOs and controller.

```
dev.bethge.shipir
├── ShipirApplication.java
├── common/
│   ├── config/        SecurityConfig, WebSocketConfig, AsyncConfig, OpenApiConfig
│   ├── exception/     ApiException, GlobalExceptionHandler, ErrorResponse
│   ├── security/      CurrentUser, session auth support
│   └── util/          PathValidationUtil   (path traversal protection, centralized)
├── docker/            Abstraction over docker-java (hexagonal: ports + adapters)
│   ├── DockerHostConnectionManager   (one DockerClient per host; LOCAL = Unix socket, TCP = TLS)
│   ├── client/        ContainerService, ImageService, NetworkService, VolumeService (+ impl)
│   ├── dto/           ContainerDto, ContainerStatsDto, ImageDto
│   └── web/           ContainerController
├── host/              DockerHost (entity), repository, service, HostHealthCheckScheduler, DTOs, HostController
├── compose/           Core of the product
│   ├── Stack, StackVersion (entities), repositories, StackService
│   ├── file/          ComposeFileRepository          (reads/writes YAML on disk)
│   ├── generator/     ComposeGeneratorService + model/  (wizard input -> YAML)
│   ├── execution/     ComposeExecutionService, ComposeProcessResult   (ProcessBuilder)
│   ├── validation/    ComposeValidationService       (`docker compose config`)
│   ├── dto/, web/
├── config_editor/     ConfigFile, repository, service (must use PathValidationUtil), DTOs, controller
├── streaming/         LogStreamHandler, DockerEventStreamService, ComposeOutputStreamService, ExecSessionHandler
├── git/               Git credentials (encrypted PAT), remote checks via JGit
├── notification/      in-app notifications (HostStatusChangedEvent listener), per-user preferences, webhooks
├── retention/         retention settings, daily purge of audit log and notifications
├── auth/              User, Role (ADMIN/OPERATOR/VIEWER), UserService, AuthController, DTOs
├── permission/        Permission (per host/stack), PermissionService, PermissionEvaluator (@PreAuthorize)
└── audit/             AuditLogEntry, service, AuditAspect (AOP, @Auditable)
```

**Dependency direction:** `docker/` knows no other feature module. `compose/` and `streaming/` may use `docker/`, never the other way around. To be enforced with ArchUnit later.

**Principles:**
- **Never pass docker-java types outward.** Expose our own interfaces and DTOs.
- **Don't rebuild Compose.** Store the YAML, run `docker compose up/down/pull/config` as a subprocess, and stream the output live over WebSocket.
- **Two UI modes for Compose:** a form wizard (generates YAML from DTOs) and an advanced mode (raw YAML editor with Compose JSON schema validation in the frontend).
- **Every deploy creates a `StackVersion`** (history, rollback).
- **`spring.jpa.open-in-view: false`.** Build DTOs in the service layer.
- **Auditing via AOP** (`@Auditable` + `AuditAspect`), not by hand in every service.
- **Config editor writes make a backup first.**

## Security (non-negotiable)

- Docker socket access is effectively root on the host. Be strict with permissions and input validation.
- **Path traversal:** all file access (config editor, compose paths) goes through `PathValidationUtil`: canonical paths, checked against allowed base directories, symlinks considered. Test it with malicious paths (`../`, absolute paths, symlinks, URL-encoded variants).
- **Subprocesses:** never through a shell with concatenated strings. Use `ProcessBuilder` with an argument list. Never interpolate user input into commands.
- Never store secrets (TLS certificates, passwords) in plain text, and never log them.
- Passwords are only stored hashed: Argon2id via `DelegatingPasswordEncoder` (BCrypt rejects input over 72 bytes, the policy allows 128 characters).

## Database & Flyway

- Dependencies: `flyway-core`, **`flyway-database-postgresql`** (a separate artifact since Flyway 10, otherwise startup fails), `postgresql` (runtime).
- Migrations live in `classpath:db/migration`, named `V<number>__<description>.sql` (double underscore), one migration per feature.
- **Never edit a migration that has already been applied.** Fixes go into a new version.
- Test and seed data never goes into `db/migration/`. It goes into a separate dev-only location (e.g. `db/testdata/`).
- Postgres 16 has `gen_random_uuid()` built in. Don't add the `pgcrypto` extension.
- Primary keys are UUIDs.

## Testing

- JUnit 5 for unit tests. Testcontainers (Postgres, Docker) for integration tests.
- Write meaningful tests for behavior, not tests that just raise coverage.
- Security-relevant code (path validation, permissions, subprocess calls) always gets tests for the malicious cases.
- Browser e2e with Playwright (Chromium) against the Vite frontend with `/api` mocked. Conventions in `.claude/skills/next-task-e2e/SKILL.md`.
