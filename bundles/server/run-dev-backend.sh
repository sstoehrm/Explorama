#!/usr/bin/env bash
set -euo pipefail

# Backend dev REPL: nREPL on :7888, HTTP on :4001. Binds 0.0.0.0 so the compose
# harness's socat bridge reaches it from its container; the application itself
# defaults to loopback.

cd "$(dirname "$0")"

export EXPLORAMA_BIND_ADDRESS="${EXPLORAMA_BIND_ADDRESS:-0.0.0.0}"
export EXPLORAMA_PORT="${EXPLORAMA_PORT:-4001}"

if ss -lptn "sport = :$EXPLORAMA_PORT" 2>/dev/null | grep -q LISTEN; then
    ss -lptn "sport = :$EXPLORAMA_PORT" >&2
    exit 1
fi

echo "backend http://$EXPLORAMA_BIND_ADDRESS:$EXPLORAMA_PORT, nrepl :7888"

exec clojure -Sdeps "$(cat clj.deps.edn)" -M:dev
