#!/usr/bin/env bash
# Checks that every VOLUME an app image declares is mounted by its template, so no state lands in an
# anonymous volume that is lost when the container is recreated (shipr-0qbh.40).
# Usage: scripts/check-template-data-paths.sh [apps.json]
# Read-only (pulls missing images): `docker image inspect`; nothing is ever started. Needs docker, python3 and network access for images not yet pulled. Exit code 1 on a gap.
# Declared VOLUMEs that are deliberately not persisted are listed in ALLOW below. The reverse direction (the
# template mounts the path the app really uses) is pinned by TemplateDataPathsTest.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="${1:-$ROOT/backend/src/main/resources/templates/apps.json}"

# image-prefix:path pairs, each with the reason it is safe to leave unmounted.
ALLOW=(
  "ghcr.io/paperless-ngx/paperless-ngx:/usr/src/paperless/export"  # target of the manual document_exporter only
  "mongo:/data/configdb"                                           # sharding config, unused by a standalone mongod
)

command -v docker >/dev/null || { echo "check-template-data-paths: docker not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "check-template-data-paths: python3 not found" >&2; exit 2; }
[ -f "$FILE" ] || { echo "check-template-data-paths: $FILE not found" >&2; exit 2; }

# One line per service: <image><TAB><comma separated mount targets>
services="$(python3 -I - "$FILE" <<'PY'
import json, sys
for t in json.load(open(sys.argv[1])):
    for s in t["spec"]["services"]:
        print(s["image"] + "\t" + ",".join(v["target"] for v in (s.get("volumes") or [])))
PY
)"

allowed() { # image path
  local entry
  for entry in "${ALLOW[@]}"; do
    entry="${entry%%  *}"
    [[ "$1" == "${entry%:*}"* && "$2" == "${entry##*:}" ]] && return 0
  done
  return 1
}

failed=0
while IFS=$'\t' read -r image targets; do
  docker image inspect -- "$image" >/dev/null 2>&1 || docker pull -q -- "$image" >/dev/null
  declared="$(docker image inspect --format '{{range $p, $_ := .Config.Volumes}}{{$p}}{{"\n"}}{{end}}' -- "$image")"
  status=ok
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    case ",$targets," in *",$path,"*) continue ;; esac
    allowed "$image" "$path" && continue
    echo "UNMOUNTED $image declares VOLUME $path, template mounts: ${targets:-nothing}"
    failed=$((failed + 1)); status=bad
  done <<< "$declared"
  if [ -z "$declared" ]; then echo "no VOLUME declared (not checked)  $image"
  elif [ "$status" = ok ]; then echo "ok        $image"; fi
done <<< "$services"

if [ "$failed" -gt 0 ]; then
  echo "check-template-data-paths: $failed declared volume(s) not mounted" >&2
  exit 1
fi
echo "check-template-data-paths: all declared volumes are mounted"
