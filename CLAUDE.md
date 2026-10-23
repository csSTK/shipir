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
- `audit/AuditLogEntry.java`, `compose/Stack.java`, `compose/generator/ComposeGeneratorService.java`, `config_editor/ConfigFile.java`, `permission/Permission.java`, `streaming/LogStreamHandler.java`: empty placeholders
- `host/{DockerHost,ConnectionType,DockerHostRepository}.java`: environment entity (TLS columns are ciphertext), LOCAL/TCP enum, repository
- `host/{HostController,HostService,HostException,HostExceptionHandler}.java`: `/api/hosts` list/create/connection-test/rename/delete (create pings first: 422 CONNECTION_FAILED; 400/409 codes via `HostException`)
- `host/{HostOverviewService,HostOverviewProperties}.java`: `GET /api/hosts/overview` (in `HostController`): parallel live `info` per host on virtual threads, shared deadline `shipir.host.overview-timeout` (5s), failures -> DOWN + error code (docker codes, TIMEOUT, DECRYPTION_FAILED, ENCRYPTION_KEY_MISSING, UNKNOWN)
- `host/{HostHealthCheckScheduler,HostStatusChangedEvent}.java`: `@Scheduled` (`shipir.host.health-check-interval`=60s, `health-check-enabled`=true; test properties disable it) reuses `HostOverviewService`, in-memory last status per host (first observation = baseline), publishes `HostStatusChangedEvent` on UP/DOWN flips, forgets deleted hosts
- `common/config/SchedulingConfig.java`: `@EnableScheduling`
- `host/HostEndpointResolver.java`: public, builds `DockerEndpoint` from a `DockerHost` (decrypts TLS)
- `host/HostEncryptionStartupCheck.java`: fails startup if TCP hosts exist without `shipir.encryption-key`
- `host/dto/*.java`: create/test/update requests (toString hides PEMs), `HostResponse` (`tls` boolean only), `HostOverviewResponse` (stats null when DOWN)
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
- `common/config/PasswordEncoderConfig.java`: Argon2id `PasswordEncoder` bean (DelegatingPasswordEncoder, bcrypt match-only)
- `common/config/ClockConfig.java`: `Clock` bean (system UTC)
- `backend/src/test/resources/application.properties`: test initial-admin password
- `backend/src/test/java/dev/bethge/shipir/auth/AuthEndpointsTest.java`: MockMvc + Testcontainers: login/me/logout, CSRF, session fixation, identical 401, JSON 401
- `backend/src/test/java/dev/bethge/shipir/auth/PasswordPolicyTest.java`, `PasswordChangeTest.java`, `SetupEnvironmentStepTest.java`: policy rules (unit); setup gate + password change flow, PASSWORD/ENVIRONMENT steps (MockMvc, own DB via `@DirtiesContext`)
- `backend/src/test/java/dev/bethge/shipir/auth/PasswordResetEndpointsTest.java`: reset endpoints (MockMvc, capturing sender, CSRF, errors, toString)
- `backend/src/test/java/dev/bethge/shipir/auth/InitialAdminSeeder{,Startup}Test.java`: seeding, no-op rerun, missing password, no password in logs
- `backend/src/test/java/dev/bethge/shipir/auth/PasswordResetServiceTest.java`: reset flow (Testcontainers, capturing sender, mutable clock, hash-only row, log capture)
- `docker/{DockerEndpoint,DockerErrorCode,DockerConnectionException,DockerProperties}.java`: own types: `Local|Tcp(TlsMaterial, toString hides PEMs)`, UNREACHABLE/TIMEOUT/TLS_ERROR/PERMISSION_DENIED/INVALID_TLS_MATERIAL, `shipir.docker.{local-socket,connect-timeout,read-timeout}`
- `docker/DockerHostConnectionManager.java`: cached docker-java client per host id (`client`/`createClient` internal to docker/), `evict(hostId)`, `localEndpoint()`, `readTimeout()`
- `docker/{DockerClientFactory,DefaultDockerClientFactory}.java`: package-private seam + httpclient5 factory (Local socket; Tcp, with `PemSslConfig` when TLS material is set)
- `docker/tls/{PemSslConfig,TlsMaterialParser,TlsMaterialValidator}.java`: in-memory PEM (CA, client chain, RSA/EC key PKCS#8/PKCS#1/SEC1) to docker-java `SSLConfig`; invalid PEM -> `DockerConnectionException(INVALID_TLS_MATERIAL)`; `TlsMaterialValidator.validate` for host/
- `docker/client/DockerSystemService{,Impl}.java`: `ping(endpoint)` (uncached, never throws) and `info(hostId, endpoint)`; enforces read timeout itself (Unix transport ignores it)
- `docker/client/Container{Service,ServiceImpl}.java`: `listContainers` (all states) and `listStacks` (grouped by `com.docker.compose.project`, RUNNING/PARTIAL/STOPPED); `docker/client/DockerCallSupport.java`: package-private read-timeout deadline + `DockerConnectionException` mapping, shared by docker/client services
- `docker/dto/{ContainerDto,StackSummaryDto}.java`: container (health from status text, ports `pub:priv/type`, compose labels) and stack summary
- `docker/dto/{ConnectionTestResult,DockerSystemInfoDto}.java`: ping result, host overview counts incl. healthy/unhealthy
- `backend/src/test/java/dev/bethge/shipir/docker/**`: endpoint, manager (fake factory), properties defaults, no-docker-java-in-API check, service unit (missing/hanging socket), Docker integration tests, dind TLS integration test, PEM parsing (`tls/PemSslConfigTest`, `tls/TestPki` generates certs)
- `backend/pom.xml`: Maven build, Java 25, Spring Boot 4.1.1 parent, docker-java 3.7.1 (core + httpclient5), starters web/websocket/security/validation/data-jpa/flyway/test, spring-security-test, flyway-database-postgresql, postgresql driver, Testcontainers (test)
- `backend/mvnw`, `backend/.mvn/wrapper/`: Maven wrapper
- `backend/Dockerfile`: multi-stage image: Temurin 25 build (tests skipped), JRE alpine runtime, user uid 10001, env-only config
- `backend/.dockerignore`: excludes `target/`, `.env`, keys, IDE and git files from the image build
- `backend/src/main/resources/application.yml`: app name, datasource (`DB_HOST/PORT/NAME/USER` with local defaults, `${DB_PASSWORD}` no default), JPA validate/no OSIV, Flyway
- `backend/src/main/resources/db/migration/V{1..4}__create_{users,docker_hosts,stacks,stack_versions}.sql`, `V5__add_users_password_changed_at.sql`, `V6__add_docker_hosts_tls_columns.sql`, `V7__create_password_reset_codes.sql`, `V9__create_notifications.sql`: Flyway schema (TIMESTAMPTZ, FKs to users RESTRICT, host/stack FKs CASCADE; V5 NULL = first-start setup pending; V6 docker_hosts: encrypted TLS BYTEA columns, connection shape/TLS/name constraints; V7 password_reset_codes: hashed codes, attempt counter, CASCADE with user; V8 audit_log: username snapshot, outcome check, user FK SET NULL, indexes on occurred_at and (action, occurred_at)); V9 notifications: type CHECK HOST_DOWN/HOST_UP, host_id SET NULL keeps host_name, preferences PK (user_id, type)
- `backend/src/test/java/dev/bethge/shipir/ShipirApplicationTests.java`: context-load + Postgres 16 test
- `backend/src/test/java/dev/bethge/shipir/DatasourcePropertiesTest.java`: resolves application.yml datasource defaults and `DB_*` overrides (no context, no DB)
- `backend/src/test/java/dev/bethge/shipir/migration/SchemaMigrationTest.java`: V1-V9 schema test (timestamptz, unique, cascade, restrict, password_changed_at, docker_hosts checks, password_reset_codes columns/checks/cascade, audit_log columns/outcome check/user SET NULL/indexes, notifications/notification_preferences), `@Transactional` rollback
- `backend/src/test/java/dev/bethge/shipir/PostgresTestConfiguration.java`: reusable `@TestConfiguration`, `@ServiceConnection` postgres:16-alpine; integration tests `@Import` it

Frontend (`frontend/`):
- `vite.config.ts`: dev proxy (`/api`, `/ws` → :8080), Vitest config
- `src/main.tsx`: entry, mounts `App`
- `src/app/App.tsx`: root component, `Providers` + `BrowserRouter` + auth gate (loading, `LoginPage` on 401, `SetupPage` while `setupRequired`, routes `/` overview and `/environments/:id`, sign out)
- `src/app/providers.tsx`, `src/app/queryClient.ts`: React Query setup
- `src/shared/api/client.ts`: `apiFetch<T>` + `ApiError` (status, code, violations, details), JSON fetch against `/api`, same-origin credentials, `X-XSRF-TOKEN` from cookie on non-GET
- `src/features/auth/{api.ts,LoginPage.tsx,LoginPage.css,index.ts}`: `useCurrentUser/useLogin/useLogout/useChangePassword/useRequestPasswordReset/useConfirmPasswordReset` hooks, login form with `Forgot password?` view switch + success notice
- `src/features/auth/PasswordResetPage.{tsx,css,test.tsx}`: reset flow (username -> neutral message + 6-digit code, new password with live rules; INVALID_RESET_CODE / PASSWORD_POLICY inline)
- `src/features/auth/{SetupPage.tsx,SetupPage.css,passwordPolicy.ts}`: first-start setup, 2 steps with indicator (`step` prop = `setupStep`: PASSWORD form, ENVIRONMENT embeds hosts `EnvironmentForm` then refetches me); client mirror of backend password rules
- `src/app/layout/{AppShell,Sidebar,UserMenu}.tsx` (+ `.css`, `AppShell.test.tsx`): app shell: top bar (brand, user menu with Settings -> /account and Sign out), sidebar (Environments, Notifications, ADMIN-only Settings group), narrow-screen toggle
- `src/app/layout/PlaceholderPages.tsx`: `PlaceholderPage`, `NotAllowedPage` (non-ADMIN on /settings/*)
- `e2e/shell.spec.ts`: sidebar/user menu navigation, viewer not allowed on settings
- `src/features/notifications/{api.ts,NotificationsPage.tsx,.css,index.ts}`: `useNotifications(unreadOnly)`, `useUnreadCount` (30 s + focus), `useMarkRead/useMarkAllRead`; `/notifications` page (assumed API: Page `{content,totalElements}`, `{count}`, POST `/{id}/read`, `/read-all`); Sidebar shows the unread badge
- `src/features/{containers,stacks}/index.ts`: empty feature entry points
- `src/features/hosts/api.ts`: host types, `HOSTS_KEY`/`HOSTS_OVERVIEW_KEY`, `useHosts/useHostsOverview` (30 s + focus refetch, `HostOverview`), `useCreateHost/useTestConnection/useRenameHost/useDeleteHost` (create/delete also invalidate current user)
- `src/features/hosts/EnvironmentForm.{tsx,css}`: Local / Remote (TCP+TLS) form, PEM file pickers (names only shown), connection test, inline server errors; props `onCreated`, `submitLabel`
- `src/features/hosts/{EnvironmentsOverview.tsx,.css}`: home screen: environment rows (status, stats), search, refresh, skeleton/error/empty states, ADMIN add dialog, renders `EnvironmentActions` per row for ADMIN
- `src/features/hosts/EnvironmentActions.{tsx,css}`: ADMIN row Rename/Delete buttons + dialogs (HOST_NAME_TAKEN inline, delete warns about stacks)
- `src/features/hosts/EnvironmentPage.tsx`: `/environments/:id` placeholder with back link
- `src/features/hosts/format.ts`: memory/plural formatting, overview error code to message
- `src/features/hosts/index.ts`: exports form, overview, page, hooks, types
- `src/test/setup.ts`: jest-dom matchers for Vitest
- `playwright.config.ts`: Playwright config (Chromium, `e2e/` dir, starts Vite dev server)
- `tsconfig.e2e.json`: type-checks `e2e/` and `playwright.config.ts` (referenced from `tsconfig.json`)
- `e2e/fixtures/api.ts`: Playwright `test` with auto `api` fixture: mocks `/api/**` per method+path, fails on unmocked calls (own spec: `api.spec.ts`)
- `e2e/app.spec.ts`: smoke spec, Shipir heading (signed in)
- `e2e/auth.spec.ts`: login happy path + sign out, wrong password
- `e2e/environments.spec.ts`: overview list/search, viewer vs admin add, admin rename/delete, taken name, operator no actions, error, placeholder navigation
- `e2e/password-reset.spec.ts`: forgot password happy path, invalid code, unknown user neutral message
- `e2e/setup.spec.ts`: setup flow (password -> environment step -> app), reload on step 2, connection failure, server error, no setup when done
- `Dockerfile`: multi-stage image (pnpm build, nginx-unprivileged on :8080; `BACKEND_URL`, `NGINX_RESOLVER`)
- `nginx/default.conf.template`: envsubst nginx config: SPA fallback, asset caching, `/api` + `/ws` proxy (re-resolving upstream)
- `.dockerignore`: keeps node_modules, dist, test output out of the build context

Repo root:
- `docker-compose.dev.yml`: local PostgreSQL 16 for development (healthcheck, `DB_*` from `.env`)
- `docker-compose.yml`: hosting stack, backend + frontend (`ghcr.io/csstk/shipir-*` or local build), external PostgreSQL, only frontend port published, passes `SHIPIR_INITIAL_ADMIN_PASSWORD` and `SHIPIR_ENCRYPTION_KEY`, mounts Docker socket (`group_add: DOCKER_GID`)
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
