# Pipeline dashboard

Dev tooling (Node, built-ins only) that records and shows `/next-task*` runs. This folder currently holds the recorder; the server, hooks and UI follow in later beads.

## Store

`$(git rev-parse --git-common-dir)/pipeline-runs/<run-id>/`, shared by every worktree of the repo (never committed, lives inside `.git`). Override with `SHIPIR_PIPELINE_STORE=<dir>` (used by tests).

- `run.json`: summary, replaced atomically (write temp file, rename).
- `events.jsonl`: append-only, one JSON object per line, written with `O_APPEND` so parallel sub-agents do not interleave lines.

`run.json` fields: `id`, `kind` (`next-task|beads|batch`), `units` (epic/bead ids), `parent` (run id of the batch, for batch members), `branch`, `worktree`, `status` (`running|done|blocked|failed`), `started`, `ended` (ISO timestamps, `ended` is null while running).

## CLI

```
node tools/pipeline-dashboard/record.mjs <event> [--run <id>] [--key value ...]
```

Without `--run` the run is found by cwd: the running run whose `worktree` contains the current directory (deepest worktree wins). `--units a,b` is a list, known numeric keys are stored as numbers, `--some-key` becomes `someKey`. Run ids match `[A-Za-z0-9][A-Za-z0-9._-]*` and are at most 128 characters. `readRun` applies the same check and returns null for anything else, so URL params can be passed in safely.

Recording never blocks the pipeline: any error prints one `pipeline-record: ...` line to stderr and the exit code is 0.

## Events

Every event has `ts` (ISO), `type` and `run`, plus:

| type | fields |
| --- | --- |
| `run-start` | `kind`, `units`, `parent`, `branch`, `worktree` (creates `run.json`) |
| `run-end` | `status` (`done` default, `blocked`, `failed`; updates `run.json`) |
| `step-start`, `step-end` | `step` (`deps`, `plan`, `wave`, `review`, `test`, `finish`, `push`) |
| `child-start`, `child-end` | `bead`, `model`, `wave`, `result`, `redBeforeGreen` (evidence) |
| `review` | `verdict`, `mustFix` (count), `round` |
| `tests` | `scope`, `passed`, `failed`, `duration` |
| `finding` | `bead` (id of the finding bead) |
| `agent` | `agentId`, `model`, `durationMs`, `tokensIn`, `tokensOut`, `tokensCacheRead`, `tokensCacheWrite`, `cost` (estimate); written by the SubagentStop hook |

Other keys passed on the command line are stored as given, except `ts`, `type` and `run`, which are always set by the recorder.

`run-start` for an existing id rewrites `run.json` from scratch (status back to `running`); events stay appended. `run-end` without a prior `run-start` creates a minimal `run.json` (`started` null).

## Importing

`record.mjs` exports `resolveStoreDir`, `findRunByCwd`, `recordEvent`, `readRun`, `listRuns`, `parseArgs`, `main`, `EVENT_TYPES`, so the server and hooks reuse store resolution and lookup.

## Tests

`node --test tools/pipeline-dashboard/test/`
