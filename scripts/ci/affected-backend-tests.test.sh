#!/usr/bin/env bash
# Shell test for affected-backend-tests.sh against a small fixture source tree (no git, no Maven).
# Usage: scripts/ci/affected-backend-tests.test.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/affected-backend-tests.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Fixture: alpha <- beta <- gamma (gamma imports beta, beta imports alpha), delta is independent.
B="$TMP/backend/src"
for p in alpha beta gamma delta common; do
  mkdir -p "$B/main/java/dev/bethge/shipir/$p" "$B/test/java/dev/bethge/shipir/$p"
  echo "package dev.bethge.shipir.$p;" > "$B/main/java/dev/bethge/shipir/$p/Thing.java"
  echo "package dev.bethge.shipir.$p;" > "$B/test/java/dev/bethge/shipir/$p/ThingTest.java"
done
mkdir -p "$B/main/java/dev/bethge/shipir/gamma/sub" "$B/test/java/dev/bethge/shipir/architecture"
echo "import dev.bethge.shipir.alpha.Thing;" >> "$B/main/java/dev/bethge/shipir/beta/Thing.java"
echo "import dev.bethge.shipir.beta.Thing;" >> "$B/main/java/dev/bethge/shipir/gamma/sub/Deep.java"
echo "package dev.bethge.shipir.architecture;" > "$B/test/java/dev/bethge/shipir/architecture/ArchitectureTest.java"

FAILS=0
ARCH='dev.bethge.shipir.architecture.**'

# run <expected stdout> <description> <changed path>...
run() {
  local expected="$1" desc="$2"; shift 2
  local actual err
  err="$TMP/err"
  actual="$(printf '%s\n' "$@" | BACKEND_ROOT="$TMP/backend" "$SCRIPT" 2>"$err")" || true
  if [[ "$actual" == "$expected" ]]; then
    echo "ok   $desc"
  else
    echo "FAIL $desc: expected '$expected', got '$actual'"; FAILS=$((FAILS + 1))
  fi
  LAST_ERR="$(cat "$err")"
}

J=backend/src/main/java/dev/bethge/shipir
T=backend/src/test/java/dev/bethge/shipir

run "dev.bethge.shipir.delta.**,$ARCH" "single package selects it plus architecture" "$J/delta/Thing.java"
run "dev.bethge.shipir.delta.**,$ARCH" "test-only change selects only that package" "$T/delta/ThingTest.java"
run "dev.bethge.shipir.alpha.**,dev.bethge.shipir.beta.**,dev.bethge.shipir.gamma.**,$ARCH" \
  "main change selects transitive reverse importers" "$J/alpha/Thing.java"
run "dev.bethge.shipir.beta.**,dev.bethge.shipir.gamma.**,$ARCH" \
  "closure goes only toward importers, not toward imports" "$J/beta/Thing.java"
case "$LAST_ERR" in *"package beta"*) echo "ok   reasons are logged to stderr";; *) echo "FAIL reasons not logged"; FAILS=$((FAILS + 1));; esac
run "dev.bethge.shipir.alpha.**,dev.bethge.shipir.beta.**,dev.bethge.shipir.gamma.**,$ARCH" \
  "nested package maps to its top-level package" "$J/alpha/nested/Deep.java"
run "$ARCH" "architecture test change selects only the architecture tests" "$T/architecture/ArchitectureTest.java"

for f in backend/pom.xml backend/mvnw backend/.mvn/wrapper/maven-wrapper.properties \
  backend/src/main/resources/application.yml backend/src/main/resources/application-dev.yml \
  backend/src/main/resources/db/migration/V1__x.sql "$J/common/Thing.java" "$J/ShipirApplication.java" \
  "$T/PostgresTestConfiguration.java" "$T/SetupGateFixture.java" "$T/MutableClock.java" \
  "$T/host/FooTestConfiguration.java" "$T/host/HostApiTestBase.java" backend/src/test/resources/application.properties \
  scripts/ci/affected-backend-tests.sh .github/workflows/tests.yml .github/workflows/ci.yml; do
  run "FULL" "FULL trigger: $f" "$f"
done
run "FULL" "FULL wins over a package change" "$J/delta/Thing.java" backend/pom.xml

run "NONE" "non-backend paths are ignored" docs/readme.md frontend/src/app/App.tsx README.md backend/Dockerfile
run "NONE" "empty input selects nothing"
run "dev.bethge.shipir.delta.**,$ARCH" "non-backend paths do not disturb a package change" frontend/src/a.ts "$J/delta/Thing.java"

if (( FAILS > 0 )); then echo "$FAILS test(s) failed"; exit 1; fi
echo "all passed"
