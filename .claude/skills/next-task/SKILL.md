---
name: next-task
description: Implement a whole beads epic end to end. Takes an epic ID, creates a branch, prepares dependencies, plans the work, hands each child task to an implementer sub-agent (tests first, implementation, e2e, focused test runs), then closes the beads, updates docs, merges to dev, pushes and deletes the branch. Use when the user says "next-task <epic-id>" or asks to implement an epic.
---

# next-task

Orchestrates one epic from branch to push. You coordinate; the implementer sub-agents write the code. Run this on Opus (Model Policy in CLAUDE.md); if the session isn't on Opus, say so and suggest `/model opus`.

**Input:** an epic ID, optionally followed by `--watch-ci` (`$ARGUMENTS`). Remove the flag, then take the rest as the epic ID. If the ID is missing, or `bd show <id>` says it isn't an epic, stop and ask for one, whether or not `--watch-ci` was passed. Never pick an epic yourself.

**By invoking this skill the user authorizes** for this epic: creating and deleting its branch, committing, merging into `dev` and pushing `dev`. Nothing else: never force-push, never rewrite pushed history, never add attribution lines (see CLAUDE.md).

## Steps

Each step lives in its own skill under `.claude/skills/`. Read that skill's `SKILL.md` and follow it. Stop and report to the user whenever a step says to stop. Don't skip ahead.

1. **Branch:** `next-task-branch`. Creates `epic/<epic-id>-<slug>` from an up-to-date `dev`.
2. **Check beads:** `next-task-plan`, part 1. Validates the epic and its children.
3. **Dependencies:** `next-task-deps`. Installs packages and applies safe updates as their own commit.
4. **Plan implementation:** `next-task-plan`, part 2. Orders the children into waves, with the model and file set for each.
5. **Hand off to sub-agents:** for each wave, start one implementer per child. Use `implementer-sonnet` or `implementer-haiku` according to the child's `model:` label. Children in the same wave run in parallel; waves run one after another. Give each agent the prompt template from `next-task-plan`. Each sub-agent then follows `next-task-implement`:
   1. write tests (`next-task-write-tests`)
   2. implement (`next-task-code`)
   3. e2e tests (`next-task-e2e`)
   4. run focused backend and frontend tests (`next-task-test`)
   5. report back as finished
6. **After each sub-agent reports:** verify the result yourself before accepting it:
   - re-run `next-task-test` for its area
   - check the diff against the acceptance criteria and the CLAUDE.md rules (architecture, security, and `apple-design` for frontend UI)
   - commit only that child's files: `git commit -m "<child title> (<child-id>)"`

   If it fails or is incomplete, send the agent the concrete findings once (SendMessage). If it still isn't right, stop and report. Don't start the next wave until every child in the current wave is committed.
7. **When all sub-agents are finished:** `next-task-finish`. Final full test run, close the beads, write the documentation, merge into `dev` and push, then delete the local branch.
8. **Watch CI (optional):** only after step 7 has pushed `dev` successfully.
   - With `--watch-ci`: run the `watch-ci` skill for `dev` (branch argument `dev`) right away.
   - Without it: ask once with AskUserQuestion, "Skip" as the first and default option, and "Watch CI" second. On "Skip", don't watch.
   - A CI failure never reopens beads, reverts or re-merges anything. Report it; `watch-ci` then offers its bug bead. Don't fix or re-run anything here.

## Report

At the end, keep it short:
- the epic and each child with its commit hash
- the test results
- anything deferred or newly filed as a bead
- the push result
- the CI result from `watch-ci` (run conclusions and URLs), or "CI not watched"

If the run stopped early, say which step stopped it, why, and what state the branch is in.
