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
| `step-start`, `step-end` | `step` (`deps`, `plan`, `wave`, `review`, `finish`, `push`), `wave` (on `wave` steps; start and end are paired by step + wave), `sha` (on `step-end --step push`) |
| `child-start`, `child-end` | `bead`, `model`, `wave`, `result`, `redBeforeGreen` (evidence) |
| `review` | `verdict`, `mustFix` (count), `round` |
| `tests` | `scope`, `passed`, `failed`, `duration` |
| `finding` | `bead` (id of the finding bead) |
| `agent` | `agentId`, `agentType`, `bead` (from the first user prompt, after the word "bead"; absent if none), `model`, `durationMs`, `tokensIn`, `tokensOut`, `tokensCacheRead`, `tokensCacheWrite`, `cost` (estimate); written by the SubagentStop hook |

Other keys passed on the command line are stored as given, except `ts`, `type` and `run`, which are always set by the recorder.

`run-start` for an existing id rewrites `run.json` from scratch (status back to `running`); events stay appended. `run-end` without a prior `run-start` creates a minimal `run.json` (`started` null).

## Agent events and pricing

The `agent` events come from the SubagentStop hook (`hooks/subagent-stop.mjs`, registered in `.claude/settings.json`). Claude Code passes `cwd`, `agent_id`, `agent_type` and `agent_transcript_path` on stdin. The hook reads the sub-agent transcript: usage is taken from assistant messages (`message.usage`, `message.model`), deduplicated by message id (streamed content blocks repeat the id; the max per field wins), duration is last minus first timestamp. The run is found by cwd (falling back to the cwd recorded in the transcript); no running run means nothing is written. The hook always exits 0 and prints nothing to stdout.

`pricing.json` holds USD per million tokens (`input`, `output`, `cacheRead`, `cacheWrite`) per model id. The cost is the sum over messages, each priced with its own model. If any message's model is missing from the table, the event has no `cost` field (never 0). Prices are estimates: cache write is the 5-minute rate (1.25x input), and Haiku uses the rate card for prompts up to 100K tokens.

## Importing

`record.mjs` exports `resolveStoreDir`, `findRunByCwd`, `recordEvent`, `readRun`, `listRuns`, `parseArgs`, `main`, `EVENT_TYPES`, `RUN_ID`, so the server and hooks reuse store resolution and lookup.

## Tests

`node --test tools/pipeline-dashboard/test/`

## Server/API

`node tools/pipeline-dashboard/server.mjs` (Node built-ins only) binds `127.0.0.1` only, port `PIPELINE_DASHBOARD_PORT` (default 4690), reads the store via `resolveStoreDir`. `createServer({storeDir, port, publicDir, tokensPath, ghRunner, ciRefreshMs, watch, pollMs})` is the factory used by tests (`port: 0`, stubbed `ghRunner`; `await server.listen()`, `server.port`, `await server.close()`).

- `GET /api/health`: `{ok:true}`.
- `GET /api/runs`: run summaries (`run.json` fields + `totals`), newest `started` first; batch members (runs whose `parent` is another run) are nested in the batch's `children`.
- `GET /api/runs/:id`: `{run, events, timeline, ci}`. `timeline` = `steps` (paired step-start/end with `durationMs`, `sha` on push), `children` (per bead: model, wave, result, `redBeforeGreen`, `durationMs`, `agents` summed from `agent` events by `bead`, else by the child's time window), `reviews`, `tests`, `totals` (tokens, `cost`, `agents`, `durationMs`; `cost` is null when there are no agents or any agent has no cost, never 0 or a partial sum), `sha`. Invalid id (not the run-id pattern, `../`, encoded `%2e%2e%2f`, `%2F`, NUL) is 400, unknown id 404.
- A batch that is still `running` but whose members have all ended is reported as ended (`blocked` if any member is blocked/failed, else `done`, `ended` = latest member end) in both endpoints.
- Requests need a `Host` of `127.0.0.1:<port>` or `localhost:<port>`, else 403 (DNS-rebinding guard).
- `GET /api/events`: Server-Sent Events, `event: run-changed` with `data: {"id": "<run id>"}` when a store file changes (`fs.watch` recursive as trigger, polling fallback, also if the store does not exist yet).
- `/tokens.css` is `frontend/src/shared/styles/tokens.css`; everything else is served from `public/` only (resolved, symlinks and traversal refused).

**CI status:** the pushed SHA is the `sha` field of the last `step-end` event with `step=push` (the skill emits `record.mjs step-end --step push --sha <sha>`). For such runs, `ci` is `{sha, state, runs[]}` from `gh run list --commit <sha> --json status,conclusion,url,name` (execFile with an argument list, SHA must be 7-40 hex chars). `state` is `success|failure|pending|none|unknown`; missing/failing `gh` gives `unknown`. Results are cached per SHA; only `success` is cached for good, everything else (pending, failure because CI may be re-run, none, unknown) is refreshed after `ciRefreshMs` (15 s). `ci` is null without a pushed SHA.
