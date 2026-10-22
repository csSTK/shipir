---
name: next-task-finish
description: Final step of /next-task once all sub-agents are finished. Runs the full test gate, closes the beads, writes short documentation, merges the epic branch into dev, pushes and deletes the local branch. Normally invoked by the next-task skill, not directly.
---

# next-task-finish

Docs and bead closing happen **before** the merge, so they're included in the push. A step that ran after the push and branch deletion would leave its work unpushed.

1. **Full test gate.** Run `next-task-test full` on the branch. If anything fails, **stop**: don't close beads, don't merge, and report the failure.
2. **Close the beads.**
   - Each child: `bd close <child> "<one line: what was done> (<commit hash>)"`. Only close children whose acceptance criteria were verified.
   - The epic: `bd close <epic> "<one line summary>"`, but only once every child is closed.
   - Children that aren't done stay open, and the epic stays open too. Say so in the report.
3. **Write documentation.** Keep it short, following the CLAUDE.md rule:
   - Code Map: check that every added, moved or removed file has a correct one-line entry.
   - README.md: update only if setup, commands or features visible to users changed.
   - CLAUDE.md: update Build & Test or the rules only if they changed.
   - HANDOFF.md: tick any decisions this epic settled. Remove items that are now fully covered.
   - Commit as `Document <epic title> (<epic-id>)`, including `.beads/issues.jsonl`.
4. **Merge into dev and push.**
   - `git fetch origin`
   - `git switch dev`
   - `git pull --ff-only origin dev`
   - `git rebase dev <branch>`. If there are conflicts, stop and report. Don't resolve them by guessing.
   - `git switch dev`
   - `git merge --ff-only <branch>`
   - `git push origin dev`

   Never use `--force` or `--force-with-lease` here. If the push is rejected, stop and report.
5. **Clean up** after a successful push:
   - `git branch -d <branch>`. Use the lowercase `-d`: it refuses to delete unmerged work.
   - Confirm `git status` is clean and `dev` matches `origin/dev`.
6. **CI** is not part of this skill. The optional `watch-ci` step is step 8 of `next-task`.
