#!/usr/bin/env bash
# Backs up a Shipir hosting installation (docker-compose.yml): the PostgreSQL database, the
# Compose stacks volume and the stored volume backups. Usage: scripts/backup.sh [backup-root]   (default: ./backups)
#
# The database is external (docker-compose.yml has no PostgreSQL service), so pg_dump runs on this
# machine with PostgreSQL 16 client tools and connects to DB_HOST like the backend does.
# DB_* and SHIPIR_ENCRYPTION_KEY are read from .env (parsed, never executed) or the environment.
# The key itself is NOT written to the backup; only its SHA-256 fingerprint, so restore can detect a mismatch.
# See README "Backup & restore".
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.yml"
ENV_FILE="$ROOT/.env"

die() { echo "backup: $*" >&2; exit 1; }

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

for tool in pg_dump docker openssl; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool not found in PATH (pg_dump needs the PostgreSQL 16 client tools)"
done

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

ENCRYPTION_KEY="$(env_value SHIPIR_ENCRYPTION_KEY)"
if [[ -n "$ENCRYPTION_KEY" ]]; then
  KEY_FINGERPRINT="$(printf '%s' "$ENCRYPTION_KEY" | openssl dgst -sha256 -r | cut -d' ' -f1)"
else
  KEY_FINGERPRINT="none"
fi

BACKEND="$(docker compose -f "$COMPOSE_FILE" ps -aq backend | head -n 1)"
[[ -n "$BACKEND" ]] || die "backend container not found; create it first: docker compose -f docker-compose.yml create backend"

BACKUP_ROOT="${1:-$ROOT/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_ROOT/shipir-$STAMP"
mkdir -p "$OUT"
chmod 700 "$BACKUP_ROOT" "$OUT"

echo "backup: writing $OUT"

echo "backup: dumping database $PGDATABASE on $PGHOST:$PGPORT"
pg_dump --format=custom --no-owner --no-privileges --file "$OUT/database.dump"

echo "backup: archiving the stacks volume (mounted at /data/stacks in the backend container)"
docker run --rm --volumes-from "$BACKEND" alpine:3 tar -C /data/stacks -czf - . > "$OUT/stacks.tar.gz"

echo "backup: archiving the stored volume backups (mounted at /data/volume-backups in the backend container)"
docker run --rm --volumes-from "$BACKEND" alpine:3 tar -C /data/volume-backups -czf - . > "$OUT/volume-backups.tar.gz"

cat > "$OUT/MANIFEST" <<MANIFEST
format=shipir-backup-1
created_at=$STAMP
database=$PGDATABASE
encryption_key_sha256=$KEY_FINGERPRINT
MANIFEST

chmod 600 "$OUT/database.dump" "$OUT/stacks.tar.gz" "$OUT/volume-backups.tar.gz" "$OUT/MANIFEST"

echo "backup: done: $OUT"
if [[ "$KEY_FINGERPRINT" == "none" ]]; then
  echo "backup: WARNING: SHIPIR_ENCRYPTION_KEY is not set; stored TLS material, git and registry credentials, webhook URLs and AI keys are not encrypted here"
else
  echo "backup: keep SHIPIR_ENCRYPTION_KEY safe, separately from this backup. Without it the restored secrets cannot be decrypted."
fi
echo "backup: the backup contains stack .env files in plain text. Store it like a secret."
