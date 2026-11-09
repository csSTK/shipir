#!/usr/bin/env bash
# Runs the screenshot regression tests (playwright.visual.config.ts) inside the official Playwright image, so macOS
# and Linux CI render identical pixels. The image tag is read from pnpm-lock.yaml, so it always matches the installed
# @playwright/test. Extra arguments go to `playwright test` (e.g. --update-snapshots).
# Linux dependencies live in docker volumes (node_modules, pnpm store), the host's node_modules is never touched.
# The named volumes are shared by every checkout and worktree on this machine: two runs at the same time (e.g. from
# two worktrees) can race on `pnpm install` into them. Run one at a time.
set -euo pipefail

cd "$(dirname "$0")/.."
frontend=$PWD
repo=$(dirname "$frontend")

version=$(grep -m1 -oE "'@playwright/test@[0-9]+\.[0-9]+\.[0-9]+'" pnpm-lock.yaml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)
if [ -z "$version" ]; then
  echo "visual-docker.sh: cannot read the @playwright/test version from pnpm-lock.yaml" >&2
  exit 1
fi
pnpm_version=$(grep -oE '"packageManager": "pnpm@[0-9.]+' package.json | grep -oE '[0-9.]+$' || true)
if [ -z "$pnpm_version" ]; then
  echo "visual-docker.sh: cannot read the pnpm version (packageManager) from package.json" >&2
  exit 1
fi
image="mcr.microsoft.com/playwright:v${version}-noble"

# Args for the container shell, quoted so they survive `bash -c`.
args=""
for a in "$@"; do args+=" $(printf '%q' "$a")"; done

docker run --rm --ipc=host \
  -v "$repo:/work" \
  -v shipir-visual-node-modules:/work/frontend/node_modules \
  -v shipir-visual-pnpm-store:/pnpm-store \
  -w /work/frontend \
  -e CI=1 -e E2E_PORT=4173 -e npm_config_store_dir=/pnpm-store \
  "$image" \
  bash -c "
    set -e
    npm install -g pnpm@${pnpm_version} --loglevel=error
    pnpm install --frozen-lockfile --prefer-offline --reporter=silent
    status=0
    pnpm exec playwright test -c playwright.visual.config.ts$args || status=\$?
    # Files written as root would be awkward to edit on the host.
    chown -R $(id -u):$(id -g) e2e/visual-baselines test-results playwright-report-visual 2>/dev/null || true
    exit \$status
  "
