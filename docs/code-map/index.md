# Code map

Per-file map of the repo, one file per module. Read this index, then only the one map file that covers the path you work on.
Checked by `node tools/code-map/check.mjs` (CI runs it).

## Rules

- One line per file or folder: `- <repo-relative path>: <description>`. Several paths may share a line: `- a, b: ...`. `{A,B}` and `*`/`**` globs are allowed.
- Description at most 160 characters: what the file is, not its history. No bead IDs, no test-case lists, no error-code catalogues.
- Every listed path must exist. A path appears in exactly one map file.
- Tests are listed in the map file of the module they test. Exception: browser e2e specs (`frontend/e2e/`) all go in `frontend/build.md`, grouped by feature.
- Add, move or remove a file: update its line in the module file in the same change.
- A new map file must be linked from this index.

## Path prefix to map file

| Path prefix | Map file |
|---|---|
| repo root files, `scripts/`, `docs/` | [root.md](root.md) |
| `.github/` | [ci.md](ci.md) |
| `tools/`, `.claude/`, `.agents/`, `.codex/`, `.cursor/`, root `package.json` | [tooling.md](tooling.md) |
| `backend/pom.xml`, `backend/src/main/resources/`, `ShipirApplication`, `package-info`, shared test support | [backend/build.md](backend/build.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/ai/` | [backend/ai.md](backend/ai.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/audit/` | [backend/audit.md](backend/audit.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/auth/` | [backend/auth.md](backend/auth.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/backup/` | [backend/backup.md](backend/backup.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/common/` | [backend/common.md](backend/common.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/compose/` | [backend/compose.md](backend/compose.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/config_editor/` | [backend/config_editor.md](backend/config_editor.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/docker/` | [backend/docker.md](backend/docker.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/git/` | [backend/git.md](backend/git.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/host/` | [backend/host.md](backend/host.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/notification/` | [backend/notification.md](backend/notification.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/permission/` | [backend/permission.md](backend/permission.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/proxy/` | [backend/proxy.md](backend/proxy.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/registry/` | [backend/registry.md](backend/registry.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/retention/` | [backend/retention.md](backend/retention.md) |
| `backend/src/{main,test}/java/dev/bethge/shipir/streaming/` | [backend/streaming.md](backend/streaming.md) |
| `frontend/package.json`, vite/ts/lint config, `frontend/{Dockerfile,index.html,public/,nginx/,e2e-config/,e2e-csp/}`, `frontend/playwright*.config.ts`, `frontend/e2e/` | [frontend/build.md](frontend/build.md) |
| `frontend/src/app/`, `frontend/src/{main.tsx,index.css}` | [frontend/app.md](frontend/app.md) |
| `frontend/src/shared/`, `frontend/src/test/` | [frontend/shared.md](frontend/shared.md) |
| `frontend/src/features/account/` | [frontend/account.md](frontend/account.md) |
| `frontend/src/features/ai-chat/` | [frontend/ai-chat.md](frontend/ai-chat.md) |
| `frontend/src/features/auth/` | [frontend/auth.md](frontend/auth.md) |
| `frontend/src/features/containers/` | [frontend/containers.md](frontend/containers.md) |
| `frontend/src/features/deployments/` | [frontend/deployments.md](frontend/deployments.md) |
| `frontend/src/features/domains/` | [frontend/domains.md](frontend/domains.md) |
| `frontend/src/features/hosts/` | [frontend/hosts.md](frontend/hosts.md) |
| `frontend/src/features/images/` | [frontend/images.md](frontend/images.md) |
| `frontend/src/features/networks/` | [frontend/networks.md](frontend/networks.md) |
| `frontend/src/features/notifications/` | [frontend/notifications.md](frontend/notifications.md) |
| `frontend/src/features/one-time-secret/` | [frontend/one-time-secret.md](frontend/one-time-secret.md) |
| `frontend/src/features/settings/` | [frontend/settings.md](frontend/settings.md) |
| `frontend/src/features/usage-history/` | [frontend/usage-history.md](frontend/usage-history.md) |
| `frontend/src/features/volumes/` | [frontend/volumes.md](frontend/volumes.md) |
