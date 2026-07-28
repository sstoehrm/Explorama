# Tailwind Phase 2 — Batch 0+1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a component-render harness (batch 0) and migrate the 11 primitive component SCSS sheets to Tailwind utility stacks in their owning ui_base components (batch 1), with rendered-level parity proof per sheet.

**Architecture:** A harness CLJS page mounts every batch-1 ui_base component in all catalog variants, then serializes `getComputedStyle` of every DOM node into a `<pre>` so a single headless-Chromium `--dump-dom` captures DOM+styles; a screenshot is taken in the same run. Capture before any change (baseline) and after each sheet migration; diff both artifacts as the parity gate. Sheets migrate one commit each; the sheet's SCSS is deleted when its classes are fully carried by the component.

**Tech Stack:** ClojureScript (figwheel build-once, `:optimizations :none` + webpack auto-bundle — the proven server-bundle test pipeline), Babashka + Python3 for capture/diff scripts, headless Chromium.

## Global Constraints

- Utilities-in-markup is the end state (user decision): emitted classes are standard Tailwind; arbitrary-value syntax (`bg-(--border)`) allowed for theme CSS-vars.
- Class knowledge stays PRIVATE to the owning ui_base component (def'd stacks at the top of the component ns, like chip.cljs's existing `chip-base-class` pattern); callers keep the component API.
- Token mapping is phase 1's verified table: `size('N')` → spacing N/4 (`size('8')`→`gap-2`/`p-2`), `radius('md')`→`rounded-md`, `color('gray-500')`→`text-gray-500`/`bg-gray-500`, `font-size('xs')`→`text-xs`, shadows/z via phase-1 theme tokens.
- Dynamically selected class names must remain statically scannable (full class strings in code) or be safelisted via `@source inline(...)` in `styles/src/tailwind.css` (phase-1 icon-color pattern).
- Descendant rules: prefer explicit param threading to child components; else move the rule to the marked `/* phase-2 remnants */` section of `styles/src/tailwind.css` with a comment naming the owning component. Count remnants per sheet in the report.
- Verification gates per sheet (all must pass before the sheet's commit): harness screenshot pixel-identical to baseline OR every diff investigated & justified in the report; computed-style JSON diff empty OR justified; `npm run sass:dist` + `npm run tailwind:dist` clean; clj-kondo clean on touched files.
- Work on branch `tailwind-phase2-batch1` off `main`. One commit per sheet; harness/baseline commits separate.
- Baseline captured BEFORE any migration change.
- Suites (browser + server test-ci) run once at batch end (Task 15), not per sheet.

## Reference facts

- Batch-1 sheets (lines): `_hints` 58, `_tooltip` 48, `_collapsible_list` 56, `_chips` ~100, `_card` ~230, `_checkbox` ~200, `_tabs` ~100, `_buttons` 340, `_input` ~300, `_select` 555, `_forms` 670. All under `styles/src/scss/components/`.
- Owning components live under `plugins/frontend/de/explorama/frontend/ui_base/components/` — formular/{button,button_group,card,checkbox,collapsible_list,input_field,input_group,select,textarea,upload,radio,section,...}.cljs, misc/{chip,hint,...}.cljs, common/tooltip.cljs. Ownership is confirmed per sheet during its inventory step.
- ui_base components declare variants in `parameter-definition` maps with `:characteristics` vectors (see chip.cljs) — the harness catalog derives variant axes from these.
- Known cross-sheet dependency: `components/_table.scss:585` does `@extend .btn-secondary` — deleting `_buttons.scss` without handling this breaks the sass build.
- The old lein-based gallery (`tools/ui-base-overview`) is UNBUILDABLE (no lein; private `lein-explorama-sync` plugin unavailable) — issue #9 gets updated, not fixed, by this plan.
- The server bundle test pipeline (`clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci`) proves the figwheel+webpack toolchain works headless; the harness reuses that pattern with its own build id.
- Phase-1 baseline discipline and artifacts live in `docs/superpowers/artifacts/tailwind/` (gitignored).

## File Structure

- Create `bundles/server/harness/de/explorama/frontend/ui_base_harness.cljs` — the catalog page (one ns; sections per component).
- Create `bundles/server/harness.cljs.edn` — figwheel build config (`:optimizations :none`, auto-bundle webpack, like test.cljs.edn).
- Create `bundles/server/resources/public/harness.html` — loads the harness bundle.
- Modify `bundles/server/cljs.deps.edn` — add `:harness` alias (extra-path `harness`, main-opts build-once).
- Create `styles/scripts/harness_capture.sh` — build + serve + screenshot + dump styles, labeled (`before`/`after-<sheet>`).
- Create `styles/scripts/harness_diff.py` — compare two captures (PNG bytes + computed-style JSON structural diff).
- Modify per sheet: the owning `ui_base` component(s); delete the sheet; update `styles/src/scss/style.scss` (`@use` line); possibly `styles/src/tailwind.css` (remnants / `@source inline`).

---

### Task 1: Harness page, build config, and capture scripts

**Files:**
- Create: `bundles/server/harness/de/explorama/frontend/ui_base_harness.cljs`
- Create: `bundles/server/harness.cljs.edn`
- Create: `bundles/server/resources/public/harness.html`
- Modify: `bundles/server/cljs.deps.edn` (add `:harness` alias)
- Create: `styles/scripts/harness_capture.sh`
- Create: `styles/scripts/harness_diff.py`

**Interfaces:**
- Produces: `bash styles/scripts/harness_capture.sh <label>` → writes `docs/superpowers/artifacts/tailwind/harness-<label>.png` and `harness-<label>.styles.json`; `python3 styles/scripts/harness_diff.py <labelA> <labelB>` → exit 0 iff identical, else prints per-node style diffs and PNG verdict. Consumed by every later task.

- [ ] **Step 1: Write the harness namespace**

`bundles/server/harness/de/explorama/frontend/ui_base_harness.cljs`:
```clojure
(ns de.explorama.frontend.ui-base-harness
  "Render harness for the phase-2 migration: mounts batch-1 ui_base
   components in all catalog variants, then serializes computed styles of
   every node into #computed-styles so `chromium --dump-dom` captures them."
  (:require [reagent.dom :as rdom]
            [de.explorama.frontend.ui-base.components.misc.chip :refer [chip]]
            [de.explorama.frontend.ui-base.components.misc.hint :refer [hint]]
            [de.explorama.frontend.ui-base.components.common.tooltip :refer [tooltip]]
            [de.explorama.frontend.ui-base.components.formular.button :refer [button]]
            [de.explorama.frontend.ui-base.components.formular.button-group :refer [button-group]]
            [de.explorama.frontend.ui-base.components.formular.card :refer [card]]
            [de.explorama.frontend.ui-base.components.formular.checkbox :refer [checkbox]]
            [de.explorama.frontend.ui-base.components.formular.collapsible-list :refer [collapsible-list]]
            [de.explorama.frontend.ui-base.components.formular.input-field :refer [input-field]]
            [de.explorama.frontend.ui-base.components.formular.textarea :refer [textarea]]
            [de.explorama.frontend.ui-base.components.formular.radio :refer [radio]]
            [de.explorama.frontend.ui-base.components.formular.select :refer [select]]))

;; Catalog rule: for each component, render one instance per combination of
;; the variant axes its parameter-definition declares as :characteristics
;; (e.g. chip: :variant x :size x :brightness), with a stable :data-harness
;; id per instance. Fill required params with fixed literal values.
;; Derive the exact axes by reading each component's parameter-definition —
;; extend `catalog` below accordingly during implementation; the chip entry
;; shows the pattern.

(defn- section [title & children]
  (into [:div {:style {:padding "16px" :border-bottom "1px solid #ccc"}}
         [:h3 title]]
        children))

(defn- chip-section []
  (section "chip"
           (for [variant [:primary :secondary]
                 size [:extra-small :small :normal :big]
                 brightness [nil :light :dark]]
             ^{:key (str variant size brightness)}
             [:div {:data-harness (str "chip-" (name variant) "-" (name size) "-" (or (some-> brightness name) "default"))
                    :style {:margin "4px" :display "inline-block"}}
              [chip (cond-> {:variant variant :size size :label "Chip"}
                      brightness (assoc :brightness brightness))]])))

(defn- app []
  [:div#harness-root
   [chip-section]
   ;; one section per batch-1 component, same pattern:
   ;; button, button-group, card, checkbox, collapsible-list, input-field,
   ;; textarea, radio, select, hint, tooltip (render trigger; hover manual)
   ])

(defn- serialize-computed-styles! []
  (let [nodes (.querySelectorAll js/document "#harness-root *")
        entries (for [i (range (.-length nodes))
                      :let [n (aget nodes i)
                            cs (js/getComputedStyle n)]]
                  ;; stable node key: nearest data-harness ancestor + tag + index
                  [(str (some-> (.closest n "[data-harness]") (.getAttribute "data-harness"))
                        "|" (.-tagName n) "|" i)
                   (into {} (for [j (range (.-length cs))
                                  :let [prop (.item cs j)]]
                              [prop (.getPropertyValue cs prop)]))])
        pre (.createElement js/document "pre")]
    (set! (.-id pre) "computed-styles")
    (set! (.-textContent pre) (js/JSON.stringify (clj->js (into {} entries))))
    (.appendChild (.-body js/document) pre)))

(defn ^:export init []
  (rdom/render [app] (.getElementById js/document "app"))
  ;; wait a tick for reagent to flush, then serialize
  (js/setTimeout serialize-computed-styles! 1000))
```

- [ ] **Step 2: Build config + html**

`bundles/server/harness.cljs.edn`:
```clojure
^{:auto-bundle :webpack}
{:main de.explorama.frontend.ui-base-harness
 :output-to "target/public/cljs-out/harness/main.js"
 :output-dir "target/public/cljs-out/harness"
 :asset-path "/cljs-out/harness"
 :optimizations :none
 :pretty-print true}
```

`bundles/server/resources/public/harness.html`:
```html
<!doctype html>
<html><head>
  <meta charset='utf-8'><title>ui_base harness</title>
  <link rel="stylesheet" href="/css/2_woco.css">
  <link rel="stylesheet" href="/css/3_style.css">
  <link rel="stylesheet" href="/css/4_temp.css">
  <link rel="stylesheet" href="/css/5_utilities.css">
</head><body>
  <div id="app"></div>
  <script src="/cljs-out/harness/main_bundle.js"></script>
  <script>de.explorama.frontend.ui_base_harness.init();</script>
</body></html>
```
(Vendor CSS is irrelevant for these primitives; if a component visibly depends on it during implementation, add the specific `1_vendor/*.css` link and note it.)

Add to `bundles/server/cljs.deps.edn` aliases (same shape as `:test-ci`):
```clojure
:harness {:extra-paths ["harness"]
          :extra-deps {com.bhauman/figwheel-main {:mvn/version "0.2.18"}}
          :main-opts ["-m" "figwheel.main" "-bo" "harness"]}
```

- [ ] **Step 3: Capture script**

`styles/scripts/harness_capture.sh`:
```bash
#!/usr/bin/env bash
# Usage: harness_capture.sh <label>   (from repo root or anywhere)
set -euo pipefail
label="${1:?usage: harness_capture.sh <label>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
art="$root/docs/superpowers/artifacts/tailwind"
mkdir -p "$art"

cd "$root/styles" && npm run build >/dev/null
cd "$root/bundles/server"
bb gather-assets.bb.clj dev >/dev/null
clojure -Sdeps "$(cat cljs.deps.edn)" -M:harness
# figwheel build-once outputs under target/public; serve both roots
python3 -m http.server 8901 -d resources/public &
srv1=$!
ln -sfn "$root/bundles/server/target/public/cljs-out" resources/public/cljs-out
trap 'kill $srv1; rm -f resources/public/cljs-out' EXIT
sleep 1
chromium --headless --disable-gpu --no-sandbox --virtual-time-budget=30000 \
  --window-size=1400,2400 --screenshot="$art/harness-$label.png" \
  "http://localhost:8901/harness.html" 2>/dev/null
chromium --headless --disable-gpu --no-sandbox --virtual-time-budget=30000 \
  --dump-dom "http://localhost:8901/harness.html" 2>/dev/null \
  | python3 -c "
import sys, re, html
m = re.search(r'<pre id=\"computed-styles\">(.*?)</pre>', sys.stdin.read(), re.S)
assert m, 'computed-styles pre not found — harness init failed'
open('$art/harness-$label.styles.json','w').write(html.unescape(m.group(1)))
"
echo "captured: $art/harness-$label.{png,styles.json}"
```

- [ ] **Step 4: Diff script**

`styles/scripts/harness_diff.py`:
```python
#!/usr/bin/env python3
"""Usage: harness_diff.py <labelA> <labelB> — exit 0 iff captures identical."""
import json, sys, hashlib, pathlib

art = pathlib.Path(__file__).resolve().parents[2] / "docs/superpowers/artifacts/tailwind"
a, b = sys.argv[1], sys.argv[2]

png_a = (art / f"harness-{a}.png").read_bytes()
png_b = (art / f"harness-{b}.png").read_bytes()
png_same = hashlib.md5(png_a).hexdigest() == hashlib.md5(png_b).hexdigest()
print(f"PNG: {'IDENTICAL' if png_same else 'DIFFERS'}")

sa = json.loads((art / f"harness-{a}.styles.json").read_text())
sb = json.loads((art / f"harness-{b}.styles.json").read_text())
diffs = 0
for key in sorted(set(sa) | set(sb)):
    if key not in sa or key not in sb:
        print(f"NODE {'+' if key in sb else '-'} {key}"); diffs += 1; continue
    for prop in sorted(set(sa[key]) | set(sb[key])):
        va, vb = sa[key].get(prop), sb[key].get(prop)
        if va != vb:
            print(f"{key} :: {prop}: {va!r} -> {vb!r}"); diffs += 1
print(f"style diffs: {diffs}")
sys.exit(0 if (png_same and diffs == 0) else 1)
```

- [ ] **Step 5: Verify the harness end-to-end (unmigrated tree)**

```bash
chmod +x styles/scripts/harness_capture.sh
bash styles/scripts/harness_capture.sh smoke
python3 - <<'EOF'
import json, pathlib
d = json.loads(pathlib.Path("docs/superpowers/artifacts/tailwind/harness-smoke.styles.json").read_text())
assert len(d) > 100, f"only {len(d)} nodes — catalog too thin or render failed"
print(len(d), "nodes captured")
EOF
```
Also Read the PNG: it must show the component grid (not a blank page).
Determinism check: `bash styles/scripts/harness_capture.sh smoke2 && python3 styles/scripts/harness_diff.py smoke smoke2` → exit 0. If PNG differs between identical runs (fonts/AA nondeterminism), fix the capture (fixed window size, `--hide-scrollbars`, longer budget) until deterministic — determinism is a hard requirement for the whole batch.

- [ ] **Step 6: Commit**

```bash
git add bundles/server/harness bundles/server/harness.cljs.edn \
  bundles/server/resources/public/harness.html bundles/server/cljs.deps.edn \
  styles/scripts/harness_capture.sh styles/scripts/harness_diff.py
git commit -m "tailwind: ui_base render harness (screenshots + computed-style dumps)"
```

---

### Task 2: Baseline capture + issue #9 update

- [ ] **Step 1: Capture the pre-migration baseline**

```bash
bash styles/scripts/harness_capture.sh baseline
```
Verify (Read PNG; node count as in Task 1 Step 5). This baseline is the reference for ALL batch-1 sheet gates.

- [ ] **Step 2: Update issue #9**

```bash
gh issue comment 9 -R sstoehrm/Explorama -b "Investigated while starting Tailwind phase 2: tools/ui-base-overview is unbuildable from a clean checkout — no lein toolchain, and the lein-explorama-sync plugin (0.13.0) is private/unavailable (404 on Clojars). Phase 2 built a replacement render harness (bundles/server/harness + styles/scripts/harness_capture.sh) used as the migration's verification gate. Suggest repurposing this issue: decide whether to delete tools/ui-base-overview or port it to deps.edn."
```

No commit (artifacts are gitignored).

---

### Tasks 3–13: one task per sheet — THE RECIPE

Sheets in order (simplest first, hardest last):
Task 3 `_tooltip.scss` · Task 4 `_hints.scss` · Task 5 `_collapsible_list.scss` · Task 6 `_chips.scss` · Task 7 `_tabs.scss` · Task 8 `_card.scss` · Task 9 `_checkbox.scss` · Task 10 `_buttons.scss` · Task 11 `_input.scss` · Task 12 `_select.scss` · Task 13 `_forms.scss`

Every sheet task follows this exact recipe (shown once; each task's implementer receives it in full):

- [ ] **Step 1: Inventory.** List every selector in the sheet. Classify each:
  - (a) simple rule on a class the owning component emits,
  - (b) state variant (`&:hover`, `&:focus`, `&.active`, `&.disabled`, `&.light`),
  - (c) descendant/compound rule,
  - (d) candidate-dead. For (d): grep the class across ALL of plugins/, bundles/*/frontend, and the sheet's sibling sheets (`@extend`) — zero hits = delete, record in report; any hit = classify as (a/b/c) for that usage site.
  Also grep OTHER sheets for `@extend`/`@include` referencing this sheet's classes/placeholders (known: `_table.scss:585 @extend .btn-secondary` for Task 10) — inline the extended declarations into the dependent sheet with a `// phase-2: inlined from _buttons.scss, migrate with this sheet's batch` comment.
  Also find NON-ui_base usage sites of the sheet's classes (plugin views using the class string directly): if ≤5 sites, migrate them in this task; if more, STOP and report BLOCKED with the count (the sheet may belong in a domain batch).

- [ ] **Step 2: Translate.** Move each rule into the owning component as utility stacks (Global Constraints: token table, private defs, variant prefixes for CSS-driven states, param threading for descendant rules, remnants only as last resort). Follow chip.cljs's existing def-at-top pattern. Conditional selection uses full class strings (scannability).

- [ ] **Step 3: Delete the sheet** and its `@use 'components/<name>';` line in `styles/src/scss/style.scss`.

- [ ] **Step 4: Gates.**
```bash
cd styles && npm run sass:dist && npm run tailwind:dist   # both clean
bash scripts/harness_capture.sh after-<sheet>
python3 scripts/harness_diff.py baseline after-<sheet>
clj-kondo --lint <touched cljs files>                      # clean
```
The diff gate: exit 0, OR every reported difference is investigated and justified in the report (e.g. sub-pixel AA — justify with pixel counts; a genuinely wrong style = fix before commit). Hover/focus states the harness can't capture: verify by reading the emitted variant classes against the old SCSS declarations, list them in the report as manually-verified.

- [ ] **Step 5: Commit** — `git commit -m "tailwind: migrate _<sheet>.scss to <component ns>"` with body: selector counts (migrated/dead-deleted/remnants), non-ui_base sites touched, manual-check list.

**Per-task specifics:**
- Task 3 `_tooltip`: owning ns `common/tooltip.cljs`; tooltip renders on hover — harness renders the trigger; the tooltip body styles are verified by forcing `:show? true` (check the param definition; if a force-show param exists use it in the catalog).
- Task 6 `_chips`: worked example in the spec; the icon-tinting descendant rule (`@include icon-color(...)`) becomes an explicit icon color param passed to the `icon` child, or a remnant.
- Task 9 `_checkbox`: uses `%control` placeholder (`@extend %control`) shared across checkbox/radio/switch — migrate the placeholder's declarations into a shared private def in the checkbox ns (radio/switch live there too — confirm via inventory) or duplicate per component if they diverge; do NOT create a cross-ns "shared styles" util for 2 call sites.
- Task 10 `_buttons`: handle `_table.scss:585 @extend .btn-secondary` per Step 1; `button.cljs` + `button_group.cljs` are both owners.
- Task 12 `_select`: 555 lines and blueprint/react-select vendor interplay — inventory step must check which selectors target vendor-emitted DOM (e.g. `.select__single-value`, seen in phase-1 ground truth). Vendor-DOM selectors cannot become markup utilities: they move to the remnants section (expected largest remnant count; that is acceptable and reported).
- Task 13 `_forms`: 670 lines, multiple owners (input_group, section, upload, radio…) — the inventory may split the work across several components; if the sheet decomposes cleanly, migrate all in this task; if any part has >5 non-ui_base sites, carve ONLY that part into a `_forms_domain.scss` residual sheet (new file, `@use`'d from style.scss, comment header naming the deferring batch) and report it.

---

### Task 14: Batch-end verification

- [ ] **Step 1: Suites**
```bash
cd bundles/server && clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci 2>&1 | grep -E 'testsuites|success'
cd ../browser && npm run test-ci 2>&1 | tail -5
```
Expected: server 71/0; browser 140/0 (same as phase-1 numbers).

- [ ] **Step 2: App-level screenshot** (welcome page, same method as phase 1):
```bash
cd ../server && bb gather-assets.bb.clj dev
clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn -c de.explorama.frontend.woco.app.core
cd resources/public && python3 -m http.server 8899 & sleep 1
chromium --headless --disable-gpu --no-sandbox --virtual-time-budget=40000 \
  --window-size=1400,900 --screenshot=/home/soeren/repos/private/Explorama/docs/superpowers/artifacts/tailwind/phase2-welcome.png \
  http://localhost:8899/ ; kill %1
```
Compare visually against `docs/superpowers/artifacts/tailwind/baseline-welcome-reliable.png` (welcome page uses cards/buttons — must be identical or justified).

- [ ] **Step 3: Remnant + scannability audit**
```bash
grep -c "phase-2 remnants" -A200 styles/src/tailwind.css || true   # count remnant rules, list in report
grep -rn '@source inline' styles/src/tailwind.css                   # list safelists added
```
Report totals: sheets deleted, lines removed, remnants added (with owners), safelists.

- [ ] **Step 4: Commit any stragglers; update CLAUDE.md** Styles section if the remnants section or new safelists exist (one line each).

---

### Task 15: PR

- [ ] Push `tailwind-phase2-batch1`, open PR against main titled "Tailwind phase 2, batch 1: migrate primitive component sheets to utilities", body: per-sheet stats table (from commit messages), harness description, gates evidence, remnant list, references #5, notes user spot-check screens (any screen with buttons/forms/selects — suggest search view and a dialog).
