#!/usr/bin/env bash
set -euo pipefail

# Runs the ClojureScript frontend for local development: Figwheel on :8020 with
# hot reloading. The compose harness's socat bridge forwards to this port.

cd "$(dirname "$0")"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Starts the Figwheel dev build with a REPL (port 8020).

Options:
  --assets       rebuild the style assets before starting, even if present
  --no-assets    skip the asset check entirely
  -h, --help     show this help

Env:
  HOST_FRONTEND_PORT   port to check for availability (default 8020; the port
                       itself comes from figwheel-main.edn)

Run ./run-dev-compose.sh in another terminal for auth and routing, and
./run-dev-backend.sh for the backend.
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

port="${HOST_FRONTEND_PORT:-8020}"

# A stale figwheel JVM or headless Chromium on this port makes the build die
# with "Address already in use" instead of a useful message.
if command -v ss >/dev/null 2>&1 && ss -lptn "sport = :${port}" 2>/dev/null | grep -q LISTEN; then
    echo "Port ${port} is already in use:" >&2
    ss -lptn "sport = :${port}" 2>/dev/null | tail -n +2 >&2
    echo "Stop that process (a stale figwheel REPL or test run) and retry." >&2
    exit 1
fi

if [ ! -d node_modules ]; then
    echo "Installing npm dependencies..."
    npm install
fi

if [ "$assets" = "force" ] || { [ "$assets" = "auto" ] && [ ! -d resources/public/css ]; }; then
    echo "Gathering assets..."
    bb gather-assets.bb.clj dev
fi

# figwheel's :css-dirs must exist or the build aborts with a spec explanation
# that never mentions the missing directory.
if [ ! -d resources/public/css ]; then
    echo "resources/public/css is missing, and figwheel refuses to start without it." >&2
    echo "Run without --no-assets, or: bb gather-assets.bb.clj dev" >&2
    exit 1
fi

echo "================================================"
echo "Explorama frontend (dev)"
echo "================================================"
echo "Figwheel: http://localhost:${port}"
echo "Through the harness: http://localhost${EXPLORAMA_HTTP_PORT:+:${EXPLORAMA_HTTP_PORT}}"
echo ""

exec clojure -Sdeps "$(cat cljs.deps.edn)" -M:dev
