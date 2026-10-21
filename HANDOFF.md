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
- [ ] Auth: JWT or server-side session?
- [x] **DECIDED:** Classic Spring MVC + WebSocket/STOMP (no WebFlux).
- [x] **DECIDED:** Project name is "Shipir". Base package is `dev.bethge.shipir`. Repo layout: `backend/` (Java/Maven) and `frontend/` (React/TypeScript).
- [ ] Where do stack directories live on the host, and how is the config editor's base directory configured?

## Recommended build order

1. **Project setup:** build tool, Spring Boot skeleton, package structure, `docker-compose.dev.yml`, Flyway V1–V4, Testcontainers baseline.
2. **`docker/` module + simple REST endpoints** (list, start, stop containers) against the local socket. A quick win that tests the basic connection.
3. **`auth` + `host`:** basic protection and host management before things get complex.
4. **`streaming`:** live logs over WebSocket early. Retrofitting it is painful.
5. **`compose`, incrementally:** first raw YAML upload + deploy (+ validation), then the form wizard on top, then versioning and rollback.
6. **`config_editor`** with `PathValidationUtil` and a backup before every write.
7. **`audit` + fine-grained `permission`.**
8. **Frontend** in parallel, feature by feature (`features/containers`, `features/stacks`, `features/hosts`, ...).

## Task for the first pass

1. Ask the open-decision questions above before generating any code.
2. Create the project skeleton (build order step 1), and make sure it builds and the tests run.
3. Implement step 2 (`docker/` module with container list/start/stop), including an integration test with Testcontainers.
4. At the end, briefly summarize what was built, what is tested and what comes next.

## Draft `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dockerpanel
    username: dockerpanel
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true
```

## Planned migrations

`V1__create_users`, `V2__create_docker_hosts`, `V3__create_stacks`, `V4__create_stack_versions`, `V5__create_config_files`, `V6__create_permissions`, `V7__create_audit_log`.

### Draft for V1–V4

```sql
-- V1__create_users.sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- V2__create_docker_hosts.sql
CREATE TABLE docker_hosts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    connection_type VARCHAR(20) NOT NULL,   -- LOCAL / TCP
    host VARCHAR(255),
    port INTEGER,
    tls_cert_ref VARCHAR(255),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- V3__create_stacks.sql
CREATE TABLE stacks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    host_id UUID NOT NULL REFERENCES docker_hosts(id) ON DELETE CASCADE,
    compose_file_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'STOPPED',
    created_by UUID NOT NULL REFERENCES users(id),
    last_deployed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (host_id, name)
);

-- V4__create_stack_versions.sql
CREATE TABLE stack_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stack_id UUID NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    yaml_content TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_stack_versions_stack_id ON stack_versions(stack_id);
```

### Rough model for the remaining tables

- `ConfigFile (id, stackId, relativePath, description)`
- `Permission (id, userId, hostId nullable, stackId nullable, permissionLevel)`
- `AuditLogEntry (id, userId, action, targetType, targetId, timestamp, details)`

## Review notes (not decided, raise before writing the migrations)

- The drafts use `TIMESTAMP` (without time zone). `TIMESTAMPTZ` is the usual recommendation in PostgreSQL to avoid time-zone ambiguity, and it maps cleanly to `Instant`/`OffsetDateTime` in Java.
- The `created_by` / `owner_user_id` foreign keys have no `ON DELETE` rule, so deleting a user who created stacks or versions will fail. That may be intended (keep history), but it should be a conscious choice.
