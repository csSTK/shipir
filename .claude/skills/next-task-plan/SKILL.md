---
name: next-task-plan
description: Steps 2 and 4 of /next-task. Validates an epic's beads tasks, then plans the implementation as parallel waves with a model and file set per child task, plus the hand-off prompt for implementer sub-agents. Normally invoked by the next-task skill, not directly.
---

# next-task-plan

## Part 1: Check the beads tasks

1. `bd show <epic>` must be an epic that's `open` or `in_progress`.
2. `bd list --parent <epic>` lists the children. Ignore children that are already `closed`.
3. For each open child, `bd show <child>` must have:
   - a clear description and testable acceptance criteria
   - a `model:haiku` or `model:sonnet` label
   - no unresolved open decision from HANDOFF.md

   If any child fails this, **stop** and tell the user which child and what's missing. Suggest `/text-to-task` to fix it. Don't patch the beads yourself.
4. Blockers outside the epic, from `bd dep list <child>`: if a child depends on an issue outside this epic that's still open, **stop** and report it.
5. If no open children are left, skip straight to `next-task-finish`. Its job is then only to close the epic.

## Part 2: Plan the implementation

Use the Code Map in CLAUDE.md, plus the paths named in each child, to work out the **file set** each child will touch. Only read code where the map isn't enough.

Build **waves**:
- Wave 1 holds the children with no open dependencies inside the epic. The next wave holds the children whose dependencies are all in earlier waves. Continue the same way.
- Children in the same wave run in parallel only if their file sets don't overlap. Shared files count as overlapping: `pom.xml`, `package.json`, `application.yml`, `CLAUDE.md`, `README.md`. If two overlap, move one into the next wave.
  - Exception: Code Map lines in CLAUDE.md. Tell parallel agents to edit only their own lines; you resolve conflicts while committing.

Show the plan to the user as a short table: wave, child, model, file set. Then continue without waiting, unless the plan surfaced a problem.

## Hand-off prompt (one per child)

Use the agent matching the label (`implementer-sonnet` or `implementer-haiku`) and send this prompt:

```
Implement bead <child-id> on branch <branch>. Follow .claude/skills/next-task-implement/SKILL.md exactly.
Your file set: <paths>. Other agents are working in parallel on: <other children's paths>. Don't touch those.
Do not commit, push or close the bead. Report back as that skill describes.
```
