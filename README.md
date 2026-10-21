# Shipir

A web platform for managing Docker containers and Compose stacks. Think of it as a simplified Portainer, built so non-experts can use it: forms instead of mandatory YAML, with a raw editor for those who want one.

> **Status:** early development. The repo skeleton (backend + frontend) is set up; features are not implemented yet.

## Why this project

This is a learning and portfolio project focused on Java backend development. The priorities are clean architecture, meaningful tests and well-reasoned decisions over feature count.

## Features (planned)

- **Containers:** list, start, stop, inspect and view stats, across one or more Docker hosts.
- **Hosts:** manage multiple Docker hosts (local Unix socket or remote TCP with TLS), with health checks.
- **Compose stacks:**
  - Create stacks with a form wizard that generates the YAML, or write it yourself in an advanced editor with schema validation.
  - Deploy, stop and pull, with live output.
  - Every deploy is versioned, so you can see history and roll back.
- **Config editor:** edit a stack's config files in the browser, with protection against path traversal and an automatic backup before every write.
- **Live streaming:** container logs, Docker events, Compose output and an exec terminal over WebSocket.
- **Access control:** users with roles (Admin, Operator, Viewer) plus fine-grained permissions per host and stack.
- **Audit log:** a record of who did what, and when.

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
- AI assistant

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

Database `shipir`, user `shipir`, password from `DB_PASSWORD`. Stop it with `docker compose -f docker-compose.dev.yml down` (add `-v` only if you want to delete the data).

### Backend (Java 25, Spring Boot 4.1.1)

```bash
./backend/mvnw -f backend/pom.xml verify          # Compile and run tests
./backend/mvnw -f backend/pom.xml spring-boot:run # Run the app on http://localhost:8080
```

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
│   └── src/shared/                   # Shared (api, ui, lib)
├── README.md                         # This file
├── CLAUDE.md                         # Development rules and architecture
└── HANDOFF.md                        # Planning context
```

### Tasks

Tasks are tracked with [beads](https://github.com/steveyegge/beads). Run `bd ready` to see available work, or see [`CLAUDE.md`](CLAUDE.md) for agent-specific instructions.

