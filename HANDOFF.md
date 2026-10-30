# Handoff: planning context and next steps

This file holds the parts of the original planning handoff that aren't permanent project rules. Those rules (stack, architecture, security, Flyway conventions) are in [`CLAUDE.md`](CLAUDE.md). Once the open decisions are made and the build order is turned into beads, most of this file can be deleted.

## Rejected alternatives

| Option | Why rejected |
|---|---|
| Python / FastAPI | Technically more convenient, but Java is more relevant for the portfolio |
| Go | No prior experience |
| WebFlux (reactive) | Not rejected outright. Recommendation so far is classic Spring MVC + WebSocket/STOMP, with WebFlux only if a clear reason comes up |

## Open decisions (ask the user, do not guess)

- [x] **DECIDED:** Maven, with the Maven wrapper (`backend/mvnw`).
- [x] **DECIDED:** Java 25 (LTS), Spring Boot 4.1.1 (pinned via `spring-boot-starter-parent`).
- [x] **DECIDED:** Auth uses a server-side Spring Security session (HttpOnly cookie, cookie-based CSRF token for the SPA), not JWT. Reason: instant revocation on logout/password change, simplest for a same-origin SPA behind nginx, STOMP reuses the cookie.
- [x] **DECIDED:** Password hashing uses Argon2id (`DelegatingPasswordEncoder`, `{argon2}` default, `{bcrypt}` match-only). Reason: Spring Security 7 BCrypt rejects passwords over 72 bytes, the password policy allows 15-128 characters.
- [x] **DECIDED:** Classic Spring MVC + WebSocket/STOMP (no WebFlux).
- [x] **DECIDED:** Project name is "Shipir". Base package is `dev.bethge.shipir`. Repo layout: `backend/` (Java/Maven) and `frontend/` (React/TypeScript).
- [x] **DECIDED:** Database and user are named `shipir`. The connection is configured via `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER` (defaults `localhost`/`5432`/`shipir`/`shipir`) and `DB_PASSWORD` (no default), shared by `application.yml` and `docker-compose.dev.yml`. Reason: a native Postgres on 5432 must be able to coexist with the Docker dev DB (`DB_PORT=5433`).
- [x] **DECIDED (shipr-r3w):** 'Stack' is renamed to 'deployment' everywhere (UI, API, code, DB via V16); a single deploy execution is a 'run' (`runId`). The `shipir.stacks.base-dir` / `SHIPIR_STACKS_BASE_DIR` setting, on-disk layout and compose project names are kept. Historic `STACK_*` audit rows are not rewritten.
- [x] **DECIDED (shipr-e42):** Stack directories live under `shipir.stacks.base-dir` (env `SHIPIR_STACKS_BASE_DIR`, `/data/stacks` on the `shipir-stacks` volume in hosting), one dir per `<host-id>/<stack-name>`; the DB (`stack_versions`) is the source of truth and files are rewritten on deploy.
- [x] **DECIDED (shipr-9sm):** AI chat assistant is in scope (was out of scope). Decisions are recorded in CLAUDE.md under "AI assistant".
- [x] **DECIDED (shipr-6ta):** The config editor works inside managed deployment directories (`<stacks-base>/<hostId>/<name>/`), no separate setting. `compose.yaml`/`.env` are excluded and the Git checkout `repo/` is read-only. Backups live in `<deployment dir>/.shipir-backups/<sha256 of path>/` (newest 10 per file, `shipir.config-editor.backups-per-file`), files are UTF-8 text up to 1 MiB, no separate `config_files` table (the directory is the source of truth), so the planned `create_config_files` migration is dropped.
- [x] **DECIDED (shipr-006w):** The AI assistant gets read-only tools for config files (list and read, same excludes as the config editor, `compose.yaml`/`.env` excluded entirely, env-like file contents never exposed, their names may appear in the tree as in the UI) and an ADMIN-only read tool for access grants. Write tools (config file edits, backup restore, permission grant/change/revoke) stay out of scope. Reason: both features are now built, and a model that changes files or access rights is a prompt-injection risk even behind Confirm. Recorded in CLAUDE.md under "AI assistant".
- [x] **DECIDED (shipr-bbzh.4):** OPERATORs may open container consoles, following the existing permission grants: OPERATE on the host or on the managed deployment that owns the container (VIEW only, no grant, VIEWER: refused; containers of other deployments/hosts are 404). It is OFF by default (`shipir.streaming.exec.operator-enabled=false`); ADMIN is unchanged. Open consoles end (ACCESS_REVOKED) when the grant or role goes away; EXEC_START/EXEC_END audit rows name the role. Consoles stay session-only: API-token requests always get 403. The UI reads the flag from `GET /api/exec/capabilities`.
- [x] **DECIDED (shipr-p4vd):** Container file access stays on the Docker archive API ("no exec"), with one narrow exception: when listing a directory hits the scan limit (`shipir.container-files.max-scan-bytes`), `ContainerFileServiceImpl.list` falls back once to a single-directory `find` via docker exec (argument list, no shell, normalised absolute path, NUL-separated output capped at 10,000 entries / 4MB). If exec is not possible (no `find`, distroless, stopped container) the result stays DIRECTORY_TOO_LARGE. Download and upload never use exec. Fallback entries carry name/type only (size 0, no mtime/mode).

## Recommended build order

1. ~~**Project setup:**~~ **done** (epic shipr-yy7): build tool, Spring Boot skeleton, package structure, `docker-compose.dev.yml`, Flyway V1–V4, Testcontainers baseline.
2. **`docker/` module + simple REST endpoints** (list, start, stop containers) against the local socket. A quick win that tests the basic connection. The `docker/` abstraction (per-host clients, LOCAL socket + TCP/TLS, system info) is **done** (epic shipr-406); read-only lists of containers, Compose stacks, images, networks and volumes are **done** (epic shipr-zlq); container lifecycle actions (start/stop/restart/pause/unpause/kill) are **done** (epic shipr-wju).
3. **`auth` + `host`:** basic protection and host management before things get complex. Login, session auth and the first-start setup are **done** (epic shipr-a1h); `host` (environments CRUD, live overview, environment setup step) is **done** (epic shipr-406); user management is **done** (epic shipr-9uk).
4. **`streaming`:** live logs over WebSocket early. Retrofitting it is painful. Compose output streaming is **done** (deploy epics); live container logs are **done** (epic shipr-9gf); the exec terminal is **done** (epic shipr-cql); Docker events are **done** (epic shipr-7ww).
5. **`compose`, incrementally:** first raw YAML upload + deploy (+ validation), then the form wizard on top, then versioning and rollback.
6. **`config_editor`** with `PathValidationUtil` and a backup before every write.
7. **`audit` + fine-grained `permission`.** `audit` (log, `@Auditable`, ADMIN page) is **done** (epic shipr-46j), together with in-app notifications, webhooks and retention rules; per-host/per-deployment `permission` is **done** (epic shipr-cbw).
8. **Frontend** in parallel, feature by feature (`features/containers`, `features/deployments`, `features/hosts`, ...).

## Task for the first pass

1. Ask the open-decision questions above before generating any code.
2. ~~Create the project skeleton (build order step 1), and make sure it builds and the tests run.~~ Done (shipr-yy7).
3. Implement step 2 (`docker/` module with container list/start/stop), including an integration test with Testcontainers.
4. At the end, briefly summarize what was built, what is tested and what comes next.

## Planned migrations

Implemented in `backend/src/main/resources/db/migration/`: `V1__create_users`, `V2__create_docker_hosts`, `V3__create_stacks`, `V4__create_stack_versions`, `V5__add_users_password_changed_at`, `V6__add_docker_hosts_tls_columns`, `V7__create_password_reset_codes`, `V8__create_audit_log`, `V9__create_notifications`, `V10__create_notification_webhooks`, `V11__create_retention_settings`, `V12__create_git_credentials`, `V13__extend_stacks_for_deploy`, `V14__add_stack_versions_deployed_at`, `V15__add_stacks_git_poll_state`, `V16__rename_stacks_to_deployments` `V17__create_ai_chat` `V18__add_users_disabled_at` (drafts removed; see the decided review notes below).

Also implemented: `V19__create_registry_credentials`, `V20__create_ai_provider_configs`, `V21__add_docker_hosts_single_local_index`, `V22__create_permissions`. No further migrations planned.

Epic shipr-m15h (managed reverse proxy): `V39__create_proxy_tables` (`proxy_instances`, `proxy_certificates`, `proxy_routes`, all per host, CASCADE with the host) is implemented. `V40__create_proxy_custom_config_versions` (`proxy_custom_config_versions`: backups of the custom proxy file, CASCADE with the proxy) is implemented.

## Review notes (decided)

- **Decided:** all timestamp columns use `TIMESTAMPTZ` (implemented in V1-V4). The drafts used `TIMESTAMP`; `TIMESTAMPTZ` is the usual recommendation in PostgreSQL to avoid time-zone ambiguity, and it maps cleanly to `Instant`/`OffsetDateTime` in Java.
- **Decided:** the `created_by` / `owner_user_id` foreign keys keep no `ON DELETE` rule (RESTRICT), so a user who owns or created hosts, stacks or versions cannot be deleted (history is kept). `stacks.host_id` and `stack_versions.stack_id` stay `ON DELETE CASCADE`.
