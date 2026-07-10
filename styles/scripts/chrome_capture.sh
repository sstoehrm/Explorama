#!/usr/bin/env bash
# Usage: chrome_capture.sh <label> <screen>   (run from anywhere)
#   screen ∈ {frame-toolbar, sidebar-open, dialog, login, welcome}
#
# Batch-4 (woco chrome) counterpart of dr_capture.sh (batch-3) / search_capture.sh
# (batch-2) -- same method (batch-1 Task-14 build: gather-assets + cljs.main
# :simple + webpack bundle; serve resources/public; headless Chromium
# DOM-click navigation through React's own handlers, since cljs/re-frame are
# NOT exposed on `window` after webpack bundling).
#
# Reachability investigation (Task 1 -- see task-1-report.md for detail):
#
#   - No URL/hash routing exists for any of these screens (same finding as
#     batch-2/batch-3: no de.explorama.frontend.*.standalone.* route handler
#     consumes a URL anywhere in the live tree).
#   - frame-toolbar: reuses batch-2's search drop-placement sequence VERBATIM
#     (close welcome -> click `#tool-search` (search/config.cljs's
#     `tool-name`, a `:tool-group :bar` item, plain-id path) -> wait for
#     `.window-placement-overlay` (armed by woco/frame/interaction/dnd.cljs)
#     -> fire a native 'mouseup' MouseEvent at a fixed viewport coordinate
#     (200,150) (the handler is React's on-mouse-up, not on-click) -> wait for
#     `.explorama__window--search`. The placed frame's root class list is
#     built by woco/frame/view/core.cljs's `build-frame-classes`, which
#     literally includes `config/window-class` = "frame" (config.cljs L54,
#     `;;TODO change this to frame, however currently many classes depend on
#     it` -- i.e. the "frame" class is ALREADY live, not aspirational) plus
#     "explorama__window" (legacy) and "bg-white" -- so the placed window's
#     header (rendered by woco/frame/view/header.cljs's `frame-header`) is a
#     genuine `.frame>.header` match, and its footer toolbar (rendered by
#     woco/frame/view/toolbar.cljs's `toolbar-comp`, the SAME
#     ui_base/components/misc/toolbar.cljs owner as the always-visible left
#     nav toolbar) is a genuine `.toolbar` match. One screenshot exercises
#     both families the screen name promises.
#   - sidebar-open: reuses batch-3's presentation entry point up to (not
#     including) "Add Slide". `#navbar-item-presentation-edit-icon-presentation`
#     (woco/tools.cljs's `header-tool` id scheme, `"navbar-item-" + id + "-" +
#     icon`, confirmed by batch-3's Task 1 dump-dom investigation) enters
#     `:editing` mode via `woco/presentation/core.cljs`'s `::toggle-modes`,
#     whose `toggle-modes-fx` (L415) unconditionally conjoins
#     `[woco.presentation.sidebar/open-window]` onto `:dispatch-n` whenever
#     `mode = :editing` -- `open-window` calls the shared
#     `:sidebar-create-event-vec` FI API, i.e. `woco/sidebar.cljs`'s
#     `::create-sidebar`, which renders `[:div.header ...]`+content with
#     `:class-name "sidebar show"` (sidebar.cljs L140) -- the EXACT
#     `.sidebar.show` selector the plan calls for. No "Add Slide" click is
#     needed for this screen; the sidebar opens as a direct side effect of
#     entering editing mode.
#   - dialog: extends the sidebar-open sequence one step further, because the
#     presentation sidebar's only local (non-backend) dialog trigger,
#     "Remove all slides" (`presentation-remove-all-button`, dispatches
#     `woco.presentation.confirmation-dialog/ask-for-confirmation`), is
#     disabled while `no-slides?` is true (sidebar.cljs L237-242,
#     `:disabled? (boolean (or read-only? no-slides? overlayer-active?))`) --
#     so "Add Slide" (`::pres-core/spawn-new-slide`, computed purely from the
#     current viewport, no existing frame/window required -- batch-3's Task 1
#     finding) is clicked first to get >=1 slide, THEN "Remove all slides" is
#     clicked once it loses its `disabled` attribute. This dispatches
#     `confirmation-dialog/ask-for-confirmation`, which sets `:show? true` on
#     `woco/presentation/confirmation_dialog.cljs`'s `confirmation-dialog`,
#     rendering `ui_base/components/frames/dialog.cljs`'s `[:div.overlay
#     [:div.dialog ...]]` -- a warning-type confirm dialog (title "Remove all
#     slides", Yes/No buttons) -- the exact `.overlay .dialog` selector the
#     plan calls for. Entirely local state; no backend involved.
#   - login: `body.login` is NOT reachable through the running app.
#     `woco/page.cljs`'s `main-panel` hardcodes `logged-in? true` (L317,
#     original `db-get-error-boundary`/`:logged-in?` lookup is commented out)
#     and, on EVERY render, unconditionally adds "login" to `<body>` (L327-328)
#     THEN immediately removes it again inside the (always-true) `logged-in?`
#     branch (L333-334) -- both DOM mutations happen synchronously inside the
#     same render call, so "login" never persists across a committed React
#     frame; it is not a timing race that a longer wait could win. The class
#     IS present in the raw pre-hydration markup, though:
#     `resources/public/index.html` ships `<body class="initial login">`
#     (checked-in template, confirmed by reading the file) and `_login.scss`'s
#     `body.login {background: url(...), #182f3d}` is a plain CSS rule on the
#     body element itself, requiring no React content. So this screen is
#     captured by serving index.html with its `app.core.init()` bootstrap
#     script REMOVED (not merely deferred) -- cljs never mounts, "login"
#     never gets removed, and the browser paints the raw HTML + CSS as-is.
#     `.explorama-overlay` (the About/datalink overlay, `woco/copyright.cljs`'s
#     `sheet`) is NOT reachable even with the app running: it is gated on
#     `(= "TRIAL"|"PRODUCTION" config/environment)`, and `config/environment`
#     reads `js/window.EXPLORAMA_ENVIRONMENT` (config.cljs L20), which
#     `index.html` never sets (grep clean) -- so the footer links that would
#     dispatch `::set-datalink` (the ONLY way to make the overlay's `show?`
#     truthy) do not exist in this build at all. Confirmed dead-end, not a
#     timing/reachability gap; falls back to compiled-CSS reading.
#   - welcome: always captured (batch-1/2/3 regression floor), no injection --
#     plain load, matching the batch-1/2/3 method exactly.
#
# Click-firing rules follow batch-2/3's hard-won lessons verbatim: exactly one
# native 'click' MouseEvent per element (a multi-event burst was found to
# double-toggle toggle-style buttons), patient long-poll waits (no retries),
# and text-content matching for buttons without a stable id/class.
#
# Writes:
#   docs/superpowers/artifacts/tailwind/chrome-<screen>-<label>.png
set -euo pipefail

label="${1:?usage: chrome_capture.sh <label> <screen>}"
screen="${2:?usage: chrome_capture.sh <label> <screen>  (screen: frame-toolbar|sidebar-open|dialog|login|welcome)}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
art="$root/docs/superpowers/artifacts/tailwind"
port="${CHROME_CAPTURE_PORT:-8897}"
mkdir -p "$art"

case "$screen" in
  frame-toolbar|sidebar-open|dialog|login|welcome) ;;
  *) echo "unknown screen: $screen (expected frame-toolbar|sidebar-open|dialog|login|welcome)" >&2; exit 2 ;;
esac

# 1. Regenerate CSS + gather assets (same as harness_capture.sh Step 1).
cd "$root/bundles/server"
bb gather-assets.bb.clj dev >/dev/null

# 2. Compile the app entry point (:simple + webpack bundle -> main_bundle.js).
clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn \
  -c de.explorama.frontend.woco.app.core >/dev/null

# 3. Build the capture HTML: index.html + an inline script that drives the
#    screen's navigation sequence (documented above), or nothing extra for
#    "welcome" (plain load is the welcome screen itself), or the init call
#    REMOVED entirely for "login" (see header comment).
capture_html="resources/public/chrome-capture-harness.html"
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

if screen == "login":
    # Do NOT call init() at all -- cljs must never mount, so the raw
    # `<body class="initial login">` + body.login CSS background is what
    # gets painted (see header comment: main-panel's logged-in?=true branch
    # removes "login" synchronously on the very first render, so any
    # bootstrap at all loses this screen).
    html = html.replace(old, '    <!-- init() intentionally not called: login screen capture -->')
    open(path, "w").write(html)
    raise SystemExit(0)

helpers = """
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
        function waitForEnabledText(selector, text, cb, tries, maxTries) {
          tries = tries || 0;
          var found = null;
          document.querySelectorAll(selector).forEach(function (el) {
            if (!found && el.textContent.trim() === text && !el.disabled) found = el;
          });
          if (found) { cb(found); return; }
          if (tries > (maxTries || 300)) { console.log('CAPTURE TIMEOUT waiting for enabled ' + selector + ' text=' + text); return; }
          setTimeout(function () { waitForEnabledText(selector, text, cb, tries + 1, maxTries); }, 200);
        }
"""

if screen == "welcome":
    body = """
        setTimeout(function () {
          console.log('CAPTURE READY');
        }, 1000);
"""
elif screen == "frame-toolbar":
    body = """
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#tool-search', function (tool) {
              fireClick(tool);
              waitFor('.window-placement-overlay', function (overlay) {
                fireMouseUpAt(overlay, 200, 150);
                waitFor('.explorama__window--search', function () {
                  waitFor('.frame>.header', function () {
                    setTimeout(function () { console.log('CAPTURE READY'); }, 500);
                  });
                });
              });
            });
          });
        }, 1000);
"""
elif screen == "sidebar-open":
    body = """
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#navbar-item-presentation-edit-icon-presentation', function (tool) {
              fireClick(tool);
              waitFor('.sidebar.show', function () {
                setTimeout(function () { console.log('CAPTURE READY'); }, 500);
              });
            });
          });
        }, 1000);
"""
elif screen == "dialog":
    body = """
        setTimeout(function () {
          waitFor('.welcome__close', function (closeBtn) {
            fireClick(closeBtn);
            waitFor('#navbar-item-presentation-edit-icon-presentation', function (tool) {
              fireClick(tool);
              waitFor('.sidebar.show', function () {
                waitForText('.card__list__ordered .card__button', 'Add Slide', function (addBtn) {
                  fireClick(addBtn);
                  waitFor('.slide__container', function () {
                    waitForEnabledText('button', 'Remove all slides', function (removeBtn) {
                      fireClick(removeBtn);
                      waitFor('.overlay .dialog', function () {
                        setTimeout(function () { console.log('CAPTURE READY'); }, 500);
                      });
                    });
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

# 4. Serve resources/public. A FRESH --user-data-dir per invocation avoids
#    any Chromium profile-cache cross-talk between runs (the documented
#    remedy for the welcome page's i18n async cold-start flakiness -- see
#    task-1-report.md).
udd="$(mktemp -d)"
python3 -m http.server "$port" -d resources/public >/dev/null 2>&1 &
srv=$!
trap 'kill "$srv" 2>/dev/null || true; rm -f "resources/public/chrome-capture-harness.html"; rm -rf "$udd"' EXIT

for _ in $(seq 1 50); do
  if curl -sf -o /dev/null "http://localhost:$port/chrome-capture-harness.html"; then break; fi
  sleep 0.2
done

url="http://localhost:$port/chrome-capture-harness.html"

# 5. Screenshot. Generous virtual-time-budget -- same rationale as
#    dr_capture.sh: toolbar-tool registration and i18n/data settling have
#    been observed to take anywhere from <1s to ~25s of *virtual* time run to
#    run (WebSocket reconnect/backoff against the nonexistent backend is
#    real-wall-clock-bound, not virtual-time-bound).
chromium --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --force-color-profile=srgb --virtual-time-budget=40000 \
  --window-size=1400,900 --user-data-dir="$udd" \
  --screenshot="$art/chrome-$screen-$label.png" "$url" 2>/dev/null

echo "captured: $art/chrome-$screen-$label.png"
