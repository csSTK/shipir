#!/usr/bin/env bash
# Restores a backup made by scripts/backup.sh into an EMPTY database and an EMPTY stacks volume.
# Usage: scripts/restore.sh <backup-dir>
#
# Refuses to run unless the database has no tables (so Flyway has not run yet) and the stacks volume is
# empty. Stops the backend container; never removes volumes. Requires the SHIPIR_ENCRYPTION_KEY the backup
# was made with (checked by fingerprint). See README "Backup & restore".
#
# This script restores only the pg_dump + tar backup written by scripts/backup.sh (database.dump,
# stacks.tar.gz, MANIFEST). It does NOT restore the ADMIN encrypted UI export (Settings > Backup):
# that export holds JSONL tables and stacks, and Shipir has no importer for it. A database restore
# always needs the pg_dump + tar backup.
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.yml"
ENV_FILE="$ROOT/.env"

die() { echo "restore: $*" >&2; exit 1; }

# Prints the value of KEY: environment first, then .env. Quotes are stripped; .env is never sourced.
env_value() {
  local key="$1" line value
  if [[ -n "${!key+x}" ]]; then
    printf '%s' "${!key}"
    return 0
  fi
  [[ -f "$ENV_FILE" ]] || return 0
  line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
  value="${line#"${key}="}"
  value="${value%\"}"; value="${value#\"}"
  value="${value%\'}"; value="${value#\'}"
  printf '%s' "$value"
}

[[ $# -eq 1 ]] || die "usage: scripts/restore.sh <backup-dir>"
SRC="$(cd "$1" 2>/dev/null && pwd)" || die "backup directory not found: $1"

for file in MANIFEST database.dump stacks.tar.gz; do
  [[ -f "$SRC/$file" ]] || die "incomplete backup, $file missing in $SRC"
done

for tool in pg_restore psql docker openssl; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool not found in PATH (needs the PostgreSQL 16 client tools)"
done

manifest_value() {
  local line
  line="$(grep -E "^$1=" "$SRC/MANIFEST" | tail -n 1 || true)"
  printf '%s' "${line#"$1="}"
}
[[ "$(manifest_value format)" == "shipir-backup-1" ]] || die "unknown backup format in $SRC/MANIFEST"

# Encryption key: the backup must be restored with the key it was made with.
ENCRYPTION_KEY="$(env_value SHIPIR_ENCRYPTION_KEY)"
if [[ -n "$ENCRYPTION_KEY" ]]; then
  KEY_FINGERPRINT="$(printf '%s' "$ENCRYPTION_KEY" | openssl dgst -sha256 -r | cut -d' ' -f1)"
else
  KEY_FINGERPRINT="none"
fi
[[ "$KEY_FINGERPRINT" == "$(manifest_value encryption_key_sha256)" ]] \
  || die "SHIPIR_ENCRYPTION_KEY does not match the key of this backup; set the original key and try again"

# libpq reads these; the password goes through the environment, never the command line.
export PGHOST="$(env_value DB_HOST)"
export PGHOST="${PGHOST:-localhost}"
export PGPORT="$(env_value DB_PORT)"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="$(env_value DB_NAME)"
export PGDATABASE="${PGDATABASE:-shipir}"
export PGUSER="$(env_value DB_USER)"
export PGUSER="${PGUSER:-shipir}"
export PGPASSWORD="$(env_value DB_PASSWORD)"
[[ -n "$PGPASSWORD" ]] || die "DB_PASSWORD is not set (environment or .env)"

TABLE_COUNT="$(psql -X -v ON_ERROR_STOP=1 -tA -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog', 'information_schema')")"
[[ "$TABLE_COUNT" == "0" ]] || die "database $PGDATABASE on $PGHOST is not empty ($TABLE_COUNT tables); restore only into an empty database"

BACKEND="$(docker compose -f "$COMPOSE_FILE" ps -aq backend | head -n 1)"
[[ -n "$BACKEND" ]] || die "backend container not found; create it first: docker compose -f docker-compose.yml create backend"

docker run --rm --volumes-from "$BACKEND" alpine:3 sh -c 'test -z "$(ls -A /data/stacks)"' \
  || die "the stacks volume is not empty; restore only into an empty volume"

echo "restore: this writes database $PGDATABASE on $PGHOST:$PGPORT and the stacks volume of the backend."
echo "restore: the backend container is stopped first. No volume is removed."
printf 'restore: type RESTORE to continue: '
read -r answer
[[ "$answer" == "RESTORE" ]] || die "aborted"

echo "restore: stopping backend"
docker compose -f "$COMPOSE_FILE" stop backend

echo "restore: restoring database"
pg_restore --no-owner --no-privileges --exit-on-error --dbname "$PGDATABASE" "$SRC/database.dump"

echo "restore: restoring the stacks volume"
docker run --rm -i --volumes-from "$BACKEND" alpine:3 tar -C /data/stacks -xzf - < "$SRC/stacks.tar.gz"

echo "restore: done. Start the app with: docker compose -f docker-compose.yml up -d"
echo "restore: Flyway checks the restored schema history on startup; the backend must not run before this restore."
