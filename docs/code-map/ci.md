# Code map: ci

.github/ workflows and automation config.

- .github/workflows/tests.yml: reusable tests: Maven verify, pnpm lint, test and build, Playwright e2e, CSP and nginx header jobs
- .github/workflows/ci.yml: push to `dev` and pull requests: paths filter, calls `tests.yml` (no e2e), runs the backup script test, cancels superseded runs
- .github/workflows/release.yml: push to `prd`: runs all tests, then builds and pushes backend and frontend images to GHCR tagged by commit sha and `latest`
- .github/workflows/tag.yml: on `v*` tag push re-tags the tested `sha-<commit>` images with semver tags (no build, fails if the image is missing)
- .github/workflows/code-map.yml: runs the code map checker tests and the check on pushes to `dev` and on pull requests touching the map, tooling or source
