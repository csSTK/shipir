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
- [ ] Where do stack directories live on the host, and how is the config editor's base directory configured?

## Recommended build order

1. ~~**Project setup:**~~ **done** (epic shipr-yy7): build tool, Spring Boot skeleton, package structure, `docker-compose.dev.yml`, Flyway V1–V4, Testcontainers baseline.
2. **`docker/` module + simple REST endpoints** (list, start, stop containers) against the local socket. A quick win that tests the basic connection. The `docker/` abstraction (per-host clients, LOCAL socket + TCP/TLS, system info) is **done** (epic shipr-406); container list/start/stop endpoints remain.
3. **`auth` + `host`:** basic protection and host management before things get complex. Login, session auth and the first-start setup are **done** (epic shipr-a1h); `host` (environments CRUD, live overview, environment setup step) is **done** (epic shipr-406); user management remains.
4. **`streaming`:** live logs over WebSocket early. Retrofitting it is painful.
5. **`compose`, incrementally:** first raw YAML upload + deploy (+ validation), then the form wizard on top, then versioning and rollback.
6. **`config_editor`** with `PathValidationUtil` and a backup before every write.
7. **`audit` + fine-grained `permission`.**
8. **Frontend** in parallel, feature by feature (`features/containers`, `features/stacks`, `features/hosts`, ...).

## Task for the first pass

1. Ask the open-decision questions above before generating any code.
2. ~~Create the project skeleton (build order step 1), and make sure it builds and the tests run.~~ Done (shipr-yy7).
3. Implement step 2 (`docker/` module with container list/start/stop), including an integration test with Testcontainers.
4. At the end, briefly summarize what was built, what is tested and what comes next.

## Planned migrations

`V1__create_users`, `V2__create_docker_hosts`, `V3__create_stacks`, `V4__create_stack_versions`, `V5__create_config_files`, `V6__create_permissions`, `V7__create_audit_log`.

V1–V4 are implemented in `backend/src/main/resources/db/migration/` (drafts removed; see the decided review notes below).

### Rough model for the remaining tables

- `ConfigFile (id, stackId, relativePath, description)`
- `Permission (id, userId, hostId nullable, stackId nullable, permissionLevel)`
- `AuditLogEntry (id, userId, action, targetType, targetId, timestamp, details)`

## Review notes (decided)

- **Decided:** all timestamp columns use `TIMESTAMPTZ` (implemented in V1-V4). The drafts used `TIMESTAMP`; `TIMESTAMPTZ` is the usual recommendation in PostgreSQL to avoid time-zone ambiguity, and it maps cleanly to `Instant`/`OffsetDateTime` in Java.
- **Decided:** the `created_by` / `owner_user_id` foreign keys keep no `ON DELETE` rule (RESTRICT), so a user who owns or created hosts, stacks or versions cannot be deleted (history is kept). `stacks.host_id` and `stack_versions.stack_id` stay `ON DELETE CASCADE`.
