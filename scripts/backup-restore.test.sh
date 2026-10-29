#!/usr/bin/env bash
# Shell test for backup.sh and restore.sh with stubbed docker, pg_dump, pg_restore and psql on PATH.
# Touches no real Docker, database or volume. Usage: scripts/backup-restore.test.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export STUB_LOG="$TMP/calls.log"

# docker stub: logs every call; "tar -C <dir> -czf -" emits "ARCHIVE:<dir>", "-xzf -" records stdin.
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "$STUB_LOG"
if [[ "$1" == "compose" ]]; then
  [[ " $* " == *" ps "* ]] && echo "stub-backend-id"
  exit 0
fi
if [[ "$*" == *" tar -C "* ]]; then
  dir=""; mode=""
  prev=""
  for arg in "$@"; do
    [[ "$prev" == "-C" ]] && dir="$arg"
    [[ "$arg" == "-czf" ]] && mode=create
    [[ "$arg" == "-xzf" ]] && mode=extract
    prev="$arg"
  done
  if [[ "$mode" == "create" ]]; then printf 'ARCHIVE:%s' "$dir"; else printf 'EXTRACTED:%s:%s\n' "$dir" "$(cat)" >> "$STUB_LOG"; fi
  exit 0
fi
exit "${STUB_EMPTY_CHECK_EXIT:-0}"
STUB
cat > "$TMP/bin/pg_dump" <<'STUB'
#!/usr/bin/env bash
file=""; prev=""
for arg in "$@"; do [[ "$prev" == "--file" ]] && file="$arg"; prev="$arg"; done
printf 'dump' > "$file"
STUB
cat > "$TMP/bin/pg_restore" <<'STUB'
#!/usr/bin/env bash
echo "pg_restore $*" >> "$STUB_LOG"
STUB
cat > "$TMP/bin/psql" <<'STUB'
#!/usr/bin/env bash
echo 0
STUB
chmod +x "$TMP"/bin/*
export PATH="$TMP/bin:$PATH"
export DB_PASSWORD=x SHIPIR_ENCRYPTION_KEY=k

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_file() { [[ -f "$1" ]] || fail "missing $1"; }

"$HERE/backup.sh" "$TMP/out" > /dev/null
B="$(echo "$TMP"/out/shipir-*)"
assert_file "$B/database.dump"
[[ "$(cat "$B/stacks.tar.gz")" == "ARCHIVE:/data/stacks" ]] || fail "stacks archive"
[[ "$(cat "$B/volume-backups.tar.gz")" == "ARCHIVE:/data/volume-backups" ]] || fail "volume-backups archive"

# Restore of a full backup restores both volumes.
: > "$STUB_LOG"
echo RESTORE | "$HERE/restore.sh" "$B" > /dev/null
grep -q 'EXTRACTED:/data/stacks:ARCHIVE:/data/stacks' "$STUB_LOG" || fail "stacks not restored"
grep -q 'EXTRACTED:/data/volume-backups:ARCHIVE:/data/volume-backups' "$STUB_LOG" || fail "volume backups not restored"

# Old backup without the third part restores without it.
rm "$B/volume-backups.tar.gz"
: > "$STUB_LOG"
echo RESTORE | "$HERE/restore.sh" "$B" > /dev/null
grep -q 'EXTRACTED:/data/stacks' "$STUB_LOG" || fail "old backup: stacks not restored"
! grep -q 'volume-backups' "$STUB_LOG" || fail "old backup: volume-backups touched"

# Non-empty volume is refused and nothing is extracted.
cp "$B/stacks.tar.gz" "$B/volume-backups.tar.gz"
: > "$STUB_LOG"
if echo RESTORE | STUB_EMPTY_CHECK_EXIT=1 "$HERE/restore.sh" "$B" > /dev/null 2>&1; then fail "non-empty volume accepted"; fi
! grep -q 'EXTRACTED' "$STUB_LOG" || fail "extracted into non-empty volume"

echo "backup-restore.test.sh: all passed"
