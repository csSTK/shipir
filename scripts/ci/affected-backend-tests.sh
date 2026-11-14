#!/usr/bin/env bash
# Selects the backend tests affected by a change. Reads changed paths (one per line) from stdin, or
# computes them with `git diff --name-only <base>...HEAD` when called as `--base <ref>`.
# stdout (one line): FULL, NONE, or a comma-separated list of surefire -Dtest patterns.
# stderr: one "affected: ..." line per reason, so the CI log shows why. BACKEND_ROOT overrides ./backend (tests).
set -euo pipefail

ROOT="${BACKEND_ROOT:-backend}"
PREFIX="${ROOT%/}"
BASE_PKG="dev.bethge.shipir"
PKG_DIR="src/main/java/dev/bethge/shipir"

if [[ "${1:-}" == "--base" ]]; then
  CHANGED="$(git diff --name-only "${2:?--base needs a ref}...HEAD")"
else
  CHANGED="$(cat)"
fi

reason() { echo "affected: $*" >&2; }
full() { reason "FULL: $1"; echo FULL; exit 0; }

DIRECT=" "   # top-level packages with a changed main file (closure applies)
TESTONLY=" " # top-level packages with only changed test files
ARCH=0
SEEN=0

# Top-level package of a path below $2 (e.g. .../shipir/auth/oidc/X.java -> auth), empty for top-level files.
top_pkg() {
  local rest="${1#"$2"/}"
  [[ "$rest" == */* ]] && echo "${rest%%/*}" || true
}

while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  case "$path" in
    scripts/ci/*|.github/workflows/*) full "$path (CI tooling)" ;;
    backend/*) ;;
    *) continue ;;
  esac

  rel="${path#backend/}"
  case "$rel" in
    Dockerfile|.dockerignore|*.md) reason "ignored $path"; continue ;;
    pom.xml|mvnw|mvnw.cmd|.mvn/*) full "$path (build setup)" ;;
    src/main/resources/*) full "$path (main resources)" ;;
    src/test/resources/*) full "$path (test resources)" ;;
    src/main/java/dev/bethge/shipir/*)
      pkg="$(top_pkg "$rel" "$PKG_DIR")"
      [[ -z "$pkg" ]] && full "$path (application root)"
      [[ "$pkg" == common ]] && full "$path (common/ is used everywhere)"
      DIRECT+="$pkg "; SEEN=1; reason "$path -> package $pkg"
      ;;
    src/test/java/dev/bethge/shipir/*)
      pkg="$(top_pkg "$rel" "src/test/java/dev/bethge/shipir")"
      file="${rel##*/}"
      if [[ -z "$pkg" ]]; then
        case "$file" in *Test.java|*Tests.java) ;; *) full "$path (shared test support)" ;; esac
        full "$path (top-level test, may depend on everything)"
      fi
      if [[ "$pkg" == architecture ]]; then ARCH=1; reason "$path -> architecture tests"; continue; fi
      case "$file" in
        *Test.java|*Tests.java) ;;
        *) full "$path (test base class or fixture)" ;;
      esac
      TESTONLY+="$pkg "; SEEN=1; reason "$path -> package $pkg (test only)"
      ;;
    *) full "$path (unknown backend path)" ;;
  esac
done <<< "$CHANGED"

if (( ! SEEN && ! ARCH )); then reason "no backend test-relevant change"; echo NONE; exit 0; fi

# Reverse import closure: a package is affected when its sources (main or test) mention a selected package.
SELECTED="$DIRECT"
ALL=()
for d in "$PREFIX"/src/main/java/dev/bethge/shipir/*/; do [[ -d "$d" ]] && ALL+=("$(basename "$d")"); done
changed=1
while (( changed )); do
  changed=0
  for p in "${ALL[@]}"; do
    [[ "$SELECTED" == *" $p "* ]] && continue
    for s in $SELECTED; do
      if grep -rqE "dev\.bethge\.shipir\.$s([^A-Za-z0-9_]|$)" \
          "$PREFIX/src/main/java/dev/bethge/shipir/$p" 2>/dev/null; then
        SELECTED+="$p "; changed=1; reason "package $p imports $s"
        break
      fi
    done
  done
done

OUT=()
for p in $(printf '%s\n' $SELECTED $TESTONLY | sort -u); do OUT+=("$BASE_PKG.$p.**"); done
OUT+=("$BASE_PKG.architecture.**")
# architecture may already be listed when a package named architecture is selected; dedupe keeping order
printf '%s\n' "${OUT[@]}" | awk '!s[$0]++' | paste -sd, -
