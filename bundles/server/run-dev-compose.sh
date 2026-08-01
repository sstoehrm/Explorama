#!/usr/bin/env bash
set -euo pipefail

# Starts the compose harness in dev mode: Caddy, Casdoor and oauth2-proxy in
# containers, with socat bridges forwarding to a frontend and backend running
# on the host. Run run-dev-backend.sh and run-dev-frontend.sh alongside it.

cd "$(dirname "$0")"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options] [-- <docker compose args>]

Starts the dev-mode compose harness (docker-compose.yml + docker-compose.dev.yml).
Runs in the foreground; Ctrl-C stops it.

Options:
  -d, --detach   run in the background
  --down         stop and remove the harness instead of starting it
  --logs         follow the logs of an already-running harness
  -h, --help     show this help

Anything after -- is passed straight to docker compose, e.g.:
  $(basename "$0") -- --build
  $(basename "$0") --logs -- caddy

Env (see docker/README.md for the full list, .env.example for a template):
  EXPLORAMA_HTTP_PORT    host port for Caddy (default 80)
  HOST_FRONTEND_PORT     host frontend port socat forwards to (default 8020)
  HOST_BACKEND_PORT      host backend port socat forwards to (default 4001)
EOF
}

action="up"
detach=()
passthrough=()

while [ $# -gt 0 ]; do
    case "$1" in
        -d|--detach) detach=(-d); shift ;;
        --down) action="down"; shift ;;
        --logs) action="logs"; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; passthrough=("$@"); break ;;
        *) echo "Unknown option: $1" >&2; echo >&2; usage >&2; exit 1 ;;
    esac
done

if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose (v2) is required but was not found." >&2
    exit 1
fi

compose=(docker compose -f docker-compose.yml -f docker-compose.dev.yml)

case "$action" in
    down)
        exec "${compose[@]}" down "${passthrough[@]+"${passthrough[@]}"}"
        ;;
    logs)
        exec "${compose[@]}" logs -f "${passthrough[@]+"${passthrough[@]}"}"
        ;;
esac

app_url="http://localhost${EXPLORAMA_HTTP_PORT:+:${EXPLORAMA_HTTP_PORT}}"

echo "================================================"
echo "Explorama dev harness (Caddy + Casdoor + oauth2-proxy)"
echo "================================================"
echo "App:     ${app_url}"
echo "Casdoor: http://localhost:${CASDOOR_PORT:-8000}  (admin/123, dev/dev123)"
echo ""
echo "Traffic is bridged to the host, so also run:"
echo "  ./run-dev-backend.sh    (backend on :${HOST_BACKEND_PORT:-4001})"
echo "  ./run-dev-frontend.sh   (figwheel on :${HOST_FRONTEND_PORT:-8020})"
echo ""

exec "${compose[@]}" up "${detach[@]+"${detach[@]}"}" "${passthrough[@]+"${passthrough[@]}"}"
