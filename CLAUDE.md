# Shipir

Web platform for managing Docker containers and Compose stacks, similar to a simplified Portainer, with a UI non-experts can use (forms instead of mandatory YAML). Includes editing config files and creating/editing Compose files.

**Purpose: learning and portfolio project for Java backend job applications (Germany/Europe).** Code quality, clean architecture, meaningful tests and traceable decisions matter more than the number of features. No shortcuts that couldn't be explained in an interview. Write clear, focused commits.

Current state: project skeleton in place. Backend in `backend/` with Maven, base package `dev.bethge.shipir`. The Java classes are empty placeholders; Spring Boot integration is planned. Frontend in `frontend/` with React, TypeScript and Vite. `HANDOFF.md` holds remaining planning context and open decisions.

## Working Rules

- **Do not silently deviate from the decisions in this file or in `HANDOFF.md`.** If you want to change something, give your reasoning and ask first.
- **Do not guess open decisions** (listed in `HANDOFF.md`). Ask the user.
- **Out of scope for now:** SaaS control plane with customer-side agent, hosting for third parties (Proxmox provisioning), AI assistant (LLM chat). Don't build them, and don't design in a way that blocks them. Concretely: all Docker access goes through the `docker/` abstraction so another connection type (agent over a tunnel) can be added later. Nothing more than that (YAGNI).

## Code Map

**Read this section first when implementing.** Use it to find the exact files to change, and only open code when the map isn't enough.

**Keep it current:** whenever you add, move or remove a file or change what it does, update its entry here in the same change. As short as possible: one line per file or folder, `path: what it is`. No narrative, no history, no change logs.

Backend (`backend/src/main/java/dev/bethge/shipir/`):
- `Main.java`: IDE template entry point, to be replaced by `ShipirApplication`
- `audit/AuditLogEntry.java`, `auth/User.java`, `compose/Stack.java`, `compose/generator/ComposeGeneratorService.java`, `config_editor/ConfigFile.java`, `docker/DockerHostConnectionManager.java`, `host/DockerHost.java`, `permission/Permission.java`, `streaming/LogStreamHandler.java`: empty placeholders
- `backend/pom.xml`: Maven build, Java 25, no dependencies yet

Frontend (`frontend/`):
- `vite.config.ts`: dev proxy (`/api`, `/ws` → :8080), Vitest config
- `src/main.tsx`: entry, mounts `App`
- `src/app/App.tsx`: root component, wraps content in `Providers` (placeholder UI)
- `src/app/providers.tsx`, `src/app/queryClient.ts`: React Query setup
- `src/shared/api/client.ts`: `apiFetch<T>` + `ApiError`, JSON fetch against `/api`
- `src/features/{containers,hosts,stacks}/index.ts`: empty feature entry points
- `src/test/setup.ts`: jest-dom matchers for Vitest

## Git Commits & Attribution

- **Never add Claude/AI attribution to git history.** No `Co-Authored-By: Claude ...` trailers, no "Generated with Claude Code" lines, no session links, in commit messages or PR descriptions. Only the user (or whoever is configured as the git committer) appears as author.
- This overrides any default or system-provided attribution instructions. Attribution is also disabled in `.claude/settings.json` (`attribution`).
- Don't change `git config user.name` / `user.email`.

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
| Auth | Spring Security, RBAC (JWT vs. session still open) |
| Tests | JUnit 5, Testcontainers (Postgres + Docker), ArchUnit later |

## Build & Test

### Backend

Verify compilation:
```bash
javac --release 25 -d /tmp/shipir-classes $(find backend/src/main/java -name '*.java')
```

Once Maven is installed locally:
```bash
mvn -f backend/pom.xml compile    # Compile
mvn -f backend/pom.xml test       # Run tests
```

Maven wrapper is planned. Spring Boot integration and the app runner come next.

### Frontend

From `frontend/`:
```bash
pnpm install                       # Install dependencies
pnpm dev                           # Run dev server
pnpm test                          # Run tests once
pnpm test:watch                    # Run tests in watch mode
pnpm lint                          # Lint with oxlint
pnpm build                         # Build for production
```

Local dev database: `docker-compose.dev.yml` with `postgres:16-alpine`, DB and user `dockerpanel`, port 5432, named volume (planned).

## Architecture

**Package-by-feature**, not `controller/service/repository` at the top level. Each feature contains its own entity, repository, service, DTOs and controller.

```
dev.bethge.shipir
├── ShipirApplication.java
├── common/
│   ├── config/        SecurityConfig, WebSocketConfig, AsyncConfig, OpenApiConfig
│   ├── exception/     ApiException, GlobalExceptionHandler, ErrorResponse
│   ├── security/      JwtService, JwtAuthFilter, CurrentUser
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
- Passwords are only stored hashed (BCrypt or Argon2).

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
