#!/usr/bin/env bash
# Starts the nginx image with the real templates and checks the security headers on static answers
# (SPA page, hashed asset, 404 asset) with and without SHIPIR_HTTPS=true (HSTS). Needs Docker; run from anywhere.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=nginxinc/nginx-unprivileged:stable-alpine
PORT=${CHECK_PORT:-18089}
name=shipir-nginx-headers-$$
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT

fail=0
check() { # description, headers, pattern
  if ! grep -qiE "$3" <<<"$2"; then echo "FAIL: $1 (missing /$3/)"; fail=1; else echo "ok:   $1"; fi
}

run() { # SHIPIR_HTTPS value
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" -p "127.0.0.1:$PORT:8080" \
    -e BACKEND_URL=http://127.0.0.1:1 -e NGINX_RESOLVER=127.0.0.11 -e SHIPIR_HTTPS="$1" \
    -v "$PWD/default.conf.template:/etc/nginx/templates/default.conf.template:ro" \
    -v "$PWD/security-headers.inc.template:/etc/nginx/templates/security-headers.inc.template:ro" \
    "$IMAGE" >/dev/null
  for _ in $(seq 40); do curl -fs -o /dev/null "http://127.0.0.1:$PORT/" && return; sleep 0.25; done
  docker logs "$name"; exit 1
}

for path in / /assets/missing.js /some/spa/route; do
  run false
  h=$(curl -si "http://127.0.0.1:$PORT$path" | tr -d '\r')
  check "$path CSP default-src" "$h" "^content-security-policy: default-src 'self'"
  check "$path CSP frame-ancestors" "$h" "frame-ancestors 'none'"
  check "$path nosniff" "$h" "^x-content-type-options: nosniff"
  check "$path X-Frame-Options" "$h" "^x-frame-options: DENY"
  check "$path Referrer-Policy" "$h" "^referrer-policy: no-referrer"
  check "$path Permissions-Policy" "$h" "^permissions-policy: camera=\(\)"
  if grep -qi "^strict-transport-security" <<<"$h"; then echo "FAIL: $path sends HSTS without SHIPIR_HTTPS"; fail=1; fi
done

run true
h=$(curl -si "http://127.0.0.1:$PORT/" | tr -d '\r')
check "HSTS when SHIPIR_HTTPS=true" "$h" "^strict-transport-security: max-age=31536000; includeSubDomains"

exit $fail
