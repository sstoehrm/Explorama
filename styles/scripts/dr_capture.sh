#!/usr/bin/env bash
# Usage: dr_capture.sh <label> <screen>   (run from anywhere)
#   screen ∈ {welcome, dashboard-overview, report, presentation}
#
# Batch-3 (dashboards/reporting) counterpart of search_capture.sh -- same
# method (batch-1 Task-14 build: gather-assets + cljs.main :simple + webpack
# bundle; serve resources/public; headless Chromium DOM-click navigation
# through React's own handlers, since cljs/re-frame are NOT exposed on
# `window` after webpack bundling -- see search_capture.sh's header and
# task-1-report.md for the batch-2 precedent this reuses verbatim).
#
# Reachability investigation (Task 1 -- see task-1-report.md for detail):
#
#   - No URL/hash routing exists for any reporting/presentation screen
#     (option (a) ruled out, same as batch-2).
#   - `#navbar-item-tool-reporting-icon-reporting` (reporting/plugin/core.cljs's
#     tools-register) and `#navbar-item-presentation-edit-icon-presentation`
#     (woco/core.cljs's tools-register) are both always-available
#     header-toolbar buttons. NOTE: unlike `#tool-search` (batch 2), whose
#     `:tool-group :bar` renders through woco/tools.cljs's plain-id
#     `gen-tool-item`/left-`toolbar`, these two are `:tool-group :header`,
#     rendered through the DIFFERENT `header-tool`/`header-section` path,
#     whose DOM id is `"navbar-item-" + id + "-" + icon`, NOT the bare
#     registered `:id` -- confirmed by dumping the live DOM (a first attempt
#     using the bare `#tool-reporting`/`#presentation-edit` ids timed out;
#     `--dump-dom` showed the real ids above). `header-tool`'s on-click also
#     gates on `product-tour/component-active?`, but that predicate is
#     unconditionally true whenever no onboarding tour step is active
#     (`(or (nil? current-step) ...)` in woco/api/product_tour.cljs), which
#     is the case here, so it does not block the synthetic click.
#   - dashboard-overview: `::dashboards/request-dashboards` /
#     `::reports/request-reports` are backend-tube calls that never resolve
#     against this static no-backend server (same class of gap as batch-2's
#     search-error state) -- the overview's :created/:shared dashboard maps
#     stay empty forever, so literal "dashboard cards" are NOT reachable.
#     The richest reachable landing (option (c)) is one step further: click
#     "Create Dashboard" -> select a template card (data/templates.cljs's
#     template catalog is a LOCAL hardcoded map, no backend involved) -> the
#     filled `.dashboard__layout`/`.dashboard__item` grid renders. This
#     exercises the bulk of _dashboards.scss's actual grid-system selectors
#     (more than the empty-overview landing would), so this script drives
#     into the builder rather than stopping at the bare empty overview.
#   - report: same backend gap for a saved report's a4-viewer
#     (`de.explorama.frontend.reporting.views.reports.view/report-view` is
#     ONLY invoked from `::r-overview/show-report`, which requires backend-
#     sourced `visible-dr` state) -- NOT reachable headlessly. The closest
#     reachable landing is "Create Report" -> the report template-builder
#     (`.report__container.in-app`, a DIFFERENT markup variant from the
#     a4-viewer's `.a4-container`/`.report__header` family) -- captured as
#     the best-effort substitute; the a4-viewer-specific selectors fall back
#     to compiled-CSS reading + manual checks per the batch-3 plan.
#   - presentation: fully reachable, no backend involved. The header-toolbar
#     presentation button enters :editing mode (woco/presentation/core.cljs's
#     ::toggle-modes),
#     which opens the presentation sidebar; its "Add Slide" button dispatches
#     `::presentation/spawn-new-slide` directly off the current viewport (no
#     existing frame/window required), giving >=1 slide and rendering the
#     `.slide__container`/`.slide__frame` overlay (page.cljs only renders
#     these in :editing mode) plus the sidebar's `.presentation__settings`
#     chrome -- covering nearly all of _presentation.scss in one shot.
#     (`.presentation__progress` has zero emitters anywhere -- dead code,
#     confirmed by grep -- so no capture path needs to reach it.)
#
# Click-firing rules follow batch-2's hard-won lessons verbatim: exactly one
# native 'click' MouseEvent per element (a multi-event burst was found to
# double-toggle toggle-style buttons), patient long-poll waits (no retries),
# and text-content matching for buttons without a stable id/class (mirrors
# search_capture.sh's "Topic/Datasource" row match).
#
# Writes:
#   docs/superpowers/artifacts/tailwind/dr-<screen>-<label>.png
set -euo pipefail

label="${1:?usage: dr_capture.sh <label> <screen>}"
screen="${2:?usage: dr_capture.sh <label> <screen>  (screen: welcome|dashboard-overview|report|presentation)}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
art="$root/docs/superpowers/artifacts/tailwind"
port="${DR_CAPTURE_PORT:-8898}"
mkdir -p "$art"

case "$screen" in
  welcome|dashboard-overview|report|presentation) ;;
  *) echo "unknown screen: $screen (expected welcome|dashboard-overview|report|presentation)" >&2; exit 2 ;;
esac

# 1. Regenerate CSS + gather assets (same as harness_capture.sh Step 1).
cd "$root/bundles/server"
bb gather-assets.bb.clj dev >/dev/null

# 2. Compile the app entry point (:simple + webpack bundle -> main_bundle.js).
clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn \
  -c de.explorama.frontend.woco.app.core >/dev/null

# 3. Build the capture HTML: index.html + an inline script that drives the
#    screen's navigation sequence (documented above), or nothing extra for
#    "welcome" (plain load is the welcome screen itself).
capture_html="resources/public/dr-capture-harness.html"
SCREEN="$screen" python3 - "$capture_html" <<'PYEOF'
import os
import sys

path = sys.argv[1]
screen = os.environ["SCREEN"]
html = open("resources/public/index.html").read()
old = ('    <script>\n'
       '        de.explorama.frontend.woco.app.core.init();\n'
       '    </script>')
assert old in html, "index.html init-script marker not found -- template changed?"

helpers = """
        function fireClick(el) {
          var rect = el.getBoundingClientRect();
          var x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
          var ev = new MouseEvent('click', {bubbles: true, cancelable: true, clientX: x, clientY: y, view: window, button: 0});
          el.dispatchEvent(ev);
        }
        function waitFor(selector, cb, tries, maxTries) {
          tries = tries || 0;
          var el = document.querySelector(selector);
          if (el) { cb(el); return; }
          if (tries > (maxTries || 300)) { console.log('CAPTURE TIMEOUT waiting for ' + selector); return; }
          setTimeout(function () { waitFor(selector, cb, tries + 1, maxTries); }, 200);
        }
        function waitForText(selector, text, cb, tries, maxTries) {
          tries = tries || 0;
          var found = null;
          document.querySelectorAll(selector).forEach(function (el) {
            if (!found && el.textContent.trim() === text) found = el;
          });
          if (found) { cb(found); return; }
          if (tries > (maxTries || 300)) { console.log('CAPTURE TIMEOUT waiting for ' + selector + ' text=' + text); return; }
          setTimeout(function () { waitForText(selector, text, cb, tries + 1, maxTries); }, 200);
        }
        function waitForEnabled(selector, cb, tries, maxTries) {
          tries = tries || 0;
          var el = document.querySelector(selector);
          if (el && !el.disabled) { cb(el); return; }
          if (tries > (maxTries || 300)) { console.log('CAPTURE TIMEOUT waiting for enabled ' + selector); return; }
          setTimeout(function () { waitForEnabled(selector, cb, tries + 1, maxTries); }, 200);
        }
"""

# Each sequence starts by closing the welcome overlay (batch-2's proven
# entry point), except "welcome" itself which must NOT close it -- the
# welcome page IS the screen being captured.
if screen == "welcome":
    body = """
        setTimeout(function () {
          console.log('CAPTURE READY');
        }, 1000);
"""
elif screen == "dashboard-overview":
    body = """
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#navbar-item-tool-reporting-icon-reporting', function (tool) {
              fireClick(tool);
              waitForText('button', 'Create Dashboard', function (createBtn) {
                fireClick(createBtn);
                waitFor('ul.select-layout li', function (firstTemplate) {
                  fireClick(firstTemplate);
                  waitFor('.dashboard__container.in-app .dashboard__layout', function () {
                    setTimeout(function () { console.log('CAPTURE READY'); }, 500);
                  });
                });
              });
            });
          });
        }, 1000);
"""
elif screen == "report":
    body = """
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#navbar-item-tool-reporting-icon-reporting', function (tool) {
              fireClick(tool);
              waitForText('button', 'Create Report', function (createBtn) {
                fireClick(createBtn);
                waitFor('.report__container.in-app', function () {
                  setTimeout(function () { console.log('CAPTURE READY'); }, 500);
                });
              });
            });
          });
        }, 1000);
"""
elif screen == "presentation":
    body = """
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#navbar-item-presentation-edit-icon-presentation', function (tool) {
              fireClick(tool);
              waitForText('.card__list__ordered .card__button', 'Add Slide', function (addBtn) {
                fireClick(addBtn);
                waitFor('.slide__container', function () {
                  waitForEnabled('.presentation__settings > button', function () {
                    setTimeout(function () { console.log('CAPTURE READY'); }, 500);
                  });
                });
              });
            });
          });
        }, 1000);
"""
else:
    raise SystemExit("unhandled screen: " + screen)

inject = ("\n    <script>\n"
          "        de.explorama.frontend.woco.app.core.init();\n"
          + helpers + body +
          "    </script>\n")
html = html.replace(old, inject)
open(path, "w").write(html)
PYEOF

# 4. Serve resources/public.
python3 -m http.server "$port" -d resources/public >/dev/null 2>&1 &
srv=$!
trap 'kill "$srv" 2>/dev/null || true; rm -f "resources/public/dr-capture-harness.html"' EXIT

for _ in $(seq 1 50); do
  if curl -sf -o /dev/null "http://localhost:$port/dr-capture-harness.html"; then break; fi
  sleep 0.2
done

url="http://localhost:$port/dr-capture-harness.html"

# 5. Screenshot. Generous virtual-time-budget -- same rationale as
#    search_capture.sh: toolbar-tool registration and i18n/data settling
#    have been observed to take anywhere from <1s to ~25s of *virtual* time
#    run to run (WebSocket reconnect/backoff against the nonexistent
#    backend is real-wall-clock-bound, not virtual-time-bound).
chromium --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --force-color-profile=srgb --virtual-time-budget=40000 \
  --window-size=1400,900 \
  --screenshot="$art/dr-$screen-$label.png" "$url" 2>/dev/null

echo "captured: $art/dr-$screen-$label.png"
