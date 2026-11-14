# Code map: ci

.github/ workflows and automation config.

- .github/workflows/tests.yml: reusable tests; `affected`+`base`+`csp` inputs limit Maven, vitest, CSP and nginx jobs to the diff; default runs all
- .github/workflows/ci.yml: push to `dev` and pull requests: paths filter, computes the diff base, calls `tests.yml` (affected, no e2e), runs the script tests
- .github/workflows/release.yml: push to `prd`: runs all tests, then builds and pushes backend and frontend images to GHCR tagged by commit sha and `latest`
- .github/workflows/tag.yml: on `v*` tag push re-tags the tested `sha-<commit>` images with semver tags (no build, fails if the image is missing)
- .github/workflows/code-map.yml: runs the generated code map checker on pushes to `dev` and on pull requests touching the map, tooling or source
