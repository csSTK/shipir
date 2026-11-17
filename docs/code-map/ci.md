# Code map: ci

.github/ workflows and automation config.

- .github/workflows/tests.yml: reusable tests; `affected`+`base`+`csp` inputs limit Maven (2 forks), vitest and the CSP/nginx job to the diff; default runs all
- .github/workflows/ci.yml: push to `dev` and pull requests: paths filter, diff base, script tests and code map check, then `tests.yml` (affected, no e2e)
- .github/workflows/release.yml: push to `prd`: runs all tests, then builds and pushes backend and frontend images to GHCR tagged by commit sha and `latest`
- .github/workflows/tag.yml: on `v*` tag push re-tags the tested `sha-<commit>` images with semver tags (no build, fails if the image is missing)
