# Shipir

A web platform for managing Docker containers and Compose stacks. Think of it as a simplified Portainer, built so non-experts can use it: forms instead of mandatory YAML, with a raw editor for those who want one.

> **Status:** early development. The repo skeleton (backend + frontend) is set up; features are not implemented yet.

## Why this project

This is a learning and portfolio project focused on Java backend development. The priorities are clean architecture, meaningful tests and well-reasoned decisions over feature count.

## Features (planned)

- **Containers:** list, start, stop, inspect and view stats, across one or more Docker hosts.
- **Hosts:** manage multiple Docker hosts (local Unix socket or remote TCP with TLS), with health checks.
- **Deployments (Compose projects):**
  - Create deployments from a bundled app template (nginx, postgres, wordpress, ...), with a form wizard that generates the YAML, or write it yourself in an advanced editor with schema validation.
  - Deploy, stop and pull, with live output.
  - Every deploy is versioned, so you can see history and roll back.
  - Environment variables per deployment, stored encrypted.
  - Deployments from a (private) Git repository with an access token: Pull & redeploy, and optional polling that redeploys on new commits.
- **Config editor:** edit a deployment's config files in the browser, with protection against path traversal and an automatic backup before every write.
- **Live streaming:** container logs, container stats (CPU, memory, network, disk I/O with sparklines), Docker events, Compose output and an exec terminal over WebSocket.
- **Access control:** users with roles (Admin, Operator, Viewer) plus fine-grained permissions per host and deployment.
- **Audit log:** a record of who did what, and when.
- **Search:** Cmd/Ctrl+K opens a command palette that finds the environments, deployments and containers you can see, and jumps to any page.
- **AI assistant:** an in-app chat (any OpenAI-compatible API, NVIDIA build by default; an admin sets the key at Settings > AI assistant) that answers from live data and acts as the signed-in user with their role. Every change waits for an explicit Confirm. Chats are saved, can be reopened, and can be shared read-only with admins for debugging.

## Upgrade notes

- **Permissions (shipr-cbw):** OPERATOR and VIEWER users only see the environments and deployments an ADMIN grants them (Settings > Users > Access). After upgrading, existing non-admin users see nothing until they are granted access.

## Tech stack

| Area | Choice |
|---|---|
| Backend | Java (current LTS), Spring Boot |
| Frontend | React, React Query, Monaco Editor |
| Database | PostgreSQL 16 with Flyway migrations |
| Docker access | [docker-java](https://github.com/docker-java/docker-java), wrapped behind the app's own interfaces |
| Compose | The `docker compose` CLI, run as a subprocess |
| Real-time | WebSocket with STOMP |
| Security | Spring Security, role-based access control |
| Testing | JUnit 5, Testcontainers, ArchUnit |

## Architecture

The backend is organized **package-by-feature** (`docker`, `host`, `compose`, `config_editor`, `streaming`, `auth`, `permission`, `audit`) rather than by technical layer. Each feature owns its entities, services, DTOs and controllers.

A few deliberate design choices:

- **Docker access goes through an abstraction.** The rest of the app never sees docker-java types. This keeps the core testable and leaves room for other connection types later, such as a remote agent.
- **Compose isn't reimplemented.** The app stores Compose files and runs the official `docker compose` CLI, streaming its output to the browser.
- **The schema is owned by Flyway**, not Hibernate (`ddl-auto: validate`).
- **Auditing is cross-cutting** and done with AOP, not repeated by hand in every service.
- **Security first.** Access to Docker is effectively root on the host, so all file access is validated against allowed base directories, subprocesses never go through a shell, and secrets are never stored in plain text or logged.

## Out of scope (for now)

- Hosted SaaS with a cloud control plane and customer-side agents
- Provisioning hosts for third parties

## Getting started

### Prerequisites

- **JDK 25** — any OpenJDK 25 distribution, e.g. [Eclipse Temurin](https://adoptium.net/).
- **Maven** — not needed locally; the Maven wrapper (`backend/mvnw`) downloads it on first use.
- **Node.js 24+** — download from [nodejs.org](https://nodejs.org) or `brew install node`.
- **pnpm 9** — run `corepack enable` to install it via corepack, or `npm install -g pnpm@9.14.4`.
- **Docker** — install [Docker Desktop](https://www.docker.com/products/docker-desktop) or Docker Engine. Needed for running containers, and **must be running for the backend tests**: they start a throwaway PostgreSQL 16 via Testcontainers (no local Postgres or `DB_PASSWORD` required).
- **git** — to clone the repository.

### Clone and build

```bash
git clone https://github.com/csSTK/shipir.git
cd shipir
```

### Local database (PostgreSQL 16)

The backend needs a local PostgreSQL. It runs in Docker via `docker-compose.dev.yml`:

```bash
cp .env.example .env                                   # then set DB_PASSWORD in .env (.env is gitignored)
docker compose -f docker-compose.dev.yml up -d         # start PostgreSQL on localhost:5432
docker compose -f docker-compose.dev.yml ps            # wait until the status shows "healthy"
```

The connection is configured with five variables, all listed in `.env.example`:

| Variable | Default | Meaning |
|---|---|---|
| `DB_PASSWORD` | none (required) | Password of the database user. Set it in `.env`, never commit it |
| `DB_HOST` | `localhost` | Host the backend connects to |
| `DB_PORT` | `5432` | Port on the host, also the published port of the container |
| `DB_NAME` | `shipir` | Database name |
| `DB_USER` | `shipir` | Database user |
| `SHIPIR_INITIAL_ADMIN_PASSWORD` | none (required on first start) | Password of the initial `admin` account, created only while the users table is empty; the admin must change it on first login. Ignored afterwards |

The compose file reads `DB_NAME`, `DB_USER` and `DB_PORT` from `.env` as well, so the container and the backend always agree.

**Port clash:** if a native PostgreSQL (e.g. Postgres.app) already listens on `127.0.0.1:5432`, it shadows the Docker database. Set `DB_PORT=5433` in `.env` and restart the container; the backend then needs `DB_PORT=5433` too (see below).

Stop the database with `docker compose -f docker-compose.dev.yml down` (add `-v` only if you want to delete the data).

### Hosting with Docker

`docker-compose.yml` runs the backend and the frontend (nginx, serving the built app and proxying `/api` and `/ws`). PostgreSQL is **not** part of this file: point the app at an existing PostgreSQL 16 server.

```bash
cp .env.example .env                                   # then set DB_PASSWORD and DB_HOST (external database) in .env
docker login ghcr.io                                   # once per host: GitHub user + token with read:packages
docker compose -f docker-compose.yml pull              # pull ghcr.io/csstk/shipir-*:${SHIPIR_TAG:-latest}
docker compose -f docker-compose.yml up -d             # app on http://localhost:${SHIPIR_PORT:-8080}
```

The images on ghcr.io are private, so `docker login ghcr.io` is required before the pull; without it, the pull fails with `unauthorized`. Use a personal access token (classic) with the `read:packages` scope as the password.

Without a pull (or when the images don't exist on ghcr.io yet), `docker compose -f docker-compose.yml build` builds both images locally, and `up` uses them.

Required in `.env`: `DB_PASSWORD`, `DB_HOST` and `DOCKER_GID`. Compose stops with a message if one is missing. On the first start (empty database) also `SHIPIR_INITIAL_ADMIN_PASSWORD`; without it the backend refuses to start. Optional: `DB_PORT`, `DB_NAME`, `DB_USER`, `SHIPIR_TAG` (image tag), `SHIPIR_PORT` (host port, default `8080`) and `SHIPIR_ENCRYPTION_KEY` (see below).

**First login:** sign in as `admin` with `SHIPIR_INITIAL_ADMIN_PASSWORD`. Shipir then asks for a new password (15–128 characters with upper- and lowercase letters, a digit and a symbol) before anything else is usable. Next, the admin adds the first Docker environment: the local Docker socket, or a remote Docker engine over TCP with TLS client certificates (needs `SHIPIR_ENCRYPTION_KEY`). Shipir tests the connection before saving it. Later logins skip both steps. After setup, every user lands on the environments overview (status and live stats per environment); only admins can add, rename or delete environments. Clicking an environment opens its dashboard; the sidebar then lists its deployments (Compose projects), containers, images, networks and volumes for every role. Clicking a container name opens its live logs (last 100–1000 lines or all, optional timestamps, stderr highlighted) for every role. Admins and operators can delete containers (running ones only with Force), unused images, volumes and networks, and remove unmanaged deployments (`compose down`, volumes kept unless chosen). They can also pull images with live progress; Shipir checks every 6 hours (`shipir.images.update-check-interval`) whether a newer image exists in the registry and marks images, deployments and containers with 'Update available'. Viewers only read.

**Forgotten password:** the login page has a "Forgot password?" flow: enter the username, then a 6-digit one-time code (valid 5 minutes, single use, 5 attempts) and a new password. Shipir can't deliver the code yet (no mail service), so this isn't usable in a real installation until delivery is added.

**Docker socket (security):** the backend mounts `/var/run/docker.sock` to manage containers on the local Docker host. Access to the Docker socket is effectively root on the host: anyone who can control the backend can control the machine. Therefore only the frontend port is published, the backend has no published port, and the stack should run on a host that only trusted operators can reach. `DOCKER_GID` is the group that owns the socket on the host; the backend user (uid `10001`) joins it through `group_add`. Find it on a Linux host with:

```bash
stat -c %g /var/run/docker.sock                        # prints e.g. 999; put it in .env as DOCKER_GID
```

**Encryption key:** `SHIPIR_ENCRYPTION_KEY` encrypts stored TLS material (AES-256-GCM) at rest. It is optional while only the local Docker host exists. Connections to remote Docker hosts over TCP+TLS require it, and the backend refuses to start if such hosts exist without a valid key. Generate one with `openssl rand -base64 32` (it must decode to exactly 32 bytes). Store it outside git and back it up: losing it makes the stored TLS material unreadable, and changing it breaks decryption of existing data.

Only the frontend port is published. The backend is reachable only from the frontend on the compose network. The frontend starts even if the backend is not up yet; it reconnects on the next request.

Images are published to GitHub Container Registry (`ghcr.io/csstk/shipir-backend` and `ghcr.io/csstk/shipir-frontend`) by the workflows in `.github/workflows/`. A push to `dev` and every pull request runs `ci.yml`: only the tests of the changed area (`backend/`, `frontend/`), no e2e, no images. A push to `prd` runs `release.yml`: all tests and e2e, and only if they pass, the images are pushed as `latest` and `sha-<full sha>`. A `v*` tag (e.g. `v1.2.3`) runs `tag.yml`, which adds the semver tags (`1.2.3`, `1.2`, `1`; no `major` tag for `v0.x`) to the tested `sha-<commit>` image without rebuilding. Set `SHIPIR_TAG` to pick one.

Branches: `dev` is the default branch where all work lands. `prd` holds released versions and only ever fast-forwards to a `dev` commit. Release: `git push origin dev:prd`, wait until `release.yml` is green, then push the `v*` tag on that commit. If `tag.yml` failed because the image did not exist yet, re-run it with `gh run rerun <id>`.

Deployment files live in the named volume `shipir-stacks`, mounted at `/data/stacks` in the backend (`SHIPIR_STACKS_BASE_DIR`, set in the image). The backend image includes the `docker compose` CLI, which runs as the app user (uid `10001`) and uses the same mounted Docker socket.

Stop the stack with `docker compose -f docker-compose.yml down`. This does not touch the external database or the `shipir-stacks` volume. Only `down -v` deletes the stack files.

### Backend (Java 25, Spring Boot 4.1.1)

```bash
./backend/mvnw -f backend/pom.xml verify          # Compile and run tests
```

To run the app against the local database, export the variables from `.env` into your shell first. The Spring app does not read `.env` itself:

```bash
set -a; source .env; set +a                       # export all variables from .env (bash/zsh)
./backend/mvnw -f backend/pom.xml spring-boot:run # Run the app on http://localhost:8080 (runs Flyway migrations)
```

In fish, `source .env` fails on the `KEY=value` lines. Export them like this instead (values without quotes, fish would keep them):

```fish
for line in (string match -rv '^\s*(#|$)' < .env); set -gx (string split -m 1 = $line); end
```

Tests don't need these variables: they start their own throwaway PostgreSQL via Testcontainers.

The backend is organized package-by-feature under `backend/src/main/java/dev/bethge/shipir/`.

### Frontend (React + TypeScript + Vite)

```bash
cd frontend

# Install dependencies
pnpm install

# Run the dev server (http://localhost:5173)
# The frontend proxies /api requests to http://localhost:8080 and WebSocket /ws to ws://localhost:8080
pnpm dev

# Run tests once
pnpm test

# Run tests in watch mode
pnpm test:watch

# Build for production
pnpm build

# Lint with oxlint
pnpm lint

# One-time: install the Chromium browser used by the e2e tests
pnpm exec playwright install chromium

# Run the Playwright e2e tests (in e2e/, with /api mocked)
pnpm e2e

# Run the e2e tests in Playwright's UI mode
pnpm e2e:ui
```

### Repository layout

```
shipir/
├── backend/                          # Java backend (Spring Boot)
│   ├── mvnw, .mvn/                   # Maven wrapper
│   ├── pom.xml                       # Maven configuration (groupId: dev.bethge, artifactId: shipir)
│   └── src/main/java/dev/bethge/shipir/  # Organized package-by-feature
├── frontend/                         # React + TypeScript + Vite
│   ├── src/app/                      # Root, providers, React Query client
│   ├── src/features/                 # Feature folders (containers, hosts, stacks)
│   ├── src/shared/                   # Shared (api, ui, lib)
│   └── e2e/                          # Playwright e2e specs; fixtures/api.ts mocks /api
├── README.md                         # This file
├── CLAUDE.md                         # Development rules and architecture
└── HANDOFF.md                        # Planning context
```

### Tasks

Tasks are tracked with [beads](https://github.com/steveyegge/beads). Run `bd ready` to see available work, or see [`CLAUDE.md`](CLAUDE.md) for agent-specific instructions.

