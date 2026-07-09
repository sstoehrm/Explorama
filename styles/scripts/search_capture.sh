#!/usr/bin/env bash
# Usage: search_capture.sh <label>   (run from anywhere)
#
# Builds the server-bundle app (batch-1 Task-14 welcome method: gather-assets
# + cljs.main :simple + webpack bundle), serves resources/public, and drives
# a headless Chromium instance through the ONLY reachability path found for
# the search feature area (Task 1 investigation — see task-1-report.md):
#
#   1. Welcome page loads -> click ".welcome__close" ("Close overview").
#      (The welcome "Search" card's own on-click, which forces :grid frame
#      placement, dispatches through a gated check-and-dispatch loop keyed to
#      "all plugins done initializing" that NEVER resolves on this no-backend
#      static build -- confirmed by 120s-virtual-time waits. Avoid it.)
#   2. Click the always-available toolbar button "#tool-search" (the same
#      element search/core.cljs registers via tools-register; unrelated to
#      the welcome page). This arms :drop frame-placement mode (the app's
#      configured default), NOT :grid, because this path has no
#      :force :grid override -- so an explicit placement click is required.
#   3. Wait for ".window-placement-overlay" (rendered by
#      woco/frame/interaction/dnd.cljs while a drop-placement is armed) and
#      fire a *native 'mouseup' event* at a fixed viewport coordinate (200,150)
#      -- the handler is React's on-mouse-up, NOT on-click; dispatching
#      'click' here silently no-ops. This places a "Search 1" window.
#   4. Click the sidebar's "Topic/Datasource" leaf row (a plain click, not
#      an expand) to dispatch ::add-search-row, rendering an actual
#      .search__block row (label/select-input/delete-icon) -- this exercises
#      far more of _search.scss than the bare window chrome alone.
#
# All synthetic clicks use a *single* native 'click' MouseEvent per element
# (NOT a pointerdown+mousedown+pointerup+mouseup+click burst -- that burst
# was empirically found to sometimes double-toggle the search tool button,
# arming then immediately disarming drop-placement, causing ~50% flaky
# failures during Task-1 investigation).
#
# ERROR STATE (.search__block--error): NOT reachable headlessly by this
# method. The only row obtainable without a backend (Topic/Datasource) is a
# select-input whose options list is populated by a live ws-api round trip
# that never resolves against this static server, so it has no choices to
# pick and no way to become a *non-empty invalid* value (the precondition
# main_search/core.cljs's error-classes / search/views/validation.cljs
# checks). Expanding the "Geographic"/"Time period" categories to look for a
# free-text or date-type row was attempted and did not yield a reliably
# expandable row within reasonable effort. Per the batch-2 plan's Task 1
# instructions, this falls back to compiled-CSS reading + manual checks for
# the .search__block--error family (Task 7).
#
# Writes:
#   docs/superpowers/artifacts/tailwind/search-normal-<label>.png
# (search-error-<label>.png is intentionally NOT produced -- see above.)
set -euo pipefail

label="${1:?usage: search_capture.sh <label>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
art="$root/docs/superpowers/artifacts/tailwind"
port="${SEARCH_CAPTURE_PORT:-8899}"
mkdir -p "$art"

# 1. Regenerate CSS + gather assets (same as harness_capture.sh Step 1).
cd "$root/bundles/server"
bb gather-assets.bb.clj dev >/dev/null

# 2. Compile the app entry point (:simple + webpack bundle -> main_bundle.js),
#    same invocation as the batch-1 welcome-page method.
clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn \
  -c de.explorama.frontend.woco.app.core >/dev/null

# 3. Build the capture HTML: index.html + an inline script that runs the
#    reachability sequence documented above, then leaves the page settled.
#    Written into resources/public (gitignored build output) so it is never
#    committed; cleaned up on exit.
capture_html="resources/public/search-capture-harness.html"
python3 - "$capture_html" <<'PYEOF'
import sys
path = sys.argv[1]
html = open("resources/public/index.html").read()
old = ('    <script>\n'
       '        de.explorama.frontend.woco.app.core.init();\n'
       '    </script>')
assert old in html, "index.html init-script marker not found -- template changed?"
inject = """
    <script>
        de.explorama.frontend.woco.app.core.init();
        function fireClick(el) {
          var rect = el.getBoundingClientRect();
          var x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
          var ev = new MouseEvent('click', {bubbles: true, cancelable: true, clientX: x, clientY: y, view: window, button: 0});
          el.dispatchEvent(ev);
        }
        function fireMouseUpAt(el, x, y) {
          var ev = new MouseEvent('mouseup', {bubbles: true, cancelable: true, clientX: x, clientY: y, view: window, button: 0});
          el.dispatchEvent(ev);
        }
        function waitFor(selector, cb, tries, maxTries) {
          tries = tries || 0;
          var el = document.querySelector(selector);
          if (el) { cb(el); return; }
          if (tries > (maxTries || 300)) { console.log('CAPTURE TIMEOUT waiting for ' + selector); return; }
          setTimeout(function () { waitFor(selector, cb, tries + 1, maxTries); }, 200);
        }
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#tool-search', function (tool) {
              fireClick(tool);
              waitFor('.window-placement-overlay', function (overlay) {
                fireMouseUpAt(overlay, 200, 150);
                waitFor('.explorama__window--search', function () {
                  setTimeout(function () {
                    var items = document.querySelectorAll('.search__sidebar__list [role=row]');
                    var topicRow = null;
                    items.forEach(function (it) { if (it.textContent.trim() === 'Topic/Datasource') topicRow = it; });
                    if (topicRow) fireClick(topicRow);
                    console.log('CAPTURE READY');
                  }, 800);
                });
              });
            });
          });
        }, 1000);
    </script>
"""
html = html.replace(old, inject)
open(path, "w").write(html)
PYEOF

# 4. Serve resources/public.
python3 -m http.server "$port" -d resources/public >/dev/null 2>&1 &
srv=$!
trap 'kill "$srv" 2>/dev/null || true; rm -f "resources/public/search-capture-harness.html"' EXIT

for _ in $(seq 1 50); do
  if curl -sf -o /dev/null "http://localhost:$port/search-capture-harness.html"; then break; fi
  sleep 0.2
done

url="http://localhost:$port/search-capture-harness.html"

# 5. Screenshot. Generous virtual-time-budget: the app's own toolbar-tool
#    registration and drop-placement arming have been observed to take
#    anywhere from <1s to ~25s of *virtual* time to settle run-to-run
#    (WebSocket reconnect/backoff churn against the nonexistent backend is
#    real-wall-clock-bound, not virtual-time-bound) -- see task-1-report.md.
chromium --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --force-color-profile=srgb --virtual-time-budget=40000 \
  --window-size=1400,900 \
  --screenshot="$art/search-normal-$label.png" "$url" 2>/dev/null

echo "captured: $art/search-normal-$label.png"
echo "note: search-error-$label.png NOT produced -- error state not headlessly reachable (see script header / task-1-report.md); compiled-CSS reading substitutes per plan."
