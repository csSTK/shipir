# Sous Factory trial: handoff

Trial of [Sous Factory](https://sousfactory.com/) (MIT, `sous-factory` on npm) against our own `/next-task*` pipeline. Local only: branch `try/sous` in worktree `../shipir-sous`, never pushed.

## Setup (2026-10-24)

- `npm i -D sous-factory`, `npx sous init` in `../shipir-sous`; global CLI also installed (`npm i -g sous-factory`, run from `.sous/`).
- `.sous/sous.yaml`: `baseBranch: try/sous`, `tracker: beads`, `lifecycle: steps` (Sous only reads beads, never claims/blocks/closes).
- `.sous/flows/*.yaml`: all agents on `claude-sonnet-5-5` (default was Opus); `baseBranch`/`diffRange` changed from `main` to `try/sous` (hard-coded in the flows, missed at first, caused the first run to stop at sync).
- `.sous/steps/verify/eval.ts`: `BASE_BRANCH = "try/sous"`, checks = backend `./mvnw -q verify` (when `backend/` changed), frontend `pnpm install --frozen-lockfile && pnpm lint && pnpm test && pnpm build` (when `frontend/` changed).
- Commits on `try/sous`: `93e5625` setup, `00b5409` flow base branch fix.
- Known gaps: npm skipped esbuild's postinstall (`npm install-scripts approve esbuild` if needed); Sous reports the `code-review` skill is referenced but not in its skills list.

## Run 1: `shipr-hae` via Sous (`sous run task shipr-hae`)

Stop DockerEventStreamService worker without interrupt. Result: merged onto `try/sous` as `e85ca2e` (`DockerEventStreamService.java` +49/-14, `DockerEventStreamServiceTest.java` +88, plus `specs/.../spec.md`).

| Step | Time | Result |
|---|---|---|
| plan | 28s | spec derived from the bead |
| implement | 8m20s (agent 1m31s, checks 6m50s) | ok |
| review (diff) | 32s | pass, round 1 |
| sync | 7m34s (checks) | ok (first attempt failed: `main` hard-coded) |
| review-full | 3 rounds | fail, fail, pass |
| fix-review-full | 2 rounds, 17m46s (agent 1m40s, checks 16m) | ok |
| merge | 5s | `fix(shipr-hae): ...`, no push |

- Wall clock ~36 min, agent time ~5.5 min; ~30 min was full Maven `verify` after every step.
- Cost not measured (Sous calls the `claude` CLI; take a `/usage` snapshot before/after next time).
- Key catch: review-full found tests asserting `worker.isInterrupted()` after the thread had terminated (always false), so they would pass against the old code. Fixed in two rounds.
- Not done: bead `shipr-hae` still open, no Code Map line in CLAUDE.md, extra `spec.md` that does not belong on `dev`.

## Run 2: `shipr-8qd` via `/next-task-beads` (comparison)

Notification list ignores deployment-only grants. Pushed to `dev` as `1278ae0`, bead closed.

- Wall clock ~5 min, API time 2m28s, cost $1.34 Sonnet (+ $0.56 Opus for a first attempt that only refused because the session was on Opus).
- 4 files +115/-29 incl. Code Map update; full `verify` passed; independent review PASS (one unused import fixed).
- Gap: tests were never run against the old code. Checked by hand afterwards in a throwaway worktree: `deploymentOnlyGrantHidesOtherDeploymentsAndUnscopedContainers` fails on `1278ae0^` (expected 1, was 4), so the test is valid.
- Reviewer noted `markAllRead` also marks hidden notifications; filed as `shipr-km9`.

## Comparison

| | next-task (`shipr-8qd`) | Sous (`shipr-hae`) |
|---|---|---|
| Wall clock | ~5 min | ~36 min |
| Agent time | ~2.5 min | ~5.5 min |
| Cost | $1.34 | not measured |
| Review | 1 round, PASS | diff + 3 whole-branch rounds, caught a test that proves nothing |
| Tests proven red-before-green | no (verified by hand later) | enforced by review |
| Repo conventions | Code Map, beads, `dev` | none of them |

## Decision

Stay on `/next-task*`. Take over Sous's one real advantage instead of the tool:

- `shipr-bby` epic: `.1` red-before-green evidence in implementer reports, `.2` "would this test pass on the old code" in `next-task-review`, `.3` move the Code Map out of always-loaded CLAUDE.md (usage showed 37% of spend on always-on context over 40k).
- Branch review is now always on (flag removed). Findings are filed as children of four standing "Findings: <category>" epics (`fcat:` labels), each with `model:` label and criteria, so `/next-task <findings-epic>` runs a group.
- Earlier: `shipr-kjl` (review step, retry cap, Sonnet orchestrator, optional branch review), done.

## Open

- `shipr-hae`: decided to bring `e85ca2e` to `dev` (cherry-picked without `spec.md`, Code Map entry in `docs/CODE_MAP.md`, together with this file).
- `try/sous` / `../shipir-sous`: keep as reference or remove (`git worktree remove ../shipir-sous`, `git branch -D try/sous`). Sous worktree leftovers under `.sandcastle/` and `.sous/test/.steps-*`.
- Global CLI: `npm uninstall -g sous-factory` if the trial is dropped.

## Upstream improvement ideas for Sous

From what the trial actually hit. Not yet checked against the Sous repo for existing issues. Anything posted there is public under the user's account: draft first, submit only with explicit OK.

1. **One source for the base branch (bug, small PR, do first).** `sous.yaml` has `baseBranch`, but the starter flows hard-code `main` (`baseBranch: main`, `diffRange: main...HEAD`) and so does `steps/verify/eval.ts` (`BASE_BRANCH`). Our first run stopped at sync on that while `sous validate` said ok. Fix: read it from config (e.g. `${{ config.baseBranch }}`), or at least have `validate` warn on a mismatch.
2. **Skip checks on unchanged code (biggest time lever, open an issue first).** ~30 of 36 min were full `verify` runs after implement, sync and every fix round; sync ran them even with nothing to merge. Cache a passed check per tree hash and skip it for an unchanged tree; optionally focused checks per step and the full gate only before merge. No token saving, but much less wall clock and agent idle time.
3. **Cost per step.** The run cost could not be measured. The `claude` CLI reports usage with `--output-format stream-json`; show tokens and dollars per step and in total in `sous status` and the UI.
4. **Red-before-green as an agent-free module step.** Review-full found the test that proved nothing only through the model, in 3 rounds. A module step can run the new tests against the base branch code and require them to fail (what was checked by hand for `shipr-8qd`). Offer it as an optional starter step.
5. **Smaller:** empty `CHECKS` passes with only a warning (`validate` should fail unless explicitly opted out); the starter references the `code-review` skill without listing it; README could mention npm skipping esbuild's install script.

Next step: check the Sous repo for existing issues, then draft 1 (PR) and 2 (issue).
