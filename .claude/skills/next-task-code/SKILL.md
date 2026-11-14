---
name: next-task-code
description: Implement a beads task until its (already written) tests pass, within the assigned file set and the CLAUDE.md architecture and security rules. Used by implementer sub-agents in /next-task.
---

# next-task-code

Write the production code that makes the tests from `next-task-write-tests` pass.

- **Stay in your file set.** If you need to change a file outside it, stop and report why. Don't just change it.
- **Follow CLAUDE.md:** its architecture, security and code-style rules apply to every line you write. If a rule and the bead disagree, stop and report. Don't guess.
- **UI changes** (under `frontend/`): read `.claude/skills/apple-design/SKILL.md` first and apply it. Add no new UI library unless the bead names it.
- **Match the surrounding code style**, naming and comment density. No speculative abstractions, flags or configuration the bead doesn't ask for.
- **Don't weaken tests to get green.** If a test turns out to be wrong, fix it and say why in the report.
- **Out-of-scope work** you discover goes into a new bead (`bd create`, then `bd dep add`). Don't do it.
- **Update the Code Map** in the same change, in the module file under `docs/code-map/` that covers your files (find it in `docs/code-map/index.md`): only your own files' lines, one line each (`- path: what it is`), at most 160 characters, no bead IDs, no history. A new module file must be linked from the index.

**Done when** the new tests and the existing tests in the area pass (`next-task-test`, focused). If they still fail after 3 failed focused runs, stop and report BLOCKED (see `next-task-implement`).
