#!/usr/bin/env bash
set -euo pipefail

# Figwheel dev build on :8020 with hot reloading. The compose harness's socat
# bridge forwards to this port.

cd "$(dirname "$0")"

port="${HOST_FRONTEND_PORT:-8020}"

# A stale figwheel JVM or headless Chromium on this port otherwise fails with a
# bare "Address already in use".
if ss -lptn "sport = :$port" 2>/dev/null | grep -q LISTEN; then
    ss -lptn "sport = :$port" >&2
    exit 1
fi

[ -d node_modules ] || npm install
# figwheel's :css-dirs must exist or the build aborts with a spec explanation
# that never names the missing directory.
[ -d resources/public/css ] || bb gather-assets.bb.clj dev

echo "figwheel http://localhost:$port"

exec clojure -Sdeps "$(cat cljs.deps.edn)" -M:dev
