# Pixi Map Integration Stage 1 (Cutover) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the OpenLayers backend of the `map` plugin with the PixiJS engine: delete `impl/openlayers` + `ol`/`ol-ext`, implement both map protocols in `impl/pixi` over the existing `map/pixi/*` engine, with core features real and advanced features alert-stubbed.

**Architecture:** Thin `deftype` adapters (`PixiObjectManager`, `PixiStateHandler`) translate the plugin's two protocols onto the standalone engine. Engine gains: render-done signal, lazy/headless boot, per-marker styling from server style maps, highlight, cluster on/off, visible-id filtering, batch fence, base-layer switching, resize/destroy, dbl-click + mouse-preference wiring. Popup is a Reagent DOM overlay fed by the ported HTML content generator.

**Tech Stack:** ClojureScript 1.12.134, PixiJS 7.4.2 (`pixi.js-legacy`), Reagent, cljs.test. **No new npm dependencies** (spec rule: every new lib needs explicit user approval — stage 1 uses none).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-25-pixi-map-integration-stage1-design.md`. Baseline suite: **161/0/0** via `npm run test-ci` from `bundles/browser`.
- **Coordinate orders (critical):** plugin boundary uses `[lat lon]` degrees (marker locations, popup positions, `move-to`/`view-position` centers). The engine (`map/pixi/*`) uses `[lon lat]`. Adapters convert at the boundary; never change the engine convention.
- New test namespaces MUST be registered in BOTH `bundles/browser/test/de/explorama/test_runner.cljs` and `test_runner_ci.cljs`.
- Task ordering: OpenLayers sources are ported from while they still exist — deletion happens only in Task 9.
- Alert-stub rule: user-triggered unavailable features notify once per `[frame-id feature]`; cleanup/list paths are silent no-ops.
- Commits: conventional style, one per task (unless a task states otherwise), body ending with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Verification for engine/adapter tasks without unit tests: `clojure -M -m figwheel.main --build-once sandbox` (from `bundles/browser`) compiles clean + `clj-kondo --lint` on changed files clean. Never write temp files to bare `/tmp`.

## Key Data Shapes (reference for all tasks)

- **marker-data** (from `set-marker-data`/`create-markers`): `{event-id-str [[bucket event-id] location style-map]}` where `location` = vector of `[lat lon]` pairs (use the first), `style-map` = `{:color "#hex" :fillColor "#hex" :fill true :stroke false :weight 3 :radius <int> :fillOpacity <0..1>}`.
- **engine marker** (what `engine/set-markers!` receives): `{:id <str> :event-id [bucket id] :lon <deg> :lat <deg> :color <int> :color-hex "#hex" :radius <int> :alpha <0..1> :highlighted? <bool>}`.
- **content-desc** (popup): `{:data <attr-map> :title-color <hex> :title-attributes [..] :display-attributes ([..] | :all) :area-feature-id <opt>}`.
- **base-layer desc**: `{:type "default"|"tms"|"wms"|"esri" :name <str> :tilemap-server-url <url-template> :attribution <str> :max-zoom <int> :min-zoom <int, default 1> :default <bool>}`.
- **view-position** return / **move-to** args: `{:center [lat lon] :zoom <num>}` / `(move-to instance zoom [lat lon])`.
- **extra-fns callbacks** (signatures from `map/map/core.cljs:218-293`): `(:marker-clicked f)` args `[event-id event-color clicked-position view-position]`; `(:marker-dbl-clicked f)` args `[mouse-event event-id]`; `(:highlight-event f)` args `[id location]`; `(:track-view-position-change f)` args `[mouse-leave?]` (call with `true` after a gesture ends to persist position); `(:hide-popup f)` no args; `(:do-panning? f)` takes the raw pointer event.

## File Structure

**Create** (`plugins/frontend/de/explorama/frontend/map/map/impl/pixi/`):
- `popup_content.cljs` — HTML string generation for popups (ported, pure).
- `stubs.cljs` — `notify-unavailable!` (throttled) + silent no-op helpers.
- `instance.cljs` — per-frame adapter state registry (engine ref, caches, pending ops).
- `object_manager.cljs` — `PixiObjectManager` deftype.
- `state_handler.cljs` — `PixiStateHandler` deftype + popup overlay.

**Create/modify engine** (`plugins/frontend/de/explorama/frontend/map/pixi/`):
- Create `settle.cljs` (render-done bookkeeping, pure) and `style.cljs` (marker conversion, pure).
- Modify `engine.cljs`, `markers.cljs`, `tiles.cljs`, `sandbox.cljs`.

**Modify:** `map/map/api.cljs` (backend switch), both test runners, `CLAUDE.md` (dependency note), `bundles/{browser,electron/frontend,server}/package.json`.

**Delete (Task 9 only):** `plugins/frontend/de/explorama/frontend/map/map/impl/openlayers/**`, `plugins/frontend_test/de/explorama/frontend/map/impl/openlayers/util_test.cljs`.

**Tests** (`plugins/frontend_test/de/explorama/frontend/map/`): `map/impl/pixi/popup_content_test.cljs`, `pixi/settle_test.cljs`, `pixi/style_test.cljs`, `map/impl/pixi/stubs_test.cljs`, `map/impl/pixi/instance_test.cljs`.

---

### Task 1: Port popup HTML content generation (pure, TDD-by-port)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/map/impl/pixi/popup_content.cljs`
- Create: `plugins/frontend_test/de/explorama/frontend/map/map/impl/pixi/popup_content_test.cljs`
- Modify: both test runners (add new namespace; leave the old `impl.openlayers.util-test` registration in place — it is removed in Task 9).

**Interfaces:**
- Produces: `(gen-popup-content ...)` with the EXACT signature and behavior of `de.explorama.frontend.map.map.impl.openlayers.util/gen-popup-content` (source: `plugins/frontend/de/explorama/frontend/map/map/impl/openlayers/util.cljs:382-420`).

- [ ] **Step 1: Port the test file.** Copy `plugins/frontend_test/de/explorama/frontend/map/impl/openlayers/util_test.cljs` to `plugins/frontend_test/de/explorama/frontend/map/map/impl/pixi/popup_content_test.cljs`. Change the `ns` to `de.explorama.frontend.map.map.impl.pixi.popup-content-test` and the require of the openlayers util to `[de.explorama.frontend.map.map.impl.pixi.popup-content :as popup-content]`, adjusting call sites (`util/gen-popup-content` → `popup-content/gen-popup-content`). Keep all 5 testing blocks and expected HTML strings byte-identical.
- [ ] **Step 2: Register `[de.explorama.frontend.map.map.impl.pixi.popup-content-test]` in BOTH test runners.**
- [ ] **Step 3: Run `npm run test-ci` (from `bundles/browser`) — expect FAIL: namespace `...impl.pixi.popup-content` missing.**
- [ ] **Step 4: Port the implementation.** Create `popup_content.cljs` with ns `de.explorama.frontend.map.map.impl.pixi.popup-content`; copy `gen-popup-content` and every private helper it uses from `map/map/impl/openlayers/util.cljs:382-420` (follow its local calls — copy the transitive private helpers too, but nothing OL-specific; the function builds plain HTML strings and must not require any `ol` namespace. If a helper imports `ol`, stop and report BLOCKED with the helper name).
- [ ] **Step 5: Run `npm run test-ci` — expect PASS (161 → 162 deftests, no regressions).**
- [ ] **Step 6: Commit** `feat(map): port popup html content generation for pixi backend`.

---

### Task 2: `settle` — render-done bookkeeping (pure, TDD)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/settle.cljs`
- Create: `plugins/frontend_test/de/explorama/frontend/map/pixi/settle_test.cljs`
- Modify: both test runners.

**Interfaces:**
- Produces (pure; state is a plain map threaded by the caller):
  - `(new-state) -> state` — `{:pending 0 :render-requested? false :listeners []}`
  - `(add-listener state f) -> state'`
  - `(note-render state) -> [state' fired]` — marks a render request; fires (returns and clears) listeners when `:pending` is 0.
  - `(note-load-start state) -> state'` — increments `:pending`.
  - `(note-load-end state) -> [state' fired]` — decrements `:pending` (floor 0); fires listeners when reaching 0 with `:render-requested?` true (which resets to false when firing).

- [ ] **Step 1: Write the failing test** `plugins/frontend_test/de/explorama/frontend/map/pixi/settle_test.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.settle-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.settle :as settle]))

(deftest fires-immediately-when-nothing-pending
  (let [s (-> (settle/new-state) (settle/add-listener :a))
        [s' fired] (settle/note-render s)]
    (is (= [:a] fired))
    (is (empty? (:listeners s')))))

(deftest waits-for-pending-loads
  (let [s (-> (settle/new-state) (settle/add-listener :a) settle/note-load-start)
        [s1 fired1] (settle/note-render s)
        [_ fired2] (settle/note-load-end s1)]
    (is (empty? fired1))
    (is (= [:a] fired2))))

(deftest multiple-loads-fire-once-at-zero
  (let [s (-> (settle/new-state) (settle/add-listener :a)
              settle/note-load-start settle/note-load-start)
        [s1 _] (settle/note-render s)
        [s2 fired1] (settle/note-load-end s1)
        [_ fired2] (settle/note-load-end s2)]
    (is (empty? fired1))
    (is (= [:a] fired2))))

(deftest listener-added-after-settle-needs-new-render
  (let [[s _] (settle/note-render (settle/new-state))
        s (settle/add-listener s :late)
        [_ fired] (settle/note-render s)]
    (is (= [:late] fired))))

(deftest load-end-never-goes-negative
  (let [[s fired] (settle/note-load-end (settle/new-state))]
    (is (zero? (:pending s)))
    (is (empty? fired))))
```

- [ ] **Step 2: Register `[de.explorama.frontend.map.pixi.settle-test]` in both runners.**
- [ ] **Step 3: `npm run test-ci` — expect FAIL (namespace missing).**
- [ ] **Step 4: Implement** `plugins/frontend/de/explorama/frontend/map/pixi/settle.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.settle)

(defn new-state []
  {:pending 0 :render-requested? false :listeners []})

(defn add-listener [state f]
  (update state :listeners conj f))

(defn- fire [state]
  [(assoc state :listeners [] :render-requested? false)
   (:listeners state)])

(defn note-render [state]
  (let [state (assoc state :render-requested? true)]
    (if (and (zero? (:pending state)) (seq (:listeners state)))
      (fire state)
      [state []])))

(defn note-load-start [state]
  (update state :pending inc))

(defn note-load-end [state]
  (let [state (update state :pending #(max 0 (dec %)))]
    (if (and (zero? (:pending state))
             (:render-requested? state)
             (seq (:listeners state)))
      (fire state)
      [state []])))
```

- [ ] **Step 5: `npm run test-ci` — expect PASS, no regressions.**
- [ ] **Step 6: Commit** `feat(map): render-done settle bookkeeping for pixi engine`.

---

### Task 3: `style` — server style-map → engine marker conversion (pure, TDD)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/style.cljs`
- Create: `plugins/frontend_test/de/explorama/frontend/map/pixi/style_test.cljs`
- Modify: both test runners.

**Interfaces:**
- Produces:
  - `(hex->int "#d62728") -> 0xd62728` — accepts `"#rrggbb"`/`"rrggbb"`; anything unparsable → `0x000000`.
  - `(marker-data->marker [id [event-id location style]]) -> engine-marker | nil` — nil when `location` is empty/missing. Uses the FIRST `[lat lon]` pair; produces the engine-marker shape from Key Data Shapes (`:color` from `:fillColor`, `:color-hex` the original string, `:alpha` from `:fillOpacity` default 0.7, `:radius` default 6, `:highlighted? false`).
  - `(markers-map->engine-markers marker-data highlighted-ids visible-ids) -> vector` — converts all entries, drops nils, sets `:highlighted?` from `highlighted-ids` (set of event-id tuples), filters to `visible-ids` (set of id strings; `nil` = all).

- [ ] **Step 1: Write the failing test:**

```clojure
(ns de.explorama.frontend.map.pixi.style-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.style :as style]))

(deftest hex-parsing
  (is (= 0xd62728 (style/hex->int "#d62728")))
  (is (= 0x1f77b4 (style/hex->int "1f77b4")))
  (is (= 0x000000 (style/hex->int nil)))
  (is (= 0x000000 (style/hex->int "nope"))))

(def entry ["ev-1" [["bucket" "ev-1"]
                    [[52.5 13.4] [48.1 11.6]]
                    {:fillColor "#ff0000" :radius 8 :fillOpacity 0.5}]])

(deftest marker-conversion
  (let [m (style/marker-data->marker entry)]
    (is (= "ev-1" (:id m)))
    (is (= ["bucket" "ev-1"] (:event-id m)))
    (is (= 13.4 (:lon m)))   ; first pair, [lat lon] -> lon
    (is (= 52.5 (:lat m)))
    (is (= 0xff0000 (:color m)))
    (is (= "#ff0000" (:color-hex m)))
    (is (= 8 (:radius m)))
    (is (= 0.5 (:alpha m)))
    (is (false? (:highlighted? m)))))

(deftest conversion-defaults-and-nil
  (is (nil? (style/marker-data->marker ["x" [["b" "x"] [] {}]])))
  (let [m (style/marker-data->marker ["x" [["b" "x"] [[1 2]] {}]])]
    (is (= 6 (:radius m)))
    (is (= 0.7 (:alpha m)))))

(deftest bulk-conversion-highlight-and-visibility
  (let [data {"a" [["b" "a"] [[50 10]] {:fillColor "#00ff00"}]
              "c" [["b" "c"] [[51 11]] {:fillColor "#0000ff"}]}
        ms (style/markers-map->engine-markers data #{["b" "c"]} nil)
        visible (style/markers-map->engine-markers data #{} #{"a"})]
    (is (= 2 (count ms)))
    (is (true? (:highlighted? (first (filter #(= "c" (:id %)) ms)))))
    (is (= ["a"] (mapv :id visible)))))
```

- [ ] **Step 2: Register `[de.explorama.frontend.map.pixi.style-test]` in both runners.**
- [ ] **Step 3: `npm run test-ci` — FAIL (namespace missing).**
- [ ] **Step 4: Implement:**

```clojure
(ns de.explorama.frontend.map.pixi.style
  (:require [clojure.string :as str]))

(defn hex->int [hex]
  (let [h (some-> hex (str/replace #"^#" ""))
        n (when (and h (re-matches #"[0-9a-fA-F]{6}" h))
            (js/parseInt h 16))]
    (or n 0x000000)))

(defn marker-data->marker
  [[id [event-id location style]]]
  (when-let [[lat lon] (first location)]
    {:id id
     :event-id event-id
     :lon lon
     :lat lat
     :color (hex->int (:fillColor style))
     :color-hex (:fillColor style)
     :radius (or (:radius style) 6)
     :alpha (or (:fillOpacity style) 0.7)
     :highlighted? false}))

(defn markers-map->engine-markers [marker-data highlighted-ids visible-ids]
  (into []
        (comp (keep marker-data->marker)
              (filter #(or (nil? visible-ids) (contains? visible-ids (:id %))))
              (map #(assoc % :highlighted?
                           (contains? (or highlighted-ids #{}) (:event-id %)))))
        marker-data))
```

- [ ] **Step 5: `npm run test-ci` — PASS, no regressions.**
- [ ] **Step 6: Commit** `feat(map): marker style conversion for pixi backend`.

---

### Task 4: Engine control surface (cluster toggle, per-marker style, highlight, batch fence, base-layer switch, resize/destroy, input wiring)

**Files:**
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs`, `markers.cljs`, `tiles.cljs`, `sandbox.cljs`

**Interfaces:**
- Consumes: engine state atom as today; `viewport/fit-extent`.
- Produces (all on `engine.cljs` unless noted):
  - `create!` opts gain `{:tile-template <str|nil> :max-zoom <int> :on-dbl-pick <fn|nil> :do-panning? <fn|nil> :on-gesture-end <fn|nil> :preserve-drawing-buffer? <bool>}`. `tile-template nil` → tile layer renders nothing until set. Application opts include `:preserveDrawingBuffer` when requested. Default viewport stays caller-supplied.
  - `(set-cluster! engine bool)` — when false, `render-nodes!` receives every marker as a single node (no clustering).
  - `(set-visible-ids! engine ids-or-nil)` — set of marker `:id`s to show; nil = all. Stored in state; the node callback filters markers before clustering.
  - `(set-highlighted! engine id-set)` — set of `:id`s; markers re-rendered with `:highlighted?`; highlighted markers additionally get a red outline ring (see markers step).
  - `(begin-batch! engine)` / `(end-batch! engine)` — between them `notify` is suppressed; `end-batch!` runs one notify.
  - `(set-tile-template! engine template max-zoom)` — swaps template, clamps viewport zoom to `max-zoom`, destroys all cached tile sprites, notify.
  - `(resize! engine)` — re-reads canvas client size, `.resize` the renderer, updates viewport `:width`/`:height`, notify.
  - `(destroy! engine)` — idempotent: removes DOM listeners, destroys Pixi app (`(.destroy app false #js{:children true})`), marks state `:destroyed?`.
  - Pick callbacks now receive the raw pointer event: `on-pick` fns are called `(f node js-event)`.
  - Dbl-click: second pointerup on the same picked node within 300ms → `on-dbl-pick (f node js-event)` (and suppresses the second single-pick call).
  - `do-panning?` (when provided) gates drag-pan start: called with the raw `pointerdown` event; falsy → no drag.
  - `on-gesture-end` (when provided) called with no args after a drag/pinch/wheel interaction ends (drag: pointerup; wheel: 400ms debounce).
- `markers.cljs` produces: `render-nodes!` uses per-marker `:radius` (`scale = radius / base-radius`, highlight ×1.6) and `:alpha`; a dedicated `highlight-ring!` pass draws a 2px red (0xff0000) circle outline at radius `(+ 4 radius-px)` around each highlighted single marker into a shared Graphics cleared each render.
- `tiles.cljs` produces: `render-tiles!` no-ops when template is nil; `clear-tiles!` destroys all cached sprites (for `set-tile-template!`).

- [ ] **Step 1:** Implement the `markers.cljs` changes: per-marker radius/alpha in the single-marker branch of `render-nodes!` (`scale (* (/ (or (:radius node) 6) base-radius) (if (:highlighted? node) 1.6 1.0))`, `(set! (.-alpha s) (or (:alpha node) 0.7))`), plus:

```clojure
(defn draw-highlight-rings!
  "Red outline around highlighted single markers. g is a Graphics cleared each call."
  [^js g nodes vpt]
  (.clear g)
  (.lineStyle g 2 0xff0000 1)
  (doseq [{:keys [lon lat radius highlighted? cluster?]} nodes
          :when (and highlighted? (not cluster?))]
    (let [[sx sy] (vp/->screen vpt lon lat)
          r (+ 4 (* (/ (or radius 6) base-radius) base-radius))]
      (.drawCircle g sx sy r)))
  (.lineStyle g 0))
```

- [ ] **Step 2:** Implement `tiles.cljs` changes: at the top of `render-tiles!` add `(when template ...)` around the body; add:

```clojure
(defn clear-tiles! [^js container cache]
  (doseq [[k ^js sprite] @cache]
    (.removeChild container sprite)
    (.destroy sprite)
    (swap! cache dissoc k)))
```

`attach-tile-layer!` must re-read `(:tile-template @state)` on every callback invocation (not capture it once) so `set-tile-template!` takes effect, and must expose the cache atom in state as `:tile-cache` so the engine can clear it.

- [ ] **Step 3:** Implement the `engine.cljs` additions. State atom gains `:cluster? true :visible-ids nil :highlighted-ids #{} :batch? false :max-zoom <opt> :destroyed? false`. The node on-change callback becomes:

```clojure
(on-change! engine
  (fn [vpt]
    (let [{:keys [marker-container markers marker-texture node-index
                  cluster-cell-px cluster? visible-ids highlighted-ids]} @state
          ms (into []
                   (comp (filter #(or (nil? visible-ids) (contains? visible-ids (:id %))))
                         (map #(assoc % :highlighted? (contains? highlighted-ids (:id %)))))
                   markers)
          nodes (if cluster?
                  (clustering/cluster ms (:viewport @state) cluster-cell-px)
                  (mapv #(assoc % :cluster? false :count 1) ms))]
      (swap! state assoc :nodes nodes)
      (markers/render-nodes! app marker-container marker-texture node-index nodes vpt)
      (markers/draw-highlight-rings! highlight-g nodes vpt))))
```

where `highlight-g` is a new Graphics added to the stage above `marker-container`. `notify` respects `(:batch? @state)` (early-return). Setter fns (`set-cluster!`, `set-visible-ids!`, `set-highlighted!`, `begin-batch!`, `end-batch!`, `set-tile-template!`, `resize!`, `destroy!`) are plain `swap!` + `notify` per the Interfaces block. Event wiring: store listeners so `destroy!` can remove them; pass the raw event to pick callbacks; add dbl-click tracking (`last-pick` atom `[id timestamp]`); gate drag start on `do-panning?`; call `on-gesture-end` after pointerup-ending-a-drag, pinch end, and 400ms after the last wheel event.
- [ ] **Step 4:** Update `sandbox.cljs`: adapt its `on-pick` handler to 2 args (`(fn [node _evt] ...)`), add toolbar buttons `"Toggle clustering"` → `(engine/set-cluster! @engine-ref (not (:cluster? @(:state @engine-ref))))` and `"Highlight 10"` → `(engine/set-highlighted! @engine-ref (set (map str (range 10))))` (ids are ints in the sandbox demo data — keep `(set (range 10))` if demo ids are ints; match the demo generator).
- [ ] **Step 5:** Verify: `npm run test-ci` (no regressions — engine has no unit suite), `clojure -M -m figwheel.main --build-once sandbox` clean, `clj-kondo --lint` on the four files clean. Note in the report: browser-visual checks deferred.
- [ ] **Step 6: Commit** `feat(map): engine control surface for plugin integration`.

---

### Task 5: Engine render-done integration

**Files:**
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs`, `tiles.cljs`

**Interfaces:**
- Consumes: `settle` (Task 2).
- Produces:
  - Engine map gains `:settle (atom (settle/new-state))`.
  - `(on-render-done! engine f)` — one-shot listener via `settle/add-listener`.
  - `(request-render! engine)` — runs `settle/note-render` and invokes any fired listeners via `(js/setTimeout #(doseq [f fired] (f)) 0)`.
  - `tiles.cljs` `tile-sprite` reports texture loads: if the texture's baseTexture is not yet `valid`, call the provided `on-load-start!` immediately and `on-load-end!` once (`.once (.-baseTexture tex) "loaded" ...)` and `"error"` both count as end). The engine passes wrappers that run `settle/note-load-start` / `note-load-end` and fire listeners the same way.

- [ ] **Step 1:** Thread two callbacks into the tile layer: `attach-tile-layer!` gains an opts map `{:on-load-start! f :on-load-end! f}` passed through to `tile-sprite`. In `tile-sprite`:

```clojure
(let [tex (.from Texture (tile-url template tile))
      base (.-baseTexture tex)]
  (when (and on-load-start! (not (.-valid base)))
    (on-load-start!)
    (.once base "loaded" on-load-end!)
    (.once base "error" on-load-end!))
  ...)
```

- [ ] **Step 2:** In `engine.cljs`: create the settle atom in `create!`; implement `on-render-done!` and `request-render!`; wire the load callbacks (swap settle state, collect fired listeners, invoke async). `set-viewport!`, `set-tile-template!` and `resize!` do NOT auto-request render-done firing — only `request-render!` marks a render request (the adapter calls it from the protocol's `render-map`).
- [ ] **Step 3:** Verify: `npm run test-ci` (settle tests still green), sandbox build-once clean, kondo clean.
- [ ] **Step 4: Commit** `feat(map): render-done signal wired through tile loads`.

---

### Task 6: Adapter state + stubs (pure parts TDD)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/map/impl/pixi/stubs.cljs`, `.../impl/pixi/instance.cljs`
- Create: `plugins/frontend_test/de/explorama/frontend/map/map/impl/pixi/stubs_test.cljs`, `.../instance_test.cljs`
- Modify: both test runners.

**Interfaces:**
- `stubs.cljs` produces: `(notify-unavailable! frame-id feature)` — first call per `[frame-id feature]` shows a notice and returns true; later calls return false silently. Notice mechanism: try `(fi/call-api :notify-event-dispatch {:type :warning :category {:misc :map} :message (str (name feature) " is not yet available in the new map renderer")})`; if `fi/call-api` with `:notify-event-dispatch` is not registered (check `plugins/frontend/de/explorama/frontend/woco/api/core.cljs` for the exact notification api name during implementation — if none exists as a single call, use `(js/alert ...)`). Pure core: `(should-notify? seen-set k) -> bool` and the seen atom, so the throttle is testable without DOM.
- `instance.cljs` produces:
  - `(new-instance-state frame-id extra-fns) -> atom` holding `{:frame-id .. :extra-fns .. :engine nil :headless? true :marker-data {} :event-cache {} :highlighted #{} :visible-ids nil :cluster? true :base-layers {} :current-base-layer nil :feature-data {} :filtered-feature-data {} :pending []}`.
  - `(register! frame-id state-atom)` / `(lookup frame-id)` / `(unregister! frame-id)` over a private registry atom.
  - `(enqueue-pending state-map op) -> state-map'` and `(drain-pending state-map) -> [state-map' ops]` (FIFO) — pure, for headless op replay.

- [ ] **Step 1: Write failing tests:**

```clojure
(ns de.explorama.frontend.map.map.impl.pixi.stubs-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.map.impl.pixi.stubs :as stubs]))

(deftest throttle-once-per-key
  (is (true? (stubs/should-notify? #{} [:f1 :heatmap])))
  (is (false? (stubs/should-notify? #{[:f1 :heatmap]} [:f1 :heatmap])))
  (is (true? (stubs/should-notify? #{[:f1 :heatmap]} [:f2 :heatmap]))))
```

```clojure
(ns de.explorama.frontend.map.map.impl.pixi.instance-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]))

(deftest pending-ops-fifo
  (let [s (-> {:pending []}
              (inst/enqueue-pending [:a 1])
              (inst/enqueue-pending [:b 2]))
        [s' ops] (inst/drain-pending s)]
    (is (= [[:a 1] [:b 2]] ops))
    (is (empty? (:pending s')))))

(deftest registry-roundtrip
  (let [a (inst/new-instance-state "f-1" {})]
    (inst/register! "f-1" a)
    (is (identical? a (inst/lookup "f-1")))
    (inst/unregister! "f-1")
    (is (nil? (inst/lookup "f-1")))))
```

- [ ] **Step 2: Register both test namespaces in both runners.**
- [ ] **Step 3: `npm run test-ci` — FAIL.**
- [ ] **Step 4: Implement both namespaces** exactly per the Interfaces block (`should-notify?` is `(not (contains? seen k))`; `notify-unavailable!` derefs/updates a private `seen` atom and calls the notice mechanism).
- [ ] **Step 5: `npm run test-ci` — PASS, no regressions.**
- [ ] **Step 6: Commit** `feat(map): pixi adapter instance registry and stub notifications`.

---

### Task 7: `PixiObjectManager`

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/map/impl/pixi/object_manager.cljs`

**Interfaces:**
- Consumes: `instance.cljs`, `stubs.cljs`, `pixi/style.cljs`, engine (`create!` NOT called here — boot happens in state-handler's `render-map`; object-manager only records state).
- Produces: `(create-instance frame-id extra-fns) -> PixiObjectManager` implementing ALL of `mapObjectManager` (`map/map/protocol/object_manager.cljs`). Method mapping:

| Method | Behavior |
|---|---|
| `create-map-instance [_ headless?]` | `(swap! state assoc :headless? headless?)` — engine boot is lazy (state-handler `render-map`). |
| `map-instance` | the engine ref or nil (opaque; no external callers). |
| `create-marker-layer` / `remove-marker-layer` / `marker-layer-created?` | flag `:marker-layer? true/false` in state; `created?` returns it. |
| `get-cluster-layer` / `get-marker-layer` / `get-popup` | opaque: return the engine ref or nil. |
| `create-markers [_ markers-data]` | merge into `:marker-data` (keyed by id), push to engine when booted (see helper below). |
| `remove-markers [_ marker-ids]` | dissoc ids from `:marker-data`, push. |
| `clear-markers` | `:marker-data {}`, push. |
| `marker-created? [_ id]` | `(contains? (:marker-data @state) id)`. |
| `get-marker-objs [_ ids]` | `(map #(get (:marker-data @state) %) ids)` (opaque — no external callers). |
| `marker-ids` | `(keys (:marker-data @state))`. |
| feature-layer/arrow/heatmap/area fns | `create-feature-layer`, `create-arrow-features`, `create-heatmap-features`, `create-area-features` → `(stubs/notify-unavailable! frame-id <feature-kw>)` and record the layer id in `:stub-feature-layers` set so `feature-layer-created?`/`all-feature-layers` answer consistently; `remove-*`/`clear-*`/getters → silent no-ops returning nil. |
| `create-overlayer [_ overlayer]` | record desc in `:stub-overlayers` by id; NO notice here (creation happens during init for all configured overlayers; the notice belongs to display — state-handler). `overlayer-created?`/`get-overlayer-obj` answer from the record; `remove-overlayer` dissocs. |
| `create-base-layer [_ desc]` | store desc in `:base-layers` keyed by `:name`; when `(:default desc)` and no `:current-base-layer` yet, set `:current-base-layer (:name desc)`. No engine work here. |
| `remove-base-layer` / `base-layer-created?` / `get-base-layer-obj` | dissoc / contains? / get on `:base-layers`. |
| `destroy-instance` | call engine `destroy!` when booted; `(inst/unregister! frame-id)`. |

The shared "push markers to engine" helper (used by state-handler too — put it in `instance.cljs` in THIS task):

```clojure
(defn push-markers! [state-map]
  (when-let [engine (:engine state-map)]
    (engine/set-markers! engine
      (style/markers-map->engine-markers
        (:marker-data state-map) (:highlighted state-map) (:visible-ids state-map)))))
```

(NOTE: `:highlighted` holds engine `:id` strings? NO — `markers-map->engine-markers` matches highlights by `:event-id` tuple. Store highlighted as a set of event-id tuples; `highlight-marker` receives the tuple — see Task 8.)

- [ ] **Step 1:** Write `object_manager.cljs`: ns requires `[de.explorama.frontend.map.map.protocol.object-manager :as proto]`, instance/stubs/style/engine; `deftype PixiObjectManager [frame-id state]` implementing every method above; `(defn create-instance [frame-id extra-fns] ...)` creates + registers the instance state atom and returns the deftype. Every protocol method must be present — a missing method throws at call time.
- [ ] **Step 2:** Verify: `clojure -M -m figwheel.main --build-once sandbox` (compiles the new ns? — sandbox doesn't require it; instead compile-check with the browser dev build: `clojure -M -m figwheel.main --build-once dev`; if that build config name differs, check `bundles/browser/dev.cljs.edn` and use its id). `clj-kondo` clean. `npm run test-ci` no regressions.
- [ ] **Step 3: Commit** `feat(map): pixi object-manager protocol implementation`.

---

### Task 8: `PixiStateHandler` + popup overlay + engine boot

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/map/impl/pixi/state_handler.cljs`

**Interfaces:**
- Consumes: everything above + `popup_content`, `reagent.dom`, `viewport` (`fit-extent` via engine), `config/frame-body-dom-id` (`de.explorama.frontend.map.config`).
- Produces: `(create-instance frame-id object-manager extra-fns) -> PixiStateHandler` implementing ALL of `mapStateHandler`. Key behaviors:

**Engine boot (private `ensure-engine!`)** — called from `render-map`:
1. Already booted → return engine. `:headless?` true or container `(.getElementById js/document (config/frame-body-dom-id frame-id))` missing → return nil.
2. Create a child `<canvas>` (fills container, `position:absolute; inset:0`) + a popup mount `<div>` + an attribution `<div class="map-attribution">` (absolutely positioned bottom-right, `font-size:10px; background:rgba(255,255,255,.7); padding:0 4px; z-index:5`) inside the container.
3. Current base-layer desc (`:current-base-layer` name → `:base-layers`); `"wms"`/`"esri"` type → `(stubs/notify-unavailable! frame-id :base-layer-type)` and fall back to the first `"default"`/`"tms"` desc (or nil template).
4. `engine/create!` with `{:canvas canvas :tile-template (:tilemap-server-url desc) :max-zoom (or (:max-zoom desc) 19) :preserve-drawing-buffer? true :viewport {:center [0 0] :zoom 2 :min-zoom (or (:min-zoom desc) 1) :max-zoom (or (:max-zoom desc) 19)} :do-panning? (:do-panning? extra-fns) :on-gesture-end #(when-let [f (:track-view-position-change extra-fns)] (f true)) :on-dbl-pick (fn [node evt] (when-let [f (:marker-dbl-clicked extra-fns)] (when-not (:cluster? node) (f evt (:event-id node)))))}`; set attribution innerHTML from desc.
5. `engine/on-pick`: for a single-marker node — if the woco select-modifier applies use `:highlight-event`, else `:marker-clicked`:

```clojure
(engine/on-pick engine
  (fn [node evt]
    (when (and node (not (:cluster? node)))
      (if (and evt (.-ctrlKey evt))
        (when-let [f (:highlight-event extra-fns)]
          (f (:event-id node) [(:lat node) (:lon node)]))
        (when-let [f (:marker-clicked extra-fns)]
          (f (:event-id node) (:color-hex node)
             [(:lat node) (:lon node)]
             {:center [(:lat node) (:lon node)] :zoom (:zoom (engine/get-viewport engine))}))))))
```

6. Mount the popup Reagent root into the popup div; store engine + DOM refs; `(swap! state assoc :engine engine :headless? false)`; `inst/push-markers!`; apply `:cluster?`, `:visible-ids`; drain + apply `:pending` ops (`[:move-to zoom [lat lon]]`, `[:fit-data]` — applied in order).

**Popup overlay component** (in this ns):

```clojure
(defn- popup-view [popup-state tick engine-ref hide-fn]
  (fn []
    @tick
    (when-let [{:keys [lat lon html]} @popup-state]
      (when-let [e @engine-ref]
        (let [[sx sy] (vp/->screen (engine/get-viewport e) lon lat)]
          [:div.map-popup {:style {:position "absolute" :left sx :top sy :z-index 20
                                   :transform "translate(-50%, calc(-100% - 12px))"
                                   :background "#fff" :border "1px solid #888"
                                   :border-radius "4px" :max-width "320px"
                                   :box-shadow "0 1px 4px rgba(0,0,0,.3)"}}
           [:div {:style {:position "absolute" :top 2 :right 6 :cursor "pointer"}
                  :on-click #(hide-fn)} "×"]
           [:div {:dangerouslySetInnerHTML {:__html html}}]])))))
```

`tick` bumps on every engine `on-change!`. `hide-fn` = the `:hide-popup` extra-fn.

**Protocol methods:**

| Method | Behavior |
|---|---|
| `render-map` | `ensure-engine!`; when booted `(engine/request-render! engine)`. |
| `one-time-render-done-listener [_ f]` | engine booted → `engine/on-render-done!`; else stash in `:pending-render-listeners`, register them during boot before `request-render!`. |
| `move-to-data` | engine → `engine/fit-markers!`; headless → enqueue `[:fit-data]`. |
| `move-to-marker [_ id]` | marker entry → `move-to` with `max(current-zoom, @(:move-data-max-zoom extra-fns fallback 10))` at its first location; then when `:cluster?` → `select-cluster-with-marker`. |
| `set-marker-data [_ md]` | `(swap! state assoc :marker-data md)` + `push-markers!`. |
| `get-marker-data` | `(:marker-data @state)`. |
| `display-marker-cluster` | `:cluster? true`, `engine/set-cluster!` when booted, then `move-to-data` (OL fits view on toggle). |
| `display-markers` | same with false. |
| `update-marker-styles [_ ids]` | data already carries styles — just `push-markers!`. |
| `marker-higlighted?` / `list-highlighted-marker` / `highlight-marker` / `de-highlight-marker` | over `:highlighted` set of event-id tuples: contains?/seq/conj/disj + `push-markers!` (also `engine/set-highlighted!` is NOT used — highlight flows through `markers-map->engine-markers`; keep engine's set-highlighted! for sandbox only). |
| `temp-hide-marker-layer` | engine → `engine/begin-batch!` (suppresses re-render churn during marker-steps). |
| `restore-temp-hidden-marker-layer` | engine → `engine/end-batch!`. |
| `hide-markers-with-id [_ ids]` | `:visible-ids` = all ids minus `ids`; `engine/set-visible-ids!` when booted. |
| `display-all-markers` | `:visible-ids nil` + engine. |
| `cache-event-data [_ id data]` / `cached-event-data` | assoc/get in `:event-cache` (key = event-id tuple). |
| `set-feature-data` / `get-feature-data` / `set-filtered-feature-data` / `get-filtered-feature-data` | store/read `:feature-data` / `:filtered-feature-data` maps (REAL storage — project save/load reads these). |
| `display-feature-layer [_ id]` | `(stubs/notify-unavailable! frame-id :feature-layer)`. |
| `hide-feature-layer` / `remove-feature-layer` / `clear-feature-layers` | silent no-ops. |
| `list-active-feature-layers` | `[]`. |
| `display-overlayer [_ id]` | `(stubs/notify-unavailable! frame-id :overlayer)`. |
| `hide-overlayer` | silent no-op. `list-active-overlayers` → `[]`. |
| `switch-base-layer [_ name]` | desc lookup; `"wms"`/`"esri"` → notify + keep current; else set `:current-base-layer`, engine booted → `engine/set-tile-template!` + attribution innerHTML update. |
| `resize-map` | engine → `engine/resize!`. |
| `move-to [_ zoom [lat lon]]` | engine → set viewport center `[lon lat]` + zoom (via `engine/set-viewport!` merging into current); headless → enqueue `[:move-to zoom [lat lon]]`. |
| `view-position` | engine → `{:center [lat lon] :zoom z}` from `engine/get-viewport` (reorder!); headless → last enqueued/`{:center [0 0] :zoom 2}`. |
| `select-cluster-with-marker [_ id]` | find in `(:nodes @(:state engine))` the cluster whose `:members` contain `:id` = id; found → `engine/set-viewport!` via the cluster-members fit (reuse engine's fit logic: call `engine/fit-markers!`-style fit on members — add tiny helper `(engine/fit-members! engine members)` in this task, same bbox+fit-extent code as `try-cluster-click`). Not found → no-op. |
| `display-popup [_ [lat lon] content-desc]` | build html: replicate the exact `gen-popup-content` invocation from `map/map/impl/openlayers/state_handler.cljs:246-273` (same args incl. `extra-fns` labels/localize fns), but with `popup-content/gen-popup-content`; `(reset! popup-state {:lat lat :lon lon :html html})`. Headless → no-op. |
| `hide-popup` | `(reset! popup-state nil)`. |
| `destroy-instance` | unmount popup root, remove created DOM nodes, `engine/destroy!`, unregister. |

- [ ] **Step 1:** Write `state_handler.cljs` per the tables above (single file; popup component + `ensure-engine!` private). Add `(defn fit-members! [engine members] ...)` to `engine.cljs` (extract the bbox+fit-extent body shared with `try-cluster-click` — refactor `try-cluster-click` to call it).
- [ ] **Step 2:** Verify compile: `clojure -M -m figwheel.main --build-once dev` (browser dev build — full plugin) AND `--build-once sandbox`; `clj-kondo` clean on the new/changed files; `npm run test-ci` no regressions.
- [ ] **Step 3: Commit** `feat(map): pixi state-handler protocol implementation with popup overlay`.

---

### Task 9: The cutover — switch api.cljs, delete OpenLayers, drop ol/ol-ext

**Files:**
- Modify: `plugins/frontend/de/explorama/frontend/map/map/api.cljs`, both test runners, `CLAUDE.md`, `bundles/browser/package.json`, `bundles/electron/frontend/package.json`, `bundles/server/package.json`
- Delete: `plugins/frontend/de/explorama/frontend/map/map/impl/openlayers/` (whole dir), `plugins/frontend_test/de/explorama/frontend/map/impl/openlayers/util_test.cljs`

- [ ] **Step 1:** `api.cljs`: replace the two openlayers requires with `[de.explorama.frontend.map.map.impl.pixi.object-manager :as pixi-obj]` and `[de.explorama.frontend.map.map.impl.pixi.state-handler :as pixi-state]`; `(def map-type :pixi)`; case arms: `:pixi (pixi-obj/create-instance frame-id extra-fns)` / `:pixi (pixi-state/create-instance frame-id object-manager-instance extra-fns)` (drop the `:openlayers` arms).
- [ ] **Step 2:** `git rm -r plugins/frontend/de/explorama/frontend/map/map/impl/openlayers plugins/frontend_test/de/explorama/frontend/map/impl/openlayers`; remove `[de.explorama.frontend.map.impl.openlayers.util-test]` from BOTH test runners.
- [ ] **Step 3:** Remove the `"ol"` and `"ol-ext"` entries from all three package.json files; run `npm install` in `bundles/browser` (regenerates lockfile; electron/server lockfiles: run `npm install` in `bundles/electron/frontend` and `bundles/server` too — they're fast since node_modules exist).
- [ ] **Step 4:** Grep-verify no stragglers: `grep -rn "openlayers\|\"ol/\|ol-ext" plugins/frontend plugins/frontend_test bundles/*/package.json` → only historical docs/specs may match; no code/deps. Update `CLAUDE.md`'s dependency list line (`OpenLayers 7` → remove; note PixiJS is the map renderer).
- [ ] **Step 5:** Full verification: `npm run test-ci` (expect 161-1+new = run reports the correct total; the 5 old popup-HTML tests now live in `popup-content-test`, the old `util-test` namespace is gone — count must equal the Task-8 baseline minus 0: verify no missing-namespace errors), `clojure -M -m figwheel.main --build-once dev` clean, `--build-once sandbox` clean.
- [ ] **Step 6: Commit** `feat(map)!: replace openlayers backend with pixi engine` (body lists the deletions + the stage-1 limitations from the spec).

---

### Task 10: Cross-bundle verification + acceptance handoff

**Files:** none created; verification + report only (fix-forward anything found, in this task's commit if code changes are needed).

- [ ] **Step 1:** Electron frontend compile: from `bundles/electron/frontend`, run the figwheel build once (check its `*.cljs.edn` build id — use `clojure -M -m figwheel.main --build-once <id>` with the id found there). Expect: clean compile with no `ol` resolution errors.
- [ ] **Step 2:** Server frontend compile: from `bundles/server`, `clojure -Sdeps "$(cat cljs.deps.edn)" -M -m figwheel.main --build-once <id>` (same procedure). Expect clean.
- [ ] **Step 3:** Browser suite final: `npm run test-ci` → green; record final counts.
- [ ] **Step 4:** Write the acceptance checklist into the task report for the human:
  1. `cd bundles/browser && clj -M:dev` → import data, open a Map window: markers render styled; cluster donuts show color composition; popup opens on marker click (content matches old fields) and closes via ×; ctrl-click highlights (red ring) and selects; dbl-click adds to details view.
  2. Toolbar/legend: cluster toggle switches modes and refits; zoom-to-data works; base-layer switch works for default layers; selecting heatmap/movement/area/overlayer shows the one-time notice.
  3. Pan/zoom smooth; view position persists (position-change task fires after gestures).
  4. Minimize → restore the map window (headless → visible transition boots the engine with markers intact).
  5. Screenshot/export the frame: map canvas appears (not blank).
  6. Save to project, reload, replay: view position + popup restore via render-done choreography.
- [ ] **Step 5: Commit** (only if fixes were needed) `fix(map): cross-bundle fixes for pixi cutover`.

---

## Self-Review

- **Spec coverage:** architecture/adapters (T7-T8), engine additions 1-9 (T2-T5, T8's `fit-members!`), deletions (T9), popup (T1+T8), stubs+throttle (T6+T8), base-layer fallback (T8), headless/lazy (T8), screenshots (`preserve-drawing-buffer?` T4/T8), mouse prefs + gesture tracking (T4+T8), task-queue sync contract (all protocol methods synchronous; only render settling async), tests (T1-T3, T6), cross-bundle (T10), CLAUDE.md (T9). Interim UX notices (T6-T8). No stage-1 npm deps anywhere.
- **Placeholder scan:** ports reference exact source file:lines that exist until T9 (ordering enforced); no TBDs. T8's method table specifies behavior per method rather than full bodies — each row names exact fns/keys already defined in earlier tasks; the two genuinely novel code units (boot sequence, popup component) are given as code.
- **Type consistency:** engine marker shape (T3) = what T4's callback filters and T7/T8 push; `[lat lon]`↔`[lon lat]` conversions localized to `style.cljs` (in) and `view-position`/`move-to`/pick callbacks (out), each explicitly marked; `settle` API used identically in T2/T5; `notify-unavailable!` arity consistent (T6-T8); `push-markers!` defined once (T7, in `instance.cljs`) and reused (T8).
