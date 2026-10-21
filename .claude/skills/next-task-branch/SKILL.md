---
name: next-task-branch
description: Step 1 of /next-task. Creates the working branch for an epic from an up-to-date main. Normally invoked by the next-task skill, not directly.
---

# next-task-branch

1. **Check the working tree** with `git status --porcelain`:
   - Clean: continue.
   - Only `.beads/issues.jsonl` changed (the beads auto-export): continue. It's carried onto the branch and committed with the first commit.
   - Anything else: **stop** and ask the user whether to commit, stash or discard it. Never discard changes yourself.
2. **Update main:**
   - `git switch main`
   - `git pull --ff-only origin main`

   If the fast-forward fails because main has diverged, **stop** and report it. Don't merge or rebase main on your own.
3. **Create the branch:** `git switch -c epic/<epic-id>-<slug>`. The slug is 2–5 lowercase words from the epic title joined by hyphens, e.g. `epic/shipr-yy7-project-skeleton`.
   - If the branch already exists, ask the user whether to resume on it or start fresh. Starting fresh means deleting the old branch, so confirm first.
4. Report the branch name and the `main` commit it starts from.
