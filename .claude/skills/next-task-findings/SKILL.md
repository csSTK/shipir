---
name: next-task-findings
description: Writes code-quality and bug findings that are out of scope for the current task into the shared backlog (beads labelled `finding`), deduplicated. Used by the /next-task orchestrators after each child and before finishing; also usable on its own.
---

# next-task-findings

The shared backlog for findings is **beads with the label `finding`** (synced via Dolt, so every session and machine sees them; `bd list --label finding --status open`). Not a markdown file: CLAUDE.md requires beads for all tracking. `BACKLOG.md` stays the list of Portainer features.

**Input:** the findings to record, each with a file/line, what is wrong and why it matters, and the bead it was discovered in (`<source-id>`). Sources:
- the "Findings" section of an implementer report
- your own review of the child's diff (`git diff` in the worktree) during verification: bugs, missing malicious-case tests, CLAUDE.md rule violations, duplication, dead code, unclear naming

## What counts

- Something wrong or worth improving that is **outside the current bead's acceptance criteria**. A defect in the bead's own scope is not a finding: send it back to the implementer.
- Concrete and verifiable: a file, a scenario, a reason. No vague "could be cleaner".

## Steps

1. **Deduplicate:** `bd search "<key words>"` and `bd list --label finding --status open`. If an open bead already covers it, add a note instead: `bd update <id> --notes "Seen again in <source-id>: <one line>"`.
2. **File new ones:**

   ```bash
   bd create --title="<short claim>" --type=<bug|chore> --priority=<0-4> \
     --labels finding \
     --deps discovered-from:<source-id> \
     --description="<file:line>. <what happens, concrete scenario>. <why it matters>." \
     --acceptance="<how we know it's fixed>"
   ```

   - `bug` for wrong behaviour, `chore` for code quality. Priority: 1 security or data loss, 2 user-visible bug, 3 quality, 4 nice to have.
   - No `model:` label: a finding is triaged before it is planned (`/text-to-task` or adding a label makes it ready for `/next-task-beads`).
3. **Don't fix it here** and don't widen the running task. Findings never block the epic, except priority 1: report those to the user at once.

## Report

One line per finding: new bead ID or the existing one it was added to, priority, title.
