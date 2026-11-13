# Shipir

Web platform for managing Docker containers and Compose deployments, similar to a simplified Portainer, with a UI non-experts can use (forms instead of mandatory YAML). Includes editing config files and creating/editing Compose files.

**Purpose: learning and portfolio project for Java backend job applications (Germany/Europe).** Code quality, clean architecture, meaningful tests and traceable decisions matter more than the number of features. No shortcuts that couldn't be explained in an interview. Write clear, focused commits.

Current state: project skeleton in place. Backend in `backend/` with Maven and Spring Boot, base package `dev.bethge.shipir`. Most feature classes are still empty placeholders. Frontend in `frontend/` with React, TypeScript and Vite. `HANDOFF.md` holds remaining planning context and open decisions.

## Working Rules

- **Do not silently deviate from the decisions in this file or in `HANDOFF.md`.** If you want to change something, give your reasoning and ask first.
- **Do not guess open decisions** (listed in `HANDOFF.md`). Ask the user.
- **Out of scope for now:** SaaS control plane with customer-side agent, hosting for third parties (Proxmox provisioning). Don't build them, and don't design in a way that blocks them. Concretely: all Docker access goes through the `docker/` abstraction so another connection type (agent over a tunnel) can be added later. Nothing more than that (YAGNI).

## Database Access

- **Reading is allowed** (e.g. `psql -c "SELECT ..."`, `\d`, `pg_dump`).
- **Writing never happens without the user's explicit consent.** That covers `INSERT`/`UPDATE`/`DELETE`, DDL, running migrations or starting the app against a real database (`spring-boot:run` runs Flyway), restoring dumps, SQL from files or stdin, and deleting database volumes (`docker compose down -v`). Ask first, say exactly what will be written and to which database, and wait for a yes.
- Throwaway Testcontainers databases in tests (`./backend/mvnw test`/`verify`) and the beads tracker (`bd`) are exempt.
- Sub-agents never write to a database. If a task needs a write, they stop and report it so the orchestrator can ask the user.
- Enforced by `.claude/hooks/db-write-guard.py` (PreToolUse on Bash): suspected writes always trigger a permission prompt. It can't see writes hidden inside scripts or app code, so the rule applies regardless of the hook.

## Frontend UI

- **All new or changed frontend UI follows the `apple-design` skill** (`.claude/skills/apple-design/SKILL.md`). Read it before building or reviewing UI: instant press feedback, springs for anything the user can touch, restraint, typography, materials, and `prefers-reduced-motion`.
- **Visual language:** use only the design tokens (`frontend/src/shared/styles/tokens.css`, rules in the "Shipir visual language" section of the skill); no hardcoded colors, sizes, radii or durations in feature CSS.
- New UI libraries (e.g. `motion` for springs) are added only when a bead calls for them, not ad hoc.
- Implementer agents skip the skill's "Initial Response" section (it is meant for interactive chats). Reviews check UI against the skill and the tokens.

## Code Map

Per-file map of the codebase: `docs/CODE_MAP.md`. Read or grep it when you need to find files, not up front; open code only where the map isn't enough.

**Keep it current:** whenever you add, move or remove a file or change what it does, update its entry in `docs/CODE_MAP.md` in the same change. One line per file or folder: `path: what it is`.

## Git Commits & Attribution

- **Never add Claude/AI attribution to git history.** No `Co-Authored-By: Claude ...` trailers, no "Generated with Claude Code" lines, no session links, in commit messages or PR descriptions. Only the user (or whoever is configured as the git committer) appears as author.
- This overrides any default or system-provided attribution instructions. Attribution is also disabled in `.claude/settings.json` (`attribution`).
- Don't change `git config user.name` / `user.email`.

## Branches

- `dev`: default branch. All work (`/next-task*` branches, each in its own worktree `../shipir-<id>`) branches from `origin/dev` and is pushed back to `dev` fast-forward. Never switch branches in a checkout another session or agent may be using.
- `prd`: release branch, only ever a fast-forward of `dev`. Release: `git push origin dev:prd`, wait until `release.yml` is green, then tag `v*` on that commit (`tag.yml` re-tags the tested image). If `tag.yml` failed because the image was missing, re-run it (`gh run rerun <id>`). Never commit to `prd` directly; GitHub blocks force-push and deletion.
- Agents never push `prd` or tags without the user asking.

## Model Policy

- **Planning uses Opus.** Planning means architecture and stack decisions, breaking work into epics and tasks, writing issue descriptions and acceptance criteria, and setting dependencies (`text-to-task`). Run planning sessions on Opus (`/model opus`).
- **Orchestration uses Opus.** The `/next-task*` skills coordinate implementation and do not plan. Run them on Opus (`/model opus`). Wave planning in `next-task-plan` part 2 (file sets, waves, hand-off prompts) is orchestration, so it runs on Opus too.
- **Implementation is done only by Sonnet or Haiku sub-agents.** Opus does not write production code. Delegate each implementation task to the `implementer-sonnet` or `implementer-haiku` subagent (`.claude/agents/`), passing the bead ID.
- **Pick the implementation model by scope:**
  - **Haiku**: small, well-specified, low-risk changes, such as boilerplate, renames, simple DTOs or entities, config tweaks, docs, and straightforward tests. Never for auth, permissions, remote host access, the audit log, subprocess execution, path handling or streaming.
  - **Sonnet**: everything else, such as multi-file features, non-trivial logic, debugging, refactors, concurrency/streaming, and anything touching auth, permissions, remote host access, the audit log, subprocess execution or path handling.
  - When unsure, use Sonnet.
- The same rules are given to the pipeline agents in `models.implementers.rules` of `.claude/wavework.json`; keep both in sync.
- Before starting implementation, a task should have a bead with clear acceptance criteria. If it doesn't, stop and plan it (on Opus) first.

## Pipeline (wavework)

The `/next-task*` skills, the implementer agents, `.claude/scripts/red-green-check*.sh` and the pipeline dashboard are generated by [wavework](https://www.npmjs.com/package/wavework), pinned exactly in the root `package.json` (devDependency, currently `0.1.2`; root `node_modules/` is gitignored). The root `package.json` is tooling only: no workspaces, `frontend/` keeps its own `pnpm-lock.yaml`, the backend is Maven.

- **Config:** `.claude/wavework.json` (base branch, check commands per area, model rules, code map, UI skill, findings epics, dashboard). Keep `mvnw` and `vitest` in the `focused` commands: the red-green script detects the test layout from those words.
- **Generated, do not hand-edit:** `.claude/skills/{next-task*,text-to-task,watch-ci}/`, `.claude/agents/implementer-*.md`, `.claude/scripts/red-green-check*.sh`, `.claude/wavework/dashboard/`, the dashboard hook entries in `.claude/settings.json` and `.claude/wavework.lock.json`. To change behavior, edit `.claude/wavework.json` or fix it upstream in wavework (and bump the pin).
- **Update:** bump the version in `package.json`, `npm install`, then `npx wavework update`. `--dry-run` shows the plan. Every run first prints `wavework: repository <root> (<name>)`; it works from any worktree. It overwrites files you did not touch. For a file that differs from the lock it keeps yours, writes `<file>.wavework-new` and exits 2: merge it (or take it with `mv`), delete the `.wavework-new` file, run `update` again until it prints "nothing to do".
- **Not generated, Shipir-owned:** `.claude/skills/apple-design/`, `.claude/hooks/db-write-guard.py`, the permissions, `bd prime` hook and attribution settings in `.claude/settings.json`. Project rules the generic skills point to live in this file (Build & Test, Testing, Dependency updates below).

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

| Area              | Decision                                                                                                                                            |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Backend           | Java (current LTS), Spring Boot                                                                                                                     |
| Frontend          | React: feature-oriented folders (`features/containers`, `features/deployments`, `features/hosts`, ...), Monaco Editor for YAML/configs, React Query |
| Database          | PostgreSQL 16                                                                                                                                       |
| Migrations        | Flyway. Hibernate never changes the schema (`ddl-auto: validate`)                                                                                   |
| Docker access     | `docker-java`, wrapped behind our own interfaces                                                                                                    |
| Compose execution | `docker compose` as a subprocess (`ProcessBuilder`). No reimplementation of the Compose spec                                                        |
| Real-time         | WebSocket with STOMP (logs, events, compose output, exec terminal)                                                                                  |
| Auth              | Spring Security, server-side session (HttpOnly cookie + CSRF), RBAC                                                                                 |
| Tests             | JUnit 5, Testcontainers (Postgres + Docker), ArchUnit later                                                                                         |

## Build & Test

### Backend

Maven wrapper, no local Maven needed:

```bash
./backend/mvnw -f backend/pom.xml compile   # Compile
./backend/mvnw -f backend/pom.xml test      # Run tests
./backend/mvnw -f backend/pom.xml verify    # Full build incl. tests
```

**Docker must be running for backend tests** (Testcontainers starts a throwaway Postgres 16). No local Postgres or `DB_PASSWORD` needed. If it is not running, report that; don't skip the tests.

**Focused backend run:** `./backend/mvnw -f backend/pom.xml test -Dtest='dev.bethge.shipir.<pkg>.**' -Dsurefire.failIfNoSpecifiedTests=false`. Run the full backend suite instead if `pom.xml`, `application*.yml`, `db/migration/`, `common/`, a test base class or a test configuration changed.

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

**Focused frontend run:** `pnpm vitest run <changed dirs, e.g. src/features/stacks src/shared/api>`, `pnpm lint` and `pnpm exec tsc -b` (the pipeline config runs the last two as `lint`). Run the full `pnpm test` instead if `package.json`, `pnpm-lock.yaml`, `vite.config.ts`, `src/app/` or `src/test/` changed. Focused e2e: `pnpm e2e e2e/<feature>.spec.ts`; run the full e2e suite if `src/app/`, `src/shared/` or `e2e/fixtures/` changed.

**Dependency updates** (the `/next-task` deps step follows this):

- Frontend: `pnpm install --frozen-lockfile` checks the lockfile (if it fails, `pnpm install` and commit the lockfile); `pnpm update` stays within the semver ranges, never `--latest`; `pnpm outdated` lists the majors not taken.
- Backend: `./backend/mvnw -f backend/pom.xml -q dependency:resolve test-compile`; report with `./backend/mvnw -f backend/pom.xml -q versions:display-dependency-updates versions:display-plugin-updates`; apply only patch and minor bumps of versions pinned directly in `pom.xml`. Leave versions managed by the Spring Boot parent alone; bumping the parent itself, even a minor, is a separate decision.
- Commit only `package.json`, `pnpm-lock.yaml`, `pom.xml` as `Update dependencies (patch/minor)`. Majors and parent bumps are reported, never applied.

Local dev database: `docker compose -f docker-compose.dev.yml up -d` (`postgres:16-alpine`, named volume, `DB_NAME`/`DB_USER`/`DB_PORT`/`DB_PASSWORD` from `.env`, defaults `shipir`/`shipir`/`5432`). The app reads the same `DB_*` variables from its environment (export `.env` first, the app does not read `.env`); use `DB_PORT=5433` if a native Postgres occupies 5432.

Pipeline dashboard (dev tooling, not part of the product): installed by wavework in `.claude/wavework/dashboard/` (Node built-ins only, committed so worktrees have the recorder; see "Pipeline (wavework)"). A SessionStart hook starts it at `http://127.0.0.1:4690` (`PIPELINE_DASHBOARD_PORT`); or run it in the foreground with `npx wavework dashboard`. Run data lives in `.git/pipeline-runs/` (`WAVEWORK_PIPELINE_STORE` overrides it in tests). Its look uses `frontend/src/shared/styles/tokens.css` (`dashboard.tokensFile`). Its tests live in the wavework repo; the red-green script's own test runs with `bash .claude/scripts/red-green-check.test.sh`.

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
│   ├── Deployment, DeploymentVersion (entities), repositories, DeploymentService, DeploymentRunService
│   ├── file/          ComposeFileRepository          (reads/writes YAML on disk)
│   ├── generator/     ComposeGeneratorService + model/  (wizard input -> YAML)
│   ├── execution/     ComposeExecutionService, ComposeProcessResult   (ProcessBuilder)
│   ├── validation/    ComposeValidationService       (`docker compose config`)
│   ├── dto/, web/
├── config_editor/     ConfigFileService (must use PathValidationUtil, backup before write), DTOs, controller
├── streaming/         LogStreamHandler, DockerEventStreamService, ComposeOutputStreamService, ExecSessionHandler
├── git/               Git credentials (encrypted PAT), remote checks via JGit
├── notification/      in-app notifications (HostStatusChangedEvent listener), per-user preferences, webhooks
├── ai/                AI chat assistant (see "AI assistant" below)
│   ├── settings/      ADMIN AI settings: API key (SecretCipher, hint only), base URL, model
│   ├── llm/           OpenAI-compatible chat client with tool calling and streaming
│   ├── tools/         tool registry: role-scoped tools over existing feature services
│   └── chat/          chat sessions/messages, orchestration loop, confirmations, sharing
├── retention/         retention settings, daily purge of audit log, notifications and chat sessions
├── backup/            BackupService, BackupController (ADMIN encrypted export of DB tables + stacks dir)
├── auth/              User, Role (ADMIN/OPERATOR/VIEWER), UserService, AuthController, DTOs
├── permission/        Permission (per host/stack), PermissionService, PermissionEvaluator (@PreAuthorize)
└── audit/             AuditLogEntry, service, AuditAspect (AOP, @Auditable)
```

**Dependency direction:** `docker/` knows no other feature module. `compose/` and `streaming/` may use `docker/`, never the other way around. `ai/` may call other feature services (its tools are thin adapters over them); no feature imports `ai/`. The AI-origin marker for audit entries lives in `common/`, so `audit/` never depends on `ai/`. To be enforced with ArchUnit later.

**Principles:**

- **Never pass docker-java types outward.** Expose our own interfaces and DTOs.
- **Don't rebuild Compose.** Store the YAML, run `docker compose up/down/pull/config` as a subprocess, and stream the output live over WebSocket.
- **Two UI modes for Compose:** a form wizard (generates YAML from DTOs) and an advanced mode (raw YAML editor with Compose JSON schema validation in the frontend).
- **Every deploy creates a `StackVersion`** (history, rollback).
- **`spring.jpa.open-in-view: false`.** Build DTOs in the service layer.
- **Auditing via AOP** (`@Auditable` + `AuditAspect`), not by hand in every service.
- **Config editor writes make a backup first.**

## AI assistant

Decided in epic shipr-9sm. An in-app chat that can do what the signed-in user can do in Shipir.

- **Providers:** two, each with its own stored config (base URL, model, encrypted API key + hint): OpenAI-compatible chat completions with tool calling (default, seeded with the NVIDIA build API and model GLM 5.3 Flash; also works with Anthropic's OpenAI-compatible endpoint) or native Google Gemini (`streamGenerateContent`, function calling). ADMIN picks the active provider at `/settings/ai`. No native Anthropic client.
- **Tools run as the signed-in user** with their role (VIEWER is read-only). Tools are thin adapters over existing feature services, never raw Docker, shell or SQL access.
- **Writes need explicit Confirm** in the chat. Model output alone never changes anything.
- **Audit:** actions are audited as the user, with details marking AI origin.
- **API key:** set by ADMIN at `/settings/ai`, encrypted via `SecretCipher`, only a hint is returned, never logged.
- **Sessions are owner-only.** The owner may share a session read-only with ADMINs (for debugging) and revoke it. Old sessions are purged by the retention job.
- **In scope, read-only (shipr-006w):** the config_editor read tools (list and read config files inside a deployment directory, under the config editor's rules: `compose.yaml` and `.env` are excluded entirely; env-like file contents are never exposed, but their names may appear in the file tree, as in the UI) and the permission read tool (ADMIN-only, lists access grants). Both features are built, so the assistant can answer questions about them. Read-only only.
- **Deployment services (shipr-ycvc.9, decided 2026-11-09):** VIEWERs may list a deployment's services and profiles read-only; the AI has `list_deployment_services` (read) and `service_action` (restart/pull/up) plus `set_deployment_profiles` (writes, OPERATOR, Confirm, same checks as REST).
- **Out of scope:** config_editor write tools (file edits, backup restore), permission write tools (grant, change, revoke), env-like file contents and `compose.yaml`/`.env` (read or write), exec terminal, RAG/embeddings. Reason for keeping writes out: a model that edits files or changes access rights is a prompt-injection risk even behind Confirm.

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
- Backend tests: one class per class under test, named `<ClassName>Test`, in `backend/src/test/java/dev/bethge/shipir/<feature>/`. Anything touching the database or Docker uses Testcontainers through the shared test baseline; no ad-hoc containers. Mockito only at module boundaries (e.g. the `docker/` port interfaces), never for the class under test or the JPA layer.
- Frontend tests: Vitest with Testing Library, file next to the code as `<name>.test.ts(x)`. Test behavior as a user sees it (roles, labels, text), not implementation details. Mock the network at the `apiFetch` boundary, don't mock React Query. Test names state the behavior (`rejectsPathOutsideBaseDirectory`, `shows error when start fails`).
- Browser e2e (Playwright, Chromium) against the Vite frontend with `/api` mocked at the network level (`page.route`); no real backend yet. Specs in `frontend/e2e/`, one per feature flow (`<feature>.spec.ts`, e.g. `containers.spec.ts`), shared fixtures and mock data in `frontend/e2e/fixtures/`. Import `test` and `expect` from `e2e/fixtures/api.ts` (behavior: `api.spec.ts`) and register responses with `api.mock(method, path, { status, body })` (path without query string); an `/api` call without a mock fails the test. One-time `pnpm exec playwright install chromium`. Every run takes its own free dev-server port (`frontend/e2e-config/port.ts`), so parallel runs never share a server; `E2E_PORT=<port>` overrides it (`E2E_CSP_PORT` for `pnpm e2e:csp`). Without `frontend/playwright.config.ts` e2e is "n/a (no e2e setup)"; never set up the framework as part of another task.
- Open decisions live in `HANDOFF.md`; planning (`text-to-task`) respects its build order and the architecture rules (e.g. a Flyway migration is its own child, ahead of the entity/repository that needs it). At the end of a run, tick the HANDOFF.md decisions it settled and remove items that are now fully covered; a plan must have no unresolved open decision from HANDOFF.md. Findings are tracked in beads only; `BACKLOG.md` stays the list of Portainer features.
