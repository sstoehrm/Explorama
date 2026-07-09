#!/usr/bin/env bash
# Usage: harness_capture.sh <label>   (run from anywhere)
#
# Builds the current styles + the ui_base render harness, serves it, and writes
#   docs/superpowers/artifacts/tailwind/harness-<label>.png
#   docs/superpowers/artifacts/tailwind/harness-<label>.styles.json
# The PNG is a coarse visual check; the styles.json (computed style of every
# rendered node) is the exact diff surface consumed by harness_diff.py.
set -euo pipefail

label="${1:?usage: harness_capture.sh <label>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
art="$root/docs/superpowers/artifacts/tailwind"
port="${HARNESS_PORT:-8901}"
mkdir -p "$art"

# 1. Regenerate CSS from source (scss + tailwind) and gather it into the server
#    bundle's resources/public/css. gather-assets runs styles/build.sh, which
#    covers the styles `npm run build`, so no separate build step is needed.
cd "$root/bundles/server"
bb gather-assets.bb.clj dev >/dev/null

# 1b. Build the harness-only utilities sheet (production tailwind.css plus
#     the harness markup as an extra @source), so harness-only utility
#     classes never land in the shipped 5_utilities.css. build.sh's `main`
#     moves (not copies) styles/dist/* into ../assets, so by the time
#     gather-assets above finishes, styles/dist/css no longer exists -- this
#     sheet is generated after the fact and does NOT ride along with that
#     wholesale copy, so it's copied into resources/public/css explicitly.
cd "$root/styles"
npm run tailwind:harness >/dev/null
cp dist/css/5_utilities.harness.css "$root/bundles/server/resources/public/css/"
cd "$root/bundles/server"

# 2. Compile the harness (figwheel build-once + webpack auto-bundle -> one
#    self-contained main_bundle.js under target/public/cljs-out/harness).
clojure -Sdeps "$(cat cljs.deps.edn)" -M:harness

# 3. Serve resources/public with the compiled cljs-out symlinked in.
ln -sfn "$root/bundles/server/target/public/cljs-out" resources/public/cljs-out
python3 -m http.server "$port" -d resources/public >/dev/null 2>&1 &
srv=$!
trap 'kill "$srv" 2>/dev/null || true; rm -f resources/public/cljs-out' EXIT

# Wait for the server to answer instead of a fixed sleep.
for _ in $(seq 1 50); do
  if curl -sf -o /dev/null "http://localhost:$port/harness.html"; then break; fi
  sleep 0.2
done

url="http://localhost:$port/harness.html"
common=(--headless --disable-gpu --no-sandbox --hide-scrollbars
        --force-color-profile=srgb --virtual-time-budget=30000)

# 4a. Screenshot (fixed viewport -> deterministic pixels). Height must exceed
#     the full catalog (~2650px for the frozen batch-1 catalog) so no section
#     is clipped from the pixel gate.
chromium "${common[@]}" --window-size=1400,3000 \
  --screenshot="$art/harness-$label.png" "$url" 2>/dev/null

# 4b. Dump the DOM and extract the serialized computed-styles JSON.
dump_file=$(mktemp)
trap "rm -f '$dump_file'" EXIT
chromium "${common[@]}" --dump-dom "$url" 2>/dev/null > "$dump_file"

# Fail if the capture did not settle (harness should mark unsettled state)
if grep -q 'settle-warning' "$dump_file"; then
  echo "FATAL: capture did not settle"
  exit 1
fi

python3 -c "
import sys, re, html
m = re.search(r'<pre id=\"computed-styles\">(.*?)</pre>', open('$dump_file').read(), re.S)
assert m, 'computed-styles pre not found -- harness init failed'
open('$art/harness-$label.styles.json','w').write(html.unescape(m.group(1)))
"

echo "captured: $art/harness-$label.{png,styles.json}"
