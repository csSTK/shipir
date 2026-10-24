---
name: next-task-beads
description: Implement one or more individual beads (tasks, bugs, chores, findings — not epics) end to end, like /next-task but without an epic. Creates a worktree branch, plans waves, hands each bead to an implementer sub-agent, records findings, closes the beads, pushes to dev. Use when the user says "next-task-beads <id> [<id> ...]" or asks to implement specific beads.
---

# next-task-beads

Same pipeline as `next-task`, for a hand-picked set of beads instead of an epic's children. Run on Sonnet (Model Policy in CLAUDE.md); if the session isn't on Sonnet, say so and suggest `/model sonnet`.

**Input:** one or more bead IDs, optionally followed by `--watch-ci` (`$ARGUMENTS`). If none is given, stop and ask; never pick beads yourself. If an ID is an epic, stop and point to `/next-task` (or `/next-task-batch` for several epics).

**By invoking this skill the user authorizes** for these beads: creating and deleting one branch and worktree, committing, pushing it to `dev` (fast-forward only) and filing `finding` beads. Nothing else: never force-push, never rewrite pushed history, never add attribution lines.

## Steps

Follow `next-task` steps 1–8, with these differences:

1. **Branch:** `next-task-branch` with prefix `task`, unit ID = the first bead ID, title = that bead's title (one bead) or a short summary of the set. E.g. `task/shipr-hae-event-worker-interrupt`.
2. **Check beads:** `next-task-plan` part 1 with the given beads as the work set. Beads without a `model:` label (e.g. fresh `finding` beads) fail the check: stop and suggest `/text-to-task` or adding the label. Dependencies between the given beads order the waves; an open blocker outside the set stops the run.
3. **Dependencies, plan, hand-off, verify, findings:** exactly as in `next-task` steps 3–6, including the `next-task-review` loop after each bead (max 3 rounds, then stop that bead and hand off) and the BLOCKED handling (blocked bead: status `blocked`, notes, worktree kept, dependents skipped, no finish; rerunning resumes in the worktree). Commit each bead as `<bead title> (<bead-id>)`.
4. **Finish:** `next-task-finish` as a bead run (no epic to close; docs commit `Document <short summary> (<ids>)`).
5. **Watch CI:** as `next-task` step 8.

## Report

As in `next-task`: each bead with its commit hash, test results, findings filed, push result, CI result. If stopped early: which step, why, branch and worktree state.
