---
name: next-task-finish
description: Final step of /next-task and /next-task-beads once all sub-agents are finished. Rebases onto origin/dev, runs the full test gate, records findings, closes the beads, writes short documentation, pushes to dev from the worktree and removes the worktree and branch. Normally invoked by those skills, not directly.
---

# next-task-finish

Everything runs **inside the worktree** (`cd <WT> && ...`). The main checkout is never switched: the branch is pushed to `dev` directly, so other sessions keep their checkout.

Docs and bead closing happen **before** the push, so they're included in it.

1. **Rebase onto the latest dev.** `git fetch origin` then `git rebase origin/dev`. If there are conflicts, stop and report. Don't resolve them by guessing.
2. **Full test gate.** Run `next-task-test full` on the rebased branch. If anything fails, **stop**: don't close beads, don't push, and report the failure.
3. **Findings.** Run `next-task-findings` for anything collected during the run that isn't filed yet, plus anything the full gate or the overall diff (`git diff origin/dev...HEAD`) shows outside the scope.
4. **Close the beads.**
   - Each child (or each bead given to `/next-task-beads`): `bd close <id> "<one line: what was done> (<commit hash>)"`. Only close beads whose acceptance criteria were verified.
   - Epic runs only: `bd close <epic> "<one line summary>"`, but only once every child is closed.
   - Beads that aren't done stay open, and the epic stays open too. Say so in the report.
5. **Write documentation.** Keep it short, following the CLAUDE.md rule:
   - Code Map: check that every added, moved or removed file has a correct one-line entry.
   - README.md: update only if setup, commands or features visible to users changed.
   - CLAUDE.md: update Build & Test or the rules only if they changed.
   - HANDOFF.md: tick any decisions this run settled. Remove items that are now fully covered.
   - Commit as `Document <epic title> (<epic-id>)` (bead runs: `Document <short summary> (<id>, <id>)`), including `.beads/issues.jsonl`.
6. **Push to dev.**
   - `git fetch origin` and check `git merge-base --is-ancestor origin/dev HEAD`. If dev moved in the meantime, go back to step 1 (rebase and run the gate again).
   - `git push origin HEAD:dev`. This is a fast-forward push; never use `--force` or `--force-with-lease`. If it is rejected, stop and report.
7. **Clean up** after a successful push, from the main repo (`MAIN`, see `next-task-branch`):
   - `git -C "$MAIN" worktree remove <WT>`. Without `--force`: it refuses if anything is uncommitted; then stop and report.
   - Check `git -C "$MAIN" merge-base --is-ancestor <branch> origin/dev`, then `git -C "$MAIN" branch -D <branch>`. The ancestor check is what makes `-D` safe; never skip it.
   - Update the local `dev` only if no worktree has it checked out: `git -C "$MAIN" fetch origin dev:dev` (git refuses on its own otherwise; that's fine, mention it).
8. **CI** is not part of this skill. The optional `watch-ci` step belongs to the calling skill.
