---
name: next-task-implement
description: The sequence an implementer sub-agent follows for one beads child task during /next-task. Understand, write tests, code, e2e, run focused tests, report. Each step is its own skill. Read by implementer-sonnet and implementer-haiku.
---

# next-task-implement

You implement exactly one child bead. You don't plan, redesign or widen the scope. Read each step's skill (`.claude/skills/<name>/SKILL.md`) and follow it in order.

1. **Understand.**
   - `bd show <id>`, then `bd update <id> --claim`.
   - Read the Code Map in CLAUDE.md to find the files. Only read code where the map isn't enough.
   - If the acceptance criteria are unclear or contradict CLAUDE.md, stop and report. Don't guess.
2. **Write tests:** `next-task-write-tests`. The tests must fail first.
3. **Implement:** `next-task-code`. Makes the tests pass and updates your Code Map lines.
4. **E2E:** `next-task-e2e`. Adds or extends specs for the user-visible flow, or reports n/a.
5. **Run focused tests:** `next-task-test`. Backend and frontend for your area, plus lint, type checks and e2e if present. Everything must pass.
6. **Report back. You're finished.** Don't commit, push or close the bead. Report:
   - the files changed
   - the criterion → test mapping
   - the exact test commands and results
   - the e2e status
   - each acceptance criterion: met or not
   - any new beads filed
