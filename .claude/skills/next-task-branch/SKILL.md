---
name: next-task-branch
description: Step 1 of /next-task, /next-task-beads and /next-task-batch. Creates the working branch in its own git worktree from the latest origin/dev, so parallel sessions and sub-agents never share a checkout. Normally invoked by those skills, not directly.
---

# next-task-branch

**Input:** a unit ID (the epic ID, or for `/next-task-beads` the first bead ID), a branch prefix (`epic` or `task`) and a title for the slug.

A branch belongs to a checkout, not to an agent. Every run therefore gets its **own worktree**; the main checkout is never switched, so other sessions working there are not disturbed and nothing lands on the wrong branch.

1. **Find the main repo:** `MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")`. The worktree path is `WT=$(dirname "$MAIN")/shipir-<unit-id>`, e.g. `../shipir-shipr-cxk`.
2. **Fetch dev:** `git -C "$MAIN" fetch origin dev`. Never base the branch on the current branch, `HEAD` or the local `dev`.
3. **Create branch and worktree in one step:** `git -C "$MAIN" worktree add -b <prefix>/<unit-id>-<slug> "$WT" origin/dev`. The slug is 2–5 lowercase words from the title joined by hyphens, e.g. `epic/shipr-yy7-project-skeleton`.
   - If the branch or the path already exists (`git worktree list`, `git branch --list`), ask the user whether to resume there or start fresh. Starting fresh deletes the old worktree and branch, so confirm first, and never remove a worktree with uncommitted changes.
   - Uncommitted changes in the main checkout are not your concern: they stay there and are not carried over.
4. **From now on, work only in `$WT`.** Start every Bash command with `cd <WT> &&` and use absolute paths under `<WT>` for Read/Edit/Write. Never run `git switch`/`checkout` anywhere.
5. Report the branch name, the worktree path and the `origin/dev` commit it starts from (`git -C "$MAIN" rev-parse --short origin/dev`).
