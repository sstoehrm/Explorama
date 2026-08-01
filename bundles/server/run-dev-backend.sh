#!/usr/bin/env bash
set -euo pipefail

# Runs the backend for local development: an nREPL on :7888 with the HTTP
# server on :4001. Binds 0.0.0.0 by default so the compose harness's socat
# bridge can reach it from its container.

cd "$(dirname "$0")"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Starts the backend dev REPL (nREPL :7888, HTTP :4001).

Options:
  --assets       rebuild the style assets before starting, even if present
  --no-assets    skip the asset check entirely
  -h, --help     show this help

Env:
  EXPLORAMA_BIND_ADDRESS   interface to listen on (default 0.0.0.0 here, so the
                           compose harness can reach the host; the application
                           itself defaults to 127.0.0.1)
  EXPLORAMA_PORT           HTTP port (default 4001)

Run ./run-dev-compose.sh in another terminal for auth and routing, and
./run-dev-frontend.sh for the UI.
EOF
}

assets="auto"

while [ $# -gt 0 ]; do
    case "$1" in
        --assets) assets="force"; shift ;;
        --no-assets) assets="skip"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; echo >&2; usage >&2; exit 1 ;;
    esac
done

port="${EXPLORAMA_PORT:-4001}"

if command -v ss >/dev/null 2>&1 && ss -lptn "sport = :${port}" 2>/dev/null | grep -q LISTEN; then
    echo "Port ${port} is already in use:" >&2
    ss -lptn "sport = :${port}" 2>/dev/null | tail -n +2 >&2
    echo "Stop that process or set EXPLORAMA_PORT to a free port." >&2
    exit 1
fi

if [ "$assets" = "force" ] || { [ "$assets" = "auto" ] && [ ! -d resources/public/css ]; }; then
    echo "Gathering assets..."
    bb gather-assets.bb.clj dev
fi

echo "================================================"
echo "Explorama backend (dev)"
echo "================================================"
echo "HTTP:  http://${EXPLORAMA_BIND_ADDRESS:-0.0.0.0}:${port}"
echo "nREPL: localhost:7888"
echo ""

export EXPLORAMA_BIND_ADDRESS="${EXPLORAMA_BIND_ADDRESS:-0.0.0.0}"
export EXPLORAMA_PORT="$port"

exec clojure -Sdeps "$(cat clj.deps.edn)" -M:dev
