#!/usr/bin/env bash
set -euo pipefail

# Caddy, Casdoor and oauth2-proxy in containers, bridged by socat to a frontend
# and backend running on the host. Args pass through to docker compose, so
# `down`, `logs -f`, `-d` and `--build` all work; defaults to `up`.

cd "$(dirname "$0")"

echo "app http://localhost${EXPLORAMA_HTTP_PORT:+:${EXPLORAMA_HTTP_PORT}}, casdoor http://localhost:${CASDOOR_PORT:-8000} (dev/dev123); also run ./run-dev-backend.sh and ./run-dev-frontend.sh"

exec docker compose -f docker-compose.yml -f docker-compose.dev.yml "${@:-up}"
