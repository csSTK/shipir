---
name: next-task
description: Implement a whole beads epic end to end. Takes an epic ID, creates a branch, prepares dependencies, plans the work, works in its own git worktree, hands each child task to an implementer sub-agent (tests first, implementation, e2e, focused test runs), records out-of-scope findings as beads, then closes the beads, updates docs, pushes to dev and removes the worktree and branch. Use when the user says "next-task <epic-id>" or asks to implement an epic.
---

# next-task

Orchestrates one epic from branch to push. You coordinate; the implementer sub-agents write the code. **Model check first:** before any other step or tool call, check the session model. If it isn't Opus (Model Policy in CLAUDE.md), stop, say so and suggest `/model opus`. Don't create branches or claim beads first.

**Input:** an epic ID, optionally with the flag `--watch-ci` (`$ARGUMENTS`). Remove the flag, then take the rest as the epic ID. If the ID is missing, or `bd show <id>` says it isn't an epic, stop and ask for one, whatever flags were passed. A whole-branch review always runs before the push (step 7). Never pick an epic yourself.

**By invoking this skill the user authorizes** for this epic: creating and deleting its branch and worktree, committing, pushing the branch to `dev` (fast-forward only) and filing `finding` beads (under the "Findings: <category>" epics, see `next-task-findings`). Nothing else: never force-push, never rewrite pushed history, never add attribution lines (see CLAUDE.md).

## Steps

Each step lives in its own skill under `.claude/skills/`. Read that skill's `SKILL.md` and follow it. Stop and report to the user whenever a step says to stop. Don't skip ahead.

1. **Branch:** `next-task-branch` with prefix `epic`. Creates `epic/<epic-id>-<slug>` from `origin/dev` in its own worktree `<WT>`. Every later step, including yours, runs only in `<WT>` (`cd <WT> && ...`, absolute paths under it); never switch branches in the main checkout.
2. **Check beads:** `next-task-plan`, part 1. Validates the epic and its children.
3. **Dependencies:** `next-task-deps`. Installs packages and applies safe updates as their own commit.
4. **Plan implementation:** `next-task-plan`, part 2. Orders the children into waves, with the model and file set for each.
5. **Hand off to sub-agents:** for each wave, start one implementer per child. Use `implementer-sonnet` or `implementer-haiku` according to the child's `model:` label. Children in the same wave run in parallel; waves run one after another. Give each agent the prompt template from `next-task-plan`, with the worktree path. Each sub-agent then follows `next-task-implement`:
   1. write tests (`next-task-write-tests`)
   2. implement (`next-task-code`)
   3. e2e tests (`next-task-e2e`)
   4. run focused backend and frontend tests (`next-task-test`)
   5. report back as finished, with out-of-scope findings
6. **After each sub-agent reports:** verify the result yourself before accepting it:
   - `cd <WT> && git branch --show-current` still shows the epic branch, and the changed files are in `<WT>`, not in the main checkout
   - re-run `next-task-test` for its area
   - the report has a **Red before green** section (failing test name + assertion line, or an explicit allowed exception); if missing, treat it as a review must-fix and send it back to the implementer
   - check the diff against the acceptance criteria and the CLAUDE.md rules (architecture, security, and `apple-design` for frontend UI)
   - **Review:** run `next-task-review` (fresh read-only reviewer sub-agent). Must-fix findings go back to the same implementer (SendMessage), then re-verify and review again; at most 3 review rounds per child. Out-of-scope findings go to `next-task-findings`. If round 3 still has must-fix findings, stop that child uncommitted and hand off to the user with the open findings
   - after a review PASS, commit only that child's files: `git commit -m "<child title> (<child-id>)"`
   - **Findings:** run `next-task-findings` for the agent's reported findings and whatever your own review of the diff turned up outside the child's scope

   If **verification** fails or is incomplete (not review findings, which follow the review loop above), send the agent the concrete findings once (SendMessage). If it still isn't right, stop and report.

   **If the agent reports BLOCKED** (retry cap in `next-task-implement`): `bd update <child> --status blocked --append-notes "<reason + failing output tail>"`. Leave its changes uncommitted, keep worktree and branch, and don't start any child that depends on it, directly or transitively. Continue the independent children. Don't start the next wave until every child in the current wave is committed or blocked.
7. **When all sub-agents are finished:** if any child is blocked, don't run `next-task-finish`: stop and report the blocked children, skipped dependents and the worktree path. Rerunning `/next-task <epic-id>` resumes in that worktree (`next-task-branch`), picks up blocked and open children, and finishes then. Otherwise `next-task-finish` (it includes the branch review). Rebase onto `origin/dev`, final full test run, remaining findings, close the beads, write the documentation, push to `dev`, then remove the worktree and branch.
8. **Watch CI (optional):** only after step 7 has pushed `dev` successfully.
   - With `--watch-ci`: run the `watch-ci` skill for `dev` (branch argument `dev`) right away.
   - Without it: ask once with AskUserQuestion, "Skip" as the first and default option, and "Watch CI" second. On "Skip", don't watch.
   - A CI failure never reopens beads, reverts or re-merges anything. Report it; `watch-ci` then offers its bug bead. Don't fix or re-run anything here.

## Report

At the end, keep it short:
- the epic and each child with its commit hash
- the test results
- anything deferred, and the `finding` beads filed (ID, priority, title)
- the push result
- the CI result from `watch-ci` (run conclusions and URLs), or "CI not watched"

If the run stopped early, say which step stopped it, why, and what state the branch and the worktree (path) are in.
