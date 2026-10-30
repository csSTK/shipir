#!/usr/bin/env bash
# Checks that every image tag in the bundled app templates exists in its registry.
# Usage: scripts/check-template-images.sh [apps.json]
# Read-only: uses `docker manifest inspect` (no pull, no containers). Needs docker and network access.
# Exit code 1 if any image cannot be resolved. See README "App templates".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILE="${1:-$ROOT/backend/src/main/resources/templates/apps.json}"

command -v docker >/dev/null || { echo "check-template-images: docker not found" >&2; exit 2; }
[ -f "$FILE" ] || { echo "check-template-images: $FILE not found" >&2; exit 2; }

images="$(grep -o '"image": *"[^"]*"' "$FILE" | sed 's/.*: *"\(.*\)"/\1/' | sort -u)"
[ -n "$images" ] || { echo "check-template-images: no images found in $FILE" >&2; exit 2; }

# Docker Hub limits anonymous manifest requests. When that happens, docker.io images are checked
# through the Docker Hub tag API instead (not rate limited the same way).
hub_has_tag() {
  local image="$1" repo tag
  repo="${image%:*}"; tag="${image##*:}"
  case "$repo" in */*) ;; *) repo="library/$repo" ;; esac
  case "${repo%%/*}" in *.*) return 2 ;; esac   # other registry: no Hub API
  [ "$(curl -s -o /dev/null -w '%{http_code}' "https://hub.docker.com/v2/repositories/$repo/tags/$tag")" = 200 ]
}

failed=0
while IFS= read -r image; do
  if out="$(docker manifest inspect "$image" 2>&1 >/dev/null)"; then
    echo "ok       $image"
  elif [[ "$out" == *toomanyrequests* ]] && command -v curl >/dev/null; then
    if hub_has_tag "$image"; then
      echo "ok(hub)  $image"
    else
      echo "MISSING  $image"
      failed=$((failed + 1))
    fi
  else
    echo "MISSING  $image"
    failed=$((failed + 1))
  fi
done <<< "$images"

if [ "$failed" -gt 0 ]; then
  echo "check-template-images: $failed image(s) not found" >&2
  exit 1
fi
echo "check-template-images: all images found"
