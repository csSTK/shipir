---
name: next-task-branch
description: Step 1 of /next-task. Creates the working branch for an epic from the latest origin/dev, never from the current branch. Normally invoked by the next-task skill, not directly.
---

# next-task-branch

1. **Check the working tree** with `git status --porcelain`:
   - Clean: continue.
   - Only `.beads/issues.jsonl` changed (the beads auto-export): continue. It's carried onto the branch and committed with the first commit.
   - Anything else: **stop** and ask the user whether to commit, stash or discard it. Never discard changes yourself.
2. **Fetch dev:** `git fetch origin dev`. Never base the branch on the current branch or `HEAD`, and don't switch to or pull the local `dev` (another agent may be using it or have it checked out).
3. **Create the branch from `origin/dev`:** `git switch -c epic/<epic-id>-<slug> origin/dev`, regardless of which branch is currently checked out. The slug is 2–5 lowercase words from the epic title joined by hyphens, e.g. `epic/shipr-yy7-project-skeleton`.
   - If the branch already exists, ask the user whether to resume on it or start fresh. Starting fresh means deleting the old branch, so confirm first.
4. Report the branch name and the `origin/dev` commit it starts from (`git rev-parse --short origin/dev`).
