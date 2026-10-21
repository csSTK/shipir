---
name: next-task-e2e
description: Add or extend Playwright e2e tests for the user-visible flow of a beads task and run them. Used by implementer sub-agents in /next-task; also usable on its own.
---

# next-task-e2e

**Applies when** the task changes something a user can see or do in the UI. Backend-only and tooling tasks report `e2e: n/a (<reason>)`.

**If the e2e setup doesn't exist yet** (no `frontend/playwright.config.ts`), report `e2e: n/a (no e2e setup)`. Never set up the framework as part of another task.

## Conventions

- **Tool:** Playwright with Chromium. Specs live in `frontend/e2e/`, one spec per feature flow: `<feature>.spec.ts`, e.g. `containers.spec.ts`.
- **Target:** the Vite frontend with `/api` mocked at the network level (`page.route`). Put shared fixtures and mock data in `frontend/e2e/fixtures/`. No real backend yet. Once full-stack e2e exists, this skill gets updated.
- **Scope:** test the user journey (open the page, act, see the result), including at least one error path. Leave edge cases to unit tests.
- **Selectors:** use roles, labels and text (`getByRole`, `getByLabel`). Use `data-testid` only when nothing semantic exists. Never use CSS or XPath structure.
- **No sleeps.** Rely on Playwright's auto-waiting and web-first assertions (`await expect(...)`).

## Run

From `frontend/`:
- Focused: `pnpm e2e e2e/<feature>.spec.ts`
- Full: `pnpm e2e`

Report the spec files added or changed and the pass/fail counts.
