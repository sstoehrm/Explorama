# PixiJS Map Engine Prototype Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, dependency-free PixiJS map-rendering prototype (WMTS basemap + ~1k markers + grid clustering + picking + popup + zoom-to-data) that proves an in-house engine can replace OpenLayers for the `map` plugin.

**Architecture:** A small set of focused ClojureScript modules over the already-present PixiJS. Pure geo/camera/clustering/picking math is unit-tested; a Pixi `engine` composes them and renders in **screen space** (every visible tile and marker is positioned via one tested `viewport/->screen` function on each viewport change — no clever container-transform math to get wrong). A dev-only figwheel **sandbox** build exercises the whole thing in isolation from the plugin's task-queue.

**Tech Stack:** ClojureScript 1.12.134, PixiJS 7.4.2 (`pixi.js-legacy`, already a dependency in all three bundles), Reagent 1.3.0 (already present) for the sandbox UI + DOM popup, Figwheel Main 0.2.18 with `:auto-bundle :webpack`, `cljs.test`.

## Global Constraints

- **No new npm dependencies.** Only `pixi.js-legacy` (already in `bundles/browser/package.json`) and `reagent` (already in `bundles/browser/deps.edn`). Grid clustering is pure ClojureScript; `supercluster` is explicitly out of scope.
- **Engine source namespaces:** `de.explorama.frontend.map.pixi.*`, files under `plugins/frontend/de/explorama/frontend/map/pixi/`.
- **Engine test namespaces:** `de.explorama.frontend.map.pixi.*-test`, files under `plugins/frontend_test/de/explorama/frontend/map/pixi/`. Every new test namespace MUST be added to **both** `bundles/browser/test/de/explorama/test_runner.cljs` and `bundles/browser/test/de/explorama/test_runner_ci.cljs` or it will not run.
- **Public coordinate convention:** callers pass `[lat lon]` order where a pair is a single argument at the plugin boundary, but internal engine functions take explicit `lon`/`lat` scalar args (documented per function). Web Mercator latitude is clamped to ±85.05112878.
- **Prototype target:** ~1k markers, smooth pan/wheel-zoom/pinch. Not 100k.
- **Tiles:** WMTS RESTful URL template with `{z}`/`{x}`/`{y}` placeholders (Web-Mercator / GoogleMapsCompatible tile matrix). Default source is basemap.de (open, no API key); the template is a config value and swappable.
- **Popups / UI chrome:** plain Reagent DOM overlay (no re-frame in the sandbox; a reagent `r/atom` holds popup state). No `ol`/`ol-ext`.
- **Rendering strategy:** screen-space. On each viewport change, reposition visible tile sprites and all marker/cluster sprites using `viewport/->screen`. Pixi `Application` runs with `autoStart true` (continuous render); positions are updated only on viewport change.
- **Bundle:** all work targets `bundles/browser`. Run the sandbox with `clojure -M:sandbox` from `bundles/browser` (after `npm install`).
- **Commits:** conventional style, one per task. End every commit message body with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
  Work on a feature branch (not `main`).

## File Structure

**Engine source** (`plugins/frontend/de/explorama/frontend/map/pixi/`):
- `projection.cljs` — pure Web Mercator: `project`, `unproject`, `world-px`, lat clamp. No Pixi, no DOM.
- `viewport.cljs` — pure camera math over a `{:center [lon lat] :zoom :width :height}` map: `->screen`, `->lonlat`, `pan`, `zoom-around`, `fit-extent`. Depends on `projection`.
- `tiles.cljs` — pure tile math (`visible-tiles`, `tile-url`) + Pixi tile rendering/caching (`ensure-tiles!`, `reposition-tiles!`). Depends on `projection`, `viewport`.
- `markers.cljs` — Pixi marker rendering: shared white circle texture, tinted sprites, highlight, `render-markers!`, `reposition-markers!`. Depends on `viewport`.
- `clustering.cljs` — pure grid-bin clustering: `cluster`. Depends on `viewport`.
- `picking.cljs` — pure hit-test: `pick`. Depends on `viewport`.
- `popup.cljs` — Reagent DOM popup overlay component. No Pixi.
- `engine.cljs` — creates the Pixi `Application`, holds the mutable engine state (viewport atom, containers, sprite indexes), wires DOM camera events, and calls the layer modules on viewport change. Depends on all of the above.
- `sandbox.cljs` — dev entry: mounts a Reagent page (canvas + buttons + popup), generates ~1k demo markers, boots the engine.

**Engine tests** (`plugins/frontend_test/de/explorama/frontend/map/pixi/`):
- `projection_test.cljs`, `viewport_test.cljs`, `tiles_test.cljs`, `clustering_test.cljs`, `picking_test.cljs`.

**Bundle scaffolding** (`bundles/browser/`):
- `sandbox.cljs.edn` — figwheel build config for the sandbox.
- `resources/public/sandbox.html` — minimal dev page.
- `deps.edn` — add `:sandbox` alias (modify).
- `test/de/explorama/test_runner.cljs` + `test_runner_ci.cljs` — register new test namespaces (modify).

---

### Task 1: Sandbox build scaffolding + Pixi smoke render

Stand up an isolated figwheel build that boots PixiJS and draws one shape, proving the toolchain (cljs → webpack → pixi → canvas) works before any engine code exists.

**Files:**
- Create: `bundles/browser/sandbox.cljs.edn`
- Create: `bundles/browser/resources/public/sandbox.html`
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs`
- Modify: `bundles/browser/deps.edn` (add `:sandbox` alias)

**Interfaces:**
- Produces: sandbox entry `de.explorama.frontend.map.pixi.sandbox/init` (0-arg, called from `sandbox.html`).

- [ ] **Step 1: Create the figwheel build config**

Create `bundles/browser/sandbox.cljs.edn`:

```clojure
^{:watch-dirs ["frontend" "../../plugins/frontend/" "../../plugins/shared/"]
  :auto-bundle :webpack
  :open-url "http://localhost:8020/sandbox.html"}
{:main de.explorama.frontend.map.pixi.sandbox
 :output-to "resources/public/js/sandbox/main.js"
 :output-dir "resources/public/js/sandbox"
 :asset-path "/js/sandbox"
 :optimizations :none
 :source-map true}
```

- [ ] **Step 2: Create the dev HTML page**

Create `bundles/browser/resources/public/sandbox.html`:

```html
<!doctype html>
<html lang="en">
<head>
    <meta charset='utf-8'>
    <title>Explorama Pixi Map Sandbox</title>
    <style>
        html, body { margin: 0; height: 100%; font-family: sans-serif; }
        #app { position: absolute; inset: 0; }
        #map-canvas { display: block; width: 100%; height: 100%; }
        .sandbox-toolbar { position: absolute; top: 8px; left: 8px; z-index: 10;
                           background: rgba(255,255,255,0.9); padding: 6px 8px; border-radius: 4px; }
        .sandbox-popup { position: absolute; z-index: 20; background: #fff; border: 1px solid #888;
                         border-radius: 4px; padding: 6px 8px; font-size: 12px; pointer-events: none;
                         transform: translate(-50%, calc(-100% - 10px)); box-shadow: 0 1px 4px rgba(0,0,0,.3); }
    </style>
</head>
<body>
    <div id="app"></div>
    <script src="/js/sandbox/main_bundle.js" type="text/javascript"></script>
    <script>de.explorama.frontend.map.pixi.sandbox.init();</script>
</body>
</html>
```

- [ ] **Step 3: Create the smoke-test entry namespace**

Create `plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.sandbox
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            ["pixi.js-legacy" :refer [Application Graphics]]))

(defn- boot-pixi! []
  (let [canvas (.getElementById js/document "map-canvas")
        app (Application. (clj->js {:autoStart true
                                    :width (.-clientWidth canvas)
                                    :height (.-clientHeight canvas)
                                    :backgroundColor 0xEAEAEA
                                    :antialias true
                                    :resolution (or js/window.devicePixelRatio 1)
                                    :autoDensity true
                                    :view canvas}))
        g (Graphics.)]
    (.beginFill g 0x3366cc)
    (.drawRect g 40 40 160 100)
    (.endFill g)
    (.addChild (.-stage app) g)
    app))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot-pixi!))
    :reagent-render (fn [] [:canvas {:id "map-canvas"}])}))

(defn init []
  (rdom/render [page] (.getElementById js/document "app")))
```

- [ ] **Step 4: Add the `:sandbox` alias**

In `bundles/browser/deps.edn`, add to the `:aliases` map (alongside `:dev`):

```clojure
:sandbox {:main-opts ["-m" "figwheel.main" "--build" "sandbox" "--repl"]}
```

- [ ] **Step 5: Run the sandbox and verify manually**

Run (from `bundles/browser`, after `npm install`):

```bash
clojure -M:sandbox
```

Expected: figwheel compiles, opens `http://localhost:8020/sandbox.html`, and a blue rectangle renders on a light-grey canvas that fills the window. No console errors.

- [ ] **Step 6: Commit**

```bash
git add bundles/browser/sandbox.cljs.edn \
        bundles/browser/resources/public/sandbox.html \
        plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs \
        bundles/browser/deps.edn
git commit -m "feat(map): pixi map sandbox scaffolding + smoke render

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `projection` — Web Mercator (pure, TDD)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/projection.cljs`
- Test: `plugins/frontend_test/de/explorama/frontend/map/pixi/projection_test.cljs`
- Modify: `bundles/browser/test/de/explorama/test_runner.cljs`, `bundles/browser/test/de/explorama/test_runner_ci.cljs`

**Interfaces:**
- Produces:
  - `(project lon lat) -> [x y]` — normalized Web Mercator, both in `[0.0, 1.0]`. `x=0` at lon −180, `x=1` at lon +180; `y=0` at the north edge (~lat +85.0511), `y=1` at the south edge.
  - `(unproject x y) -> [lon lat]` — inverse of `project`.
  - `(world-px zoom) -> px` — world width in pixels at `zoom`: `256 * 2^zoom`.
  - `(clamp-lat lat) -> lat` — clamp to ±85.05112878.

- [ ] **Step 1: Write the failing test**

Create `plugins/frontend_test/de/explorama/frontend/map/pixi/projection_test.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.projection-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.projection :as proj]))

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-6))

(deftest project-center
  (testing "lon/lat 0,0 maps to the middle of the world square"
    (let [[x y] (proj/project 0 0)]
      (is (close? x 0.5))
      (is (close? y 0.5)))))

(deftest project-edges
  (testing "lon +180 -> x 1.0, lon -180 -> x 0.0"
    (is (close? (first (proj/project 180 0)) 1.0))
    (is (close? (first (proj/project -180 0)) 0.0)))
  (testing "max mercator lat -> y ~0, min -> y ~1"
    (is (close? (second (proj/project 0 85.05112878)) 0.0))
    (is (close? (second (proj/project 0 -85.05112878)) 1.0))))

(deftest round-trip
  (testing "unproject inverts project"
    (doseq [[lon lat] [[13.4 52.5] [-73.9 40.7] [0 0] [100 -33]]]
      (let [[x y] (proj/project lon lat)
            [lon2 lat2] (proj/unproject x y)]
        (is (close? lon lon2))
        (is (close? lat lat2))))))

(deftest world-size
  (testing "world width doubles per zoom level"
    (is (= 256 (proj/world-px 0)))
    (is (= 512 (proj/world-px 1)))
    (is (= 1024 (proj/world-px 2)))))

(deftest lat-clamped
  (testing "latitudes beyond the mercator limit are clamped"
    (is (close? (proj/clamp-lat 90) 85.05112878))
    (is (close? (proj/clamp-lat -90) -85.05112878))))
```

- [ ] **Step 2: Register the test namespace in both runners**

In BOTH `bundles/browser/test/de/explorama/test_runner.cljs` and `bundles/browser/test/de/explorama/test_runner_ci.cljs`, add to the `:require` vector (next to the existing `de.explorama.frontend.map.*` entries):

```clojure
            [de.explorama.frontend.map.pixi.projection-test]
```

- [ ] **Step 3: Run the test to verify it fails**

Run (from `bundles/browser`):

```bash
npm run test-ci
```

Expected: FAIL — namespace `de.explorama.frontend.map.pixi.projection` cannot be loaded (does not exist yet).

- [ ] **Step 4: Write the implementation**

Create `plugins/frontend/de/explorama/frontend/map/pixi/projection.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.projection)

(def ^:const max-lat 85.05112878)

(defn clamp-lat [lat]
  (-> lat (min max-lat) (max (- max-lat))))

(defn project
  "Web Mercator. Returns [x y] normalized to [0,1]. lon/lat in degrees."
  [lon lat]
  (let [lat (clamp-lat lat)
        x (/ (+ lon 180.0) 360.0)
        sin-lat (js/Math.sin (* lat (/ js/Math.PI 180.0)))
        y (- 0.5 (/ (js/Math.log (/ (+ 1 sin-lat) (- 1 sin-lat)))
                    (* 4 js/Math.PI)))]
    [x y]))

(defn unproject
  "Inverse of project. [x y] in [0,1] -> [lon lat] degrees."
  [x y]
  (let [lon (- (* x 360.0) 180.0)
        n (* js/Math.PI (- 1 (* 2 y)))
        lat (* (/ 180.0 js/Math.PI) (js/Math.atan (js/Math.sinh n)))]
    [lon lat]))

(defn world-px
  "World width in pixels at zoom: 256 * 2^zoom."
  [zoom]
  (* 256 (js/Math.pow 2 zoom)))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npm run test-ci`
Expected: PASS for `projection-test` (existing suite total increases; no regressions).

- [ ] **Step 6: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/projection.cljs \
        plugins/frontend_test/de/explorama/frontend/map/pixi/projection_test.cljs \
        bundles/browser/test/de/explorama/test_runner.cljs \
        bundles/browser/test/de/explorama/test_runner_ci.cljs
git commit -m "feat(map): web mercator projection for pixi map engine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `viewport` — camera math (pure, TDD)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/viewport.cljs`
- Test: `plugins/frontend_test/de/explorama/frontend/map/pixi/viewport_test.cljs`
- Modify: both test runners.

**Interfaces:**
- Consumes: `projection/project`, `projection/unproject`, `projection/world-px`.
- A **viewport** is `{:center [lon lat] :zoom <number> :width <px> :height <px> :min-zoom <n> :max-zoom <n>}`.
- Produces:
  - `(->screen vp lon lat) -> [sx sy]` — pixel position of a lon/lat in the viewport.
  - `(->lonlat vp sx sy) -> [lon lat]` — inverse.
  - `(pan vp dx dy) -> vp'` — drag the map by `dx,dy` screen pixels (content follows the cursor).
  - `(zoom-around vp dz sx sy) -> vp'` — change zoom by `dz`, keeping the lon/lat under `(sx,sy)` fixed; clamps to `:min-zoom`/`:max-zoom`.
  - `(fit-extent vp [min-lon min-lat max-lon max-lat]) -> vp'` — center + zoom so the bbox fits the viewport (with ~10% padding), clamped to zoom bounds.

- [ ] **Step 1: Write the failing test**

Create `plugins/frontend_test/de/explorama/frontend/map/pixi/viewport_test.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.viewport-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-4))

(def base {:center [13.4 52.5] :zoom 6 :width 800 :height 600
           :min-zoom 1 :max-zoom 19})

(deftest center-is-screen-centre
  (testing "the viewport center projects to the middle of the canvas"
    (let [[sx sy] (vp/->screen base 13.4 52.5)]
      (is (close? sx 400))
      (is (close? sy 300)))))

(deftest screen-lonlat-round-trip
  (testing "->lonlat inverts ->screen"
    (let [[lon lat] (vp/->lonlat base 250 175)
          [sx sy] (vp/->screen base lon lat)]
      (is (close? sx 250))
      (is (close? sy 175)))))

(deftest pan-shifts-center
  (testing "panning right by 100px moves the map content right (center goes west)"
    (let [v2 (vp/pan base 100 0)]
      ;; the lon/lat previously at screen 300,300 is now at 400,300
      (let [ll (vp/->lonlat base 300 300)
            [sx _] (vp/->screen v2 (first ll) (second ll))]
        (is (close? sx 400))))))

(deftest zoom-around-keeps-point-fixed
  (testing "zooming around a screen point keeps that point's lon/lat under the cursor"
    (let [px 600 py 200
          ll (vp/->lonlat base px py)
          v2 (vp/zoom-around base 1 px py)
          [sx sy] (vp/->screen v2 (first ll) (second ll))]
      (is (= 7 (:zoom v2)))
      (is (close? sx px))
      (is (close? sy py)))))

(deftest zoom-clamped
  (testing "zoom respects max-zoom"
    (is (= 19 (:zoom (vp/zoom-around (assoc base :zoom 19) 5 400 300))))))

(deftest fit-extent-centers-and-fits
  (testing "fit-extent centers on the bbox midpoint"
    (let [v2 (vp/fit-extent base [10.0 50.0 16.0 54.0])
          [clon clat] (:center v2)]
      (is (close? clon 13.0))
      (is (close? clat 52.0))
      (is (<= (:zoom v2) (:max-zoom base))))))
```

- [ ] **Step 2: Register the test namespace in both runners**

Add `[de.explorama.frontend.map.pixi.viewport-test]` to the `:require` vector of both `test_runner.cljs` and `test_runner_ci.cljs`.

- [ ] **Step 3: Run to verify it fails**

Run: `npm run test-ci`
Expected: FAIL — `de.explorama.frontend.map.pixi.viewport` does not exist.

- [ ] **Step 4: Write the implementation**

Create `plugins/frontend/de/explorama/frontend/map/pixi/viewport.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.viewport
  (:require [de.explorama.frontend.map.pixi.projection :as proj]))

(defn ->screen
  "Pixel position of lon/lat within viewport vp."
  [{:keys [center zoom width height]} lon lat]
  (let [s (proj/world-px zoom)
        [cx cy] (proj/project (first center) (second center))
        [px py] (proj/project lon lat)]
    [(+ (* (- px cx) s) (/ width 2))
     (+ (* (- py cy) s) (/ height 2))]))

(defn ->lonlat
  "Inverse of ->screen: lon/lat at screen pixel sx,sy."
  [{:keys [center zoom width height]} sx sy]
  (let [s (proj/world-px zoom)
        [cx cy] (proj/project (first center) (second center))
        px (+ cx (/ (- sx (/ width 2)) s))
        py (+ cy (/ (- sy (/ height 2)) s))]
    (proj/unproject px py)))

(defn pan
  "Drag the map by dx,dy screen pixels."
  [{:keys [center zoom] :as vp} dx dy]
  (let [s (proj/world-px zoom)
        [cx cy] (proj/project (first center) (second center))
        ncx (- cx (/ dx s))
        ncy (- cy (/ dy s))]
    (assoc vp :center (proj/unproject ncx ncy))))

(defn- clamp [x lo hi] (-> x (max lo) (min hi)))

(defn zoom-around
  "Change zoom by dz keeping the lon/lat under (sx,sy) fixed."
  [{:keys [zoom width height min-zoom max-zoom] :as vp} dz sx sy]
  (let [[lon lat] (->lonlat vp sx sy)
        nz (clamp (+ zoom dz) min-zoom max-zoom)
        s (proj/world-px nz)
        [px py] (proj/project lon lat)
        ncx (- px (/ (- sx (/ width 2)) s))
        ncy (- py (/ (- sy (/ height 2)) s))]
    (assoc vp :zoom nz :center (proj/unproject ncx ncy))))

(defn fit-extent
  "Center + zoom so [min-lon min-lat max-lon max-lat] fits, with ~10% padding."
  [{:keys [width height min-zoom max-zoom] :as vp} [min-lon min-lat max-lon max-lat]]
  (let [[x1 y1] (proj/project min-lon max-lat)      ; north-west
        [x2 y2] (proj/project max-lon min-lat)      ; south-east
        dx (max (js/Math.abs (- x2 x1)) 1e-9)
        dy (max (js/Math.abs (- y2 y1)) 1e-9)
        s (* 0.9 (min (/ width dx) (/ height dy)))  ; px per world-unit that fits
        zoom (clamp (/ (js/Math.log (/ s 256)) (js/Math.log 2)) min-zoom max-zoom)
        center (proj/unproject (/ (+ x1 x2) 2) (/ (+ y1 y2) 2))]
    (assoc vp :zoom zoom :center center)))
```

- [ ] **Step 5: Run to verify it passes**

Run: `npm run test-ci`
Expected: PASS for `viewport-test`.

- [ ] **Step 6: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/viewport.cljs \
        plugins/frontend_test/de/explorama/frontend/map/pixi/viewport_test.cljs \
        bundles/browser/test/de/explorama/test_runner.cljs \
        bundles/browser/test/de/explorama/test_runner_ci.cljs
git commit -m "feat(map): viewport camera math for pixi map engine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `tiles` — visible-tile math + URL templating (pure part, TDD)

Only the pure functions here; Pixi rendering is Task 6.

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/tiles.cljs`
- Test: `plugins/frontend_test/de/explorama/frontend/map/pixi/tiles_test.cljs`
- Modify: both test runners.

**Interfaces:**
- Consumes: `projection/project`, `viewport/->lonlat`.
- Produces:
  - `(visible-tiles vp) -> (seq {:z <int> :x <int> :y <int>})` — the integer-zoom tiles (`z = floor(:zoom)`) covering the viewport; `x` wrapped modulo `2^z`, `y` clamped to `[0, 2^z-1]`.
  - `(tile-url template {:z :x :y}) -> string` — replaces `{z}`/`{x}`/`{y}` in `template`.
  - `(tile-key {:z :x :y}) -> string` — stable cache key `"z/x/y"`.

- [ ] **Step 1: Write the failing test**

Create `plugins/frontend_test/de/explorama/frontend/map/pixi/tiles_test.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.tiles-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.tiles :as tiles]))

(def vp {:center [13.4 52.5] :zoom 5 :width 800 :height 600
         :min-zoom 1 :max-zoom 19})

(deftest tile-url-templating
  (is (= "https://s/5/17/10.png"
         (tiles/tile-url "https://s/{z}/{x}/{y}.png" {:z 5 :x 17 :y 10}))))

(deftest tile-key-format
  (is (= "5/17/10" (tiles/tile-key {:z 5 :x 17 :y 10}))))

(deftest visible-tiles-cover-viewport
  (let [ts (tiles/visible-tiles vp)]
    (testing "all tiles are at integer zoom = floor(:zoom)"
      (is (every? #(= 5 (:z %)) ts)))
    (testing "there is at least one tile and coordinates are in-range"
      (is (pos? (count ts)))
      (is (every? #(<= 0 (:x %) 31) ts))   ; 2^5 - 1
      (is (every? #(<= 0 (:y %) 31) ts)))
    (testing "the tile under the viewport center is included"
      (let [[cx cy] (de.explorama.frontend.map.pixi.projection/project 13.4 52.5)
            n 32
            centre {:z 5 :x (js/Math.floor (* cx n)) :y (js/Math.floor (* cy n))}]
        (is (some #(and (= (:x %) (:x centre)) (= (:y %) (:y centre))) ts))))))
```

- [ ] **Step 2: Register the test namespace in both runners**

Add `[de.explorama.frontend.map.pixi.tiles-test]` to both runners' `:require` vectors.

- [ ] **Step 3: Run to verify it fails**

Run: `npm run test-ci`
Expected: FAIL — `de.explorama.frontend.map.pixi.tiles` does not exist.

- [ ] **Step 4: Write the pure-function implementation**

Create `plugins/frontend/de/explorama/frontend/map/pixi/tiles.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.tiles
  (:require [clojure.string :as str]
            [de.explorama.frontend.map.pixi.projection :as proj]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn tile-url [template {:keys [z x y]}]
  (-> template
      (str/replace "{z}" (str z))
      (str/replace "{x}" (str x))
      (str/replace "{y}" (str y))))

(defn tile-key [{:keys [z x y]}]
  (str z "/" x "/" y))

(defn- clamp [v lo hi] (-> v (max lo) (min hi)))

(defn visible-tiles
  "Integer-zoom tiles covering the viewport."
  [{:keys [zoom width height] :as vpt}]
  (let [z (js/Math.floor zoom)
        n (js/Math.pow 2 z)
        corners [(vp/->lonlat vpt 0 0)
                 (vp/->lonlat vpt width 0)
                 (vp/->lonlat vpt 0 height)
                 (vp/->lonlat vpt width height)]
        txs (map (fn [[lon lat]] (first (proj/project lon lat))) corners)
        tys (map (fn [[lon lat]] (second (proj/project lon lat))) corners)
        minx (js/Math.floor (* (apply min txs) n))
        maxx (js/Math.floor (* (apply max txs) n))
        miny (js/Math.floor (* (apply min tys) n))
        maxy (js/Math.floor (* (apply max tys) n))]
    (for [x (range minx (inc maxx))
          y (range miny (inc maxy))]
      {:z z :x (mod x n) :y (clamp y 0 (dec n))})))
```

- [ ] **Step 5: Run to verify it passes**

Run: `npm run test-ci`
Expected: PASS for `tiles-test`.

- [ ] **Step 6: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/tiles.cljs \
        plugins/frontend_test/de/explorama/frontend/map/pixi/tiles_test.cljs \
        bundles/browser/test/de/explorama/test_runner.cljs \
        bundles/browser/test/de/explorama/test_runner_ci.cljs
git commit -m "feat(map): tile math + WMTS url templating (pure)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `engine` core — Pixi app, viewport atom, containers, camera events

Create the engine that owns the Pixi `Application`, a viewport atom, two child containers (tiles below, markers above), and DOM camera interaction (drag-pan, wheel-zoom, pinch). Rendering of tiles/markers comes in later tasks; here we prove the camera drives a viewport and re-renders. To make it visible now, draw a debug crosshair at the viewport center and a Graphics grid that repositions on viewport change.

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs`
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs` (use the engine instead of the smoke render)

**Interfaces:**
- Consumes: `viewport/pan`, `viewport/zoom-around`, `viewport/->screen`, PixiJS `Application`/`Container`/`Graphics`.
- Produces (engine state map, held in an atom returned by `create!`):
  - `(create! {:keys [canvas viewport tile-template on-viewport-change]}) -> engine` — builds the app + containers + event listeners; renders once. `engine` is a map with `:app`, `:state` (an atom holding `{:viewport ... :tile-container ... :marker-container ... :tile-template ...}`).
  - `(get-viewport engine) -> vp`
  - `(set-viewport! engine vp)` — replaces the viewport and triggers a reposition of all layers.
  - `(on-change! engine f)` — register a 1-arg callback invoked with the new viewport after every change (used by tiles/markers/popup tasks to re-render).
- Contract for later tasks: whenever the viewport changes (pan/zoom/`set-viewport!`), the engine calls every registered `on-change!` callback with the new viewport. Tile/marker/popup layers register here.

- [ ] **Step 1: Write the engine**

Create `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.engine
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Application Container Graphics]]))

(defn- notify [engine]
  (let [{:keys [state callbacks]} engine
        v (:viewport @state)]
    (doseq [f @callbacks] (f v))))

(defn on-change! [engine f]
  (swap! (:callbacks engine) conj f))

(defn get-viewport [engine] (:viewport @(:state engine)))

(defn set-viewport! [engine v]
  (swap! (:state engine) assoc :viewport v)
  (notify engine))

(defn- draw-debug-grid! [engine]
  (let [{:keys [state debug]} engine
        {:keys [viewport]} @state
        {:keys [width height]} viewport]
    (.clear debug)
    (.lineStyle debug 1 0x999999 0.5)
    ;; crosshair at centre
    (.moveTo debug (/ width 2) 0) (.lineTo debug (/ width 2) height)
    (.moveTo debug 0 (/ height 2)) (.lineTo debug width (/ height 2))))

(defn- install-events! [engine canvas]
  (let [{:keys [state]} engine
        dragging (atom nil)
        pointers (atom {})
        pinch-dist (atom nil)]
    (.addEventListener
     canvas "wheel"
     (fn [e]
       (.preventDefault e)
       (let [rect (.getBoundingClientRect canvas)
             sx (- (.-clientX e) (.-left rect))
             sy (- (.-clientY e) (.-top rect))
             dz (if (pos? (.-deltaY e)) -1 1)]
         (swap! state update :viewport vp/zoom-around dz sx sy)
         (notify engine)))
     #js {:passive false})
    (.addEventListener
     canvas "pointerdown"
     (fn [e]
       (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)])
       (when (= 1 (count @pointers))
         (reset! dragging [(.-clientX e) (.-clientY e)])))
     #js {:passive true})
    (.addEventListener
     canvas "pointermove"
     (fn [e]
       (when (contains? @pointers (.-pointerId e))
         (swap! pointers assoc (.-pointerId e) [(.-clientX e) (.-clientY e)]))
       (cond
         ;; two-finger pinch zoom
         (= 2 (count @pointers))
         (let [[[ax ay] [bx by]] (vals @pointers)
               d (js/Math.hypot (- ax bx) (- ay by))
               rect (.getBoundingClientRect canvas)
               mx (- (/ (+ ax bx) 2) (.-left rect))
               my (- (/ (+ ay by) 2) (.-top rect))]
           (when-let [pd @pinch-dist]
             (let [dz (/ (- d pd) 200)]
               (swap! state update :viewport vp/zoom-around dz mx my)
               (notify engine)))
           (reset! pinch-dist d))
         ;; single-pointer drag pan
         @dragging
         (let [[lx ly] @dragging
               dx (- (.-clientX e) lx)
               dy (- (.-clientY e) ly)]
           (reset! dragging [(.-clientX e) (.-clientY e)])
           (swap! state update :viewport vp/pan dx dy)
           (notify engine))))
     #js {:passive true})
    (let [end (fn [e]
                (swap! pointers dissoc (.-pointerId e))
                (when (not= 2 (count @pointers)) (reset! pinch-dist nil))
                (when (zero? (count @pointers)) (reset! dragging nil)))]
      (.addEventListener canvas "pointerup" end #js {:passive true})
      (.addEventListener canvas "pointerleave" end #js {:passive true})
      (.addEventListener canvas "pointercancel" end #js {:passive true}))))

(defn create! [{:keys [canvas viewport tile-template]}]
  (let [w (.-clientWidth canvas)
        h (.-clientHeight canvas)
        app (Application. (clj->js {:autoStart true
                                    :width w :height h
                                    :backgroundColor 0xEAEAEA
                                    :antialias true
                                    :resolution (or js/window.devicePixelRatio 1)
                                    :autoDensity true
                                    :view canvas}))
        tile-container (Container.)
        marker-container (Container.)
        debug (Graphics.)
        state (atom {:viewport (assoc viewport :width w :height h)
                     :tile-container tile-container
                     :marker-container marker-container
                     :tile-template tile-template})
        engine {:app app :state state :debug debug :callbacks (atom [])}]
    (.addChild (.-stage app) tile-container)
    (.addChild (.-stage app) marker-container)
    (.addChild (.-stage app) debug)
    (install-events! engine canvas)
    (on-change! engine (fn [_] (draw-debug-grid! engine)))
    (notify engine)
    engine))
```

- [ ] **Step 2: Point the sandbox at the engine**

Replace the body of `plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs` with:

```clojure
(ns de.explorama.frontend.map.pixi.sandbox
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [de.explorama.frontend.map.pixi.engine :as engine]))

(def wmts-template
  "https://sgx.geodatenzentrum.de/wmts_basemapde/tile/1.0.0/de_basemapde_web_raster_farbe/default/GLOBAL_WEBMERCATOR/{z}/{y}/{x}.png")

(defonce engine-ref (atom nil))

(defn- boot! []
  (let [canvas (.getElementById js/document "map-canvas")]
    (reset! engine-ref
            (engine/create!
             {:canvas canvas
              :tile-template wmts-template
              :viewport {:center [13.4 52.5] :zoom 6
                         :min-zoom 1 :max-zoom 19}}))))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot!))
    :reagent-render (fn [] [:canvas {:id "map-canvas"}])}))

(defn init []
  (rdom/render [page] (.getElementById js/document "app")))
```

Note: the WMTS `{z}/{y}/{x}` path order is intentional (WMTS RESTful is `TileMatrix/TileRow/TileCol`); `tile-url` fills `{x}`/`{y}` correctly regardless of order.

- [ ] **Step 3: Run the sandbox and verify manually**

Run (from `bundles/browser`): `clojure -M:sandbox`
Expected: a grey canvas with a centered crosshair. **Drag** pans (crosshair stays centered — it marks the viewport center, which is screen-center by definition; confirm no errors and the app stays responsive). **Wheel** changes zoom without errors. On a trackpad, **pinch** changes zoom. Open the console: no exceptions during interaction.

- [ ] **Step 4: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs
git commit -m "feat(map): pixi engine core with camera pan/zoom/pinch

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `tiles` — Pixi rendering + cache (manual verify)

Add tile rendering to `tiles.cljs` and register it as an engine `on-change!` layer. Tiles are positioned in **screen space** each viewport change.

**Files:**
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/tiles.cljs` (add rendering fns)
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs` (attach the tile layer)

**Interfaces:**
- Consumes: `visible-tiles`, `tile-url`, `tile-key`, `projection/unproject`, `viewport/->screen`, PixiJS `Sprite`, `Texture`, `Container`.
- Produces:
  - `(attach-tile-layer! engine)` — registers an `on-change!` callback that ensures/repositions tile sprites in `(:tile-container @(:state engine))`, keyed by `tile-key`, with an LRU-ish cap.

- [ ] **Step 1: Add tile rendering to `tiles.cljs`**

Append to `plugins/frontend/de/explorama/frontend/map/pixi/tiles.cljs` (add `Sprite`, `Texture` to a new `:require` for `pixi.js-legacy`, and `viewport`/`projection` already required):

```clojure
;; add to ns :require -> ["pixi.js-legacy" :refer [Sprite Texture]]

(def ^:private max-cached-tiles 256)

(defn- tile-sprite [template tile]
  (let [tex (.from Texture (tile-url template tile))
        s (Sprite. tex)]
    (set! (.-anchor.x s) 0)
    (set! (.-anchor.y s) 0)
    s))

(defn- place-tile! [^js sprite vpt tile]
  (let [z (:z tile)
        n (js/Math.pow 2 z)
        [lon lat] (proj/unproject (/ (:x tile) n) (/ (:y tile) n)) ; NW corner
        [sx sy] (vp/->screen vpt lon lat)
        size (* 256 (js/Math.pow 2 (- (:zoom vpt) z)))]
    (set! (.-x sprite) sx)
    (set! (.-y sprite) sy)
    (set! (.-width sprite) size)
    (set! (.-height sprite) size)))

(defn render-tiles!
  "Ensure sprites for visible tiles exist in `container`, positioned for `vpt`.
   `cache` is an atom map of tile-key -> sprite."
  [^js container cache template vpt]
  (let [wanted (visible-tiles vpt)
        wanted-keys (set (map tile-key wanted))]
    ;; remove tiles no longer visible (simple cap-based eviction)
    (doseq [[k ^js sprite] @cache
            :when (not (contains? wanted-keys k))]
      (.removeChild container sprite)
      (.destroy sprite)
      (swap! cache dissoc k))
    ;; add/reposition visible tiles
    (doseq [tile wanted
            :let [k (tile-key tile)]]
      (let [sprite (or (get @cache k)
                       (let [s (tile-sprite template tile)]
                         (.addChild container s)
                         (swap! cache assoc k s)
                         s))]
        (place-tile! sprite vpt tile)))
    (when (> (count @cache) max-cached-tiles)
      (doseq [[k ^js sprite] (take (- (count @cache) max-cached-tiles) @cache)]
        (.removeChild container sprite)
        (.destroy sprite)
        (swap! cache dissoc k)))))

(defn attach-tile-layer! [engine]
  (let [{:keys [state]} engine
        {:keys [tile-container tile-template]} @state
        cache (atom {})]
    ((requiring-resolve 'de.explorama.frontend.map.pixi.engine/on-change!) ; see note
     engine
     (fn [vpt] (render-tiles! tile-container cache tile-template vpt)))))
```

Note: `requiring-resolve` is not available in cljs. Instead, avoid a circular dependency by having the engine call `attach-tile-layer!`. Implement `attach-tile-layer!` to take the `on-change!` function explicitly:

```clojure
(defn attach-tile-layer!
  "on-change is engine/on-change! passed in to avoid a cyclic require."
  [engine on-change]
  (let [{:keys [state]} engine
        {:keys [tile-container tile-template]} @state
        cache (atom {})]
    (on-change (fn [vpt] (render-tiles! tile-container cache tile-template vpt)))))
```

- [ ] **Step 2: Attach the tile layer from the engine**

In `engine.cljs`, at the end of `create!` (before `(notify engine)`), add a require of `tiles` and attach the layer. Update the `ns` `:require` to include `[de.explorama.frontend.map.pixi.tiles :as tiles]`, and in `create!` replace the debug-grid registration with:

```clojure
    (tiles/attach-tile-layer! engine on-change!)
    (on-change! engine (fn [_] (draw-debug-grid! engine)))
```

(`tiles` requires `viewport`/`projection` only, so `engine -> tiles` is acyclic.)

- [ ] **Step 3: Run the sandbox and verify manually**

Run: `clojure -M:sandbox`
Expected: the **basemap.de raster tiles render** over Germany at zoom 6. Dragging pans the map smoothly and new tiles load at the edges; wheel/pinch zoom loads the appropriate zoom level. The crosshair stays centered. No console errors (a few 404s for out-of-range tiles at the poles are acceptable).

If basemap.de is unreachable in your environment, temporarily set `wmts-template` in `sandbox.cljs` to `"https://a.tile.openstreetmap.de/{z}/{x}/{y}.png"` to verify, then restore the WMTS template.

- [ ] **Step 4: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/tiles.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs
git commit -m "feat(map): WMTS raster tile rendering + cache

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `markers` — render ~1k markers (manual verify)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/markers.cljs`
- Modify: `engine.cljs` (attach marker layer, expose `set-markers!`)
- Modify: `sandbox.cljs` (generate ~1k demo markers + a "regenerate" button)

**Interfaces:**
- Consumes: `viewport/->screen`, PixiJS `Graphics`, `Sprite`, `Texture`, `Container`.
- A **marker** is `{:id <any> :lon <deg> :lat <deg> :color <int rgb> :highlighted? <bool>}`.
- Produces:
  - `(circle-texture app radius) -> Texture` — one white filled circle, reused by all markers (tinted per marker).
  - `(render-markers! app container index markers vpt)` — sync sprites in `container` to `markers` (add/remove/reposition/tint/highlight). `index` is an atom map of `id -> sprite`.
  - `attach-marker-layer!` and engine helpers `set-markers!`/`get-markers` (see engine step).

- [ ] **Step 1: Write `markers.cljs`**

Create `plugins/frontend/de/explorama/frontend/map/pixi/markers.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.markers
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]
            ["pixi.js-legacy" :refer [Graphics Sprite]]))

(def ^:const base-radius 6)

(defn circle-texture [^js app radius]
  (let [g (Graphics.)]
    (.beginFill g 0xffffff)
    (.drawCircle g radius radius radius)
    (.endFill g)
    (.generateTexture (.-renderer app) g)))

(defn- ensure-sprite [^js container ^js texture index id]
  (or (get @index id)
      (let [s (Sprite. texture)]
        (set! (.-anchor.x s) 0.5)
        (set! (.-anchor.y s) 0.5)
        (.addChild container s)
        (swap! index assoc id s)
        s)))

(defn render-markers!
  [^js _app ^js container ^js texture index markers vpt]
  (let [wanted (set (map :id markers))]
    (doseq [[id ^js sprite] @index
            :when (not (contains? wanted id))]
      (.removeChild container sprite)
      (.destroy sprite)
      (swap! index dissoc id))
    (doseq [{:keys [id lon lat color highlighted?]} markers]
      (let [^js s (ensure-sprite container texture index id)
            [sx sy] (vp/->screen vpt lon lat)
            scale (if highlighted? 1.6 1.0)]
        (set! (.-x s) sx)
        (set! (.-y s) sy)
        (set! (.-tint s) (or color 0x000000))
        (set! (.-alpha s) 0.7)
        (set! (.-scale.x s) scale)
        (set! (.-scale.y s) scale)))))
```

- [ ] **Step 2: Wire the marker layer into the engine**

In `engine.cljs`:
- add `[de.explorama.frontend.map.pixi.markers :as markers]` to the `ns` `:require`;
- store markers + texture in state; add helpers and an attach step.

Add these functions and extend `create!`:

```clojure
(defn get-markers [engine] (:markers @(:state engine)))

(defn set-markers! [engine markers]
  (swap! (:state engine) assoc :markers markers)
  (notify engine))

;; in create!, after building `app`, create the texture and store it + empty markers:
;;   marker-texture (markers/circle-texture app markers/base-radius)
;; add to the state map: :markers []  :marker-texture marker-texture  :marker-index (atom {})
;; then register the layer (after attach-tile-layer!):
;;   (on-change! engine
;;     (fn [vpt]
;;       (let [{:keys [marker-container markers marker-texture marker-index]} @state]
;;         (markers/render-markers! app marker-container marker-texture marker-index markers vpt))))
```

Concretely, `create!`'s `state` atom map becomes:

```clojure
        state (atom {:viewport (assoc viewport :width w :height h)
                     :tile-container tile-container
                     :marker-container marker-container
                     :tile-template tile-template
                     :markers []
                     :marker-texture (markers/circle-texture app markers/base-radius)
                     :marker-index (atom {})})
```

and after `(tiles/attach-tile-layer! engine on-change!)` add:

```clojure
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [marker-container markers marker-texture marker-index]} @state]
                    (markers/render-markers! app marker-container marker-texture marker-index markers vpt))))
```

- [ ] **Step 3: Generate demo markers in the sandbox**

Update `sandbox.cljs` to generate ~1k markers and add a toolbar. Replace `page`/`boot!` with:

```clojure
(defn- demo-markers [n]
  (mapv (fn [i]
          {:id i
           :lon (+ 6.0 (rand 9.0))     ; roughly across Germany
           :lat (+ 47.5 (rand 7.0))
           :color (rand-nth [0xd62728 0x1f77b4 0x2ca02c 0xff7f0e])})
        (range n)))

(defn- boot! []
  (let [canvas (.getElementById js/document "map-canvas")
        e (engine/create!
           {:canvas canvas
            :tile-template wmts-template
            :viewport {:center [10.5 51.0] :zoom 6 :min-zoom 1 :max-zoom 19}})]
    (reset! engine-ref e)
    (engine/set-markers! e (demo-markers 1000))))

(defn- page []
  (r/create-class
   {:component-did-mount (fn [_] (boot!))
    :reagent-render
    (fn []
      [:div
       [:div.sandbox-toolbar
        [:button {:on-click #(engine/set-markers! @engine-ref (demo-markers 1000))}
         "Regenerate 1k"]]
       [:canvas {:id "map-canvas"}]])}))
```

- [ ] **Step 4: Run the sandbox and verify manually**

Run: `clojure -M:sandbox`
Expected: ~1000 colored dots over Germany on the basemap. Panning/zooming keeps dots pinned to their geographic positions and stays smooth. "Regenerate 1k" swaps the set instantly.

- [ ] **Step 5: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/markers.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs
git commit -m "feat(map): render ~1k markers as tinted pixi sprites

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `clustering` — grid binning (pure, TDD)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/clustering.cljs`
- Test: `plugins/frontend_test/de/explorama/frontend/map/pixi/clustering_test.cljs`
- Modify: both test runners.

**Interfaces:**
- Consumes: `viewport/->screen`.
- Produces:
  - `(cluster markers vpt cell-px) -> (seq node)` where a node is either a single marker with `{:cluster? false :count 1}` merged in, or `{:cluster? true :count n :lon <avg> :lat <avg> :members [markers]}`. Markers are binned by screen cell of size `cell-px`.

- [ ] **Step 1: Write the failing test**

Create `plugins/frontend_test/de/explorama/frontend/map/pixi/clustering_test.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.clustering-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.clustering :as clustering]))

(def vp {:center [10.0 51.0] :zoom 6 :width 800 :height 600
         :min-zoom 1 :max-zoom 19})

(deftest far-apart-markers-stay-separate
  (let [ms [{:id 1 :lon 6.0 :lat 48.0} {:id 2 :lon 14.0 :lat 54.0}]
        nodes (clustering/cluster ms vp 60)]
    (is (= 2 (count nodes)))
    (is (every? #(false? (:cluster? %)) nodes))))

(deftest near-markers-merge
  (let [ms [{:id 1 :lon 10.000 :lat 51.000}
            {:id 2 :lon 10.001 :lat 51.001}
            {:id 3 :lon 10.002 :lat 51.000}]
        nodes (clustering/cluster ms vp 80)
        cl (first (filter :cluster? nodes))]
    (is (= 1 (count nodes)))
    (is (:cluster? cl))
    (is (= 3 (:count cl)))
    (is (= 3 (count (:members cl))))))

(deftest count-total-preserved
  (let [ms (mapv (fn [i] {:id i :lon (+ 10 (* 0.5 (mod i 5))) :lat (+ 51 (* 0.5 (quot i 5)))})
                 (range 20))
        nodes (clustering/cluster ms vp 50)]
    (is (= 20 (reduce + (map :count nodes))))))
```

- [ ] **Step 2: Register the test namespace in both runners**

Add `[de.explorama.frontend.map.pixi.clustering-test]` to both runners.

- [ ] **Step 3: Run to verify it fails**

Run: `npm run test-ci`
Expected: FAIL — `de.explorama.frontend.map.pixi.clustering` does not exist.

- [ ] **Step 4: Write the implementation**

Create `plugins/frontend/de/explorama/frontend/map/pixi/clustering.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.clustering
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn cluster
  "Grid-bin markers by screen cell of size cell-px at the current viewport."
  [markers vpt cell-px]
  (let [cells (group-by (fn [{:keys [lon lat]}]
                          (let [[sx sy] (vp/->screen vpt lon lat)]
                            [(js/Math.floor (/ sx cell-px))
                             (js/Math.floor (/ sy cell-px))]))
                        markers)]
    (mapv (fn [[_ ms]]
            (if (= 1 (count ms))
              (assoc (first ms) :cluster? false :count 1)
              (let [n (count ms)]
                {:cluster? true
                 :count n
                 :lon (/ (reduce + (map :lon ms)) n)
                 :lat (/ (reduce + (map :lat ms)) n)
                 :members (vec ms)})))
          cells)))
```

- [ ] **Step 5: Run to verify it passes**

Run: `npm run test-ci`
Expected: PASS for `clustering-test`.

- [ ] **Step 6: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/clustering.cljs \
        plugins/frontend_test/de/explorama/frontend/map/pixi/clustering_test.cljs \
        bundles/browser/test/de/explorama/test_runner.cljs \
        bundles/browser/test/de/explorama/test_runner_ci.cljs
git commit -m "feat(map): grid-bin clustering (pure)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: cluster rendering + click-to-zoom (manual verify)

Render clusters instead of raw markers when zoomed out: the marker layer now renders the output of `clustering/cluster`. Cluster nodes draw as a bubble + count label; clicking a cluster zooms to fit its members.

**Files:**
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/markers.cljs` (render cluster nodes)
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs` (compute clusters in the marker layer; expose click handling hook)

**Interfaces:**
- Consumes: `clustering/cluster`, `viewport/->screen`, `viewport/fit-extent`, PixiJS `Container`, `Graphics`, `Text`.
- Produces:
  - `(render-nodes! app container index nodes vpt)` — renders a mix of single-marker sprites and cluster bubbles (Graphics circle sized by count + `Text` count), keyed by a node key (`id` for singles, `"cluster:cx:cy"` for clusters).
  - engine state gains `:cluster-cell-px` (default 60); the marker on-change callback computes `nodes = (clustering/cluster markers vpt cell-px)` and calls `render-nodes!`.
  - `(nodes-at engine sx sy)` deferred to Task 10; here clicking is handled inline: cluster bubbles carry their bbox for `fit-extent`.

- [ ] **Step 1: Replace marker rendering with node rendering in `markers.cljs`**

Add to the `ns` `:require`: `[de.explorama.frontend.map.pixi.viewport :as vp]` (already there) and `["pixi.js-legacy" :refer [Graphics Sprite Text Container]]`. Add:

```clojure
(defn- cluster-radius [count]
  (+ 10 (min 24 (* 4 (js/Math.log count)))))

(defn- node-key [node]
  (if (:cluster? node)
    (str "cluster:" (.toFixed (:lon node) 4) ":" (.toFixed (:lat node) 4))
    (:id node)))

(defn render-nodes!
  "Render single markers as tinted sprites and clusters as bubble+count.
   `index` maps node-key -> {:kind :marker|:cluster :obj <displayobject> :node <node>}."
  [^js _app ^js container ^js texture index nodes vpt]
  (let [wanted (set (map node-key nodes))]
    (doseq [[k entry] @index
            :when (not (contains? wanted k))]
      (.removeChild container (:obj entry))
      (.destroy (:obj entry) #js {:children true})
      (swap! index dissoc k))
    (doseq [node nodes
            :let [k (node-key node)
                  [sx sy] (vp/->screen vpt (:lon node) (:lat node))]]
      (if (:cluster? node)
        (let [entry (or (get @index k)
                        (let [c (Container.)
                              g (Graphics.)
                              t (Text. "" (clj->js {:fontSize 11 :fill 0xffffff :fontFamily "sans-serif"}))]
                          (set! (.-anchor.x t) 0.5) (set! (.-anchor.y t) 0.5)
                          (.addChild c g) (.addChild c t)
                          (.addChild container c)
                          (let [e {:kind :cluster :obj c :g g :t t :node node}]
                            (swap! index assoc k e) e)))
              ^js g (:g entry)
              ^js t (:t entry)
              r (cluster-radius (:count node))]
          (.clear g)
          (.beginFill g 0x1f77b4 0.85) (.drawCircle g 0 0 r) (.endFill g)
          (set! (.-text t) (str (:count node)))
          (set! (.-x (:obj entry)) sx) (set! (.-y (:obj entry)) sy)
          (swap! index assoc-in [k :node] node))
        (let [entry (or (get @index k)
                        (let [s (Sprite. texture)]
                          (set! (.-anchor.x s) 0.5) (set! (.-anchor.y s) 0.5)
                          (.addChild container s)
                          (let [e {:kind :marker :obj s :node node}]
                            (swap! index assoc k e) e)))
              ^js s (:obj entry)]
          (set! (.-x s) sx) (set! (.-y s) sy)
          (set! (.-tint s) (or (:color node) 0x000000))
          (set! (.-alpha s) 0.7)
          (set! (.-scale.x s) (if (:highlighted? node) 1.6 1.0))
          (set! (.-scale.y s) (if (:highlighted? node) 1.6 1.0))
          (swap! index assoc-in [k :node] node))))))
```

- [ ] **Step 2: Use node rendering + cluster clicks in the engine**

In `engine.cljs`:
- add `[de.explorama.frontend.map.pixi.clustering :as clustering]` to `:require`;
- change the state map to include `:cluster-cell-px 60` and `:node-index (atom {})` (replace `:marker-index`);
- replace the marker on-change callback with a node-rendering one;
- add a `pointerdown`-driven cluster click: on a single (non-drag) click, if it lands on a cluster bubble, `fit-extent` to the cluster members' bbox.

Replace the marker layer registration in `create!` with:

```clojure
    (on-change! engine
                (fn [vpt]
                  (let [{:keys [marker-container markers marker-texture node-index cluster-cell-px]} @state
                        nodes (clustering/cluster markers vpt cluster-cell-px)]
                    (markers/render-nodes! app marker-container marker-texture node-index nodes vpt))))
```

Add cluster-click handling inside `install-events!`'s `pointerup` `end` handler: if the pointer moved less than 4px since `pointerdown` (a click, not a drag), hit-test cluster nodes:

```clojure
;; capture press position in pointerdown: (reset! press [(.-clientX e) (.-clientY e)])
;; in end: when it was a click, run:
(defn- try-cluster-click [engine canvas cx cy]
  (let [{:keys [state]} engine
        {:keys [viewport node-index]} @state
        rect (.getBoundingClientRect canvas)
        sx (- cx (.-left rect))
        sy (- cy (.-top rect))]
    (some (fn [[_ entry]]
            (when (= :cluster (:kind entry))
              (let [node (:node entry)
                    [nx ny] (vp/->screen viewport (:lon node) (:lat node))
                    r 24
                    dx (- nx sx) dy (- ny sy)]
                (when (<= (+ (* dx dx) (* dy dy)) (* r r))
                  (let [lons (map :lon (:members node))
                        lats (map :lat (:members node))
                        bbox [(apply min lons) (apply min lats)
                              (apply max lons) (apply max lats)]]
                    (swap! state update :viewport vp/fit-extent bbox)
                    (notify engine)
                    true)))))
          @node-index)))
```

Wire `try-cluster-click` into the `end` handler (only when `press` and the release position differ by < 4px). Keep an atom `press` alongside `dragging` in `install-events!`.

- [ ] **Step 3: Run the sandbox and verify manually**

Run: `clojure -M:sandbox`
Expected: at zoom 6 the 1k markers collapse into blue count-bubbles; zooming in splits them into smaller clusters and eventually single dots. Clicking a cluster zooms to fit its members. Panning/zooming re-clusters smoothly.

- [ ] **Step 4: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/markers.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs
git commit -m "feat(map): cluster bubbles with count + click-to-zoom

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `picking` — hit-test (pure, TDD) + hover/click wiring (manual)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/picking.cljs`
- Test: `plugins/frontend_test/de/explorama/frontend/map/pixi/picking_test.cljs`
- Modify: both test runners; `engine.cljs` (expose picking + `on-pick` callback).

**Interfaces:**
- Consumes: `viewport/->screen`.
- Produces:
  - `(pick items vpt sx sy) -> item | nil` — returns the first item whose projected screen position is within `(:radius item)` px of `(sx,sy)`. `item` is a node `{:lon :lat :radius ...}`.
  - engine: `(on-pick engine f)` registers a 1-arg callback invoked with the picked node (or `nil`) on click; `pointermove` updates a `:hovered` node for highlight.

- [ ] **Step 1: Write the failing test**

Create `plugins/frontend_test/de/explorama/frontend/map/pixi/picking_test.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.picking-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.picking :as picking]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(def vpt {:center [10.0 51.0] :zoom 6 :width 800 :height 600
          :min-zoom 1 :max-zoom 19})

(deftest picks-item-under-cursor
  (let [item {:id 1 :lon 10.0 :lat 51.0 :radius 8}
        [sx sy] (vp/->screen vpt 10.0 51.0)]
    (is (= 1 (:id (picking/pick [item] vpt sx sy))))
    (is (= 1 (:id (picking/pick [item] vpt (+ sx 5) sy))))))

(deftest misses-when-outside-radius
  (let [item {:id 1 :lon 10.0 :lat 51.0 :radius 8}
        [sx sy] (vp/->screen vpt 10.0 51.0)]
    (is (nil? (picking/pick [item] vpt (+ sx 40) sy)))))

(deftest returns-first-hit
  (let [a {:id :a :lon 10.0 :lat 51.0 :radius 10}
        b {:id :b :lon 10.0 :lat 51.0 :radius 10}
        [sx sy] (vp/->screen vpt 10.0 51.0)]
    (is (= :a (:id (picking/pick [a b] vpt sx sy))))))
```

- [ ] **Step 2: Register the test namespace in both runners**

Add `[de.explorama.frontend.map.pixi.picking-test]` to both runners.

- [ ] **Step 3: Run to verify it fails**

Run: `npm run test-ci`
Expected: FAIL — `de.explorama.frontend.map.pixi.picking` does not exist.

- [ ] **Step 4: Write the implementation**

Create `plugins/frontend/de/explorama/frontend/map/pixi/picking.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.picking
  (:require [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn pick
  "First item whose screen position is within (:radius item) px of (sx,sy)."
  [items vpt sx sy]
  (some (fn [{:keys [lon lat radius] :as item}]
          (let [[ix iy] (vp/->screen vpt lon lat)
                dx (- ix sx) dy (- iy sy)
                r (or radius 8)]
            (when (<= (+ (* dx dx) (* dy dy)) (* r r))
              item)))
        items))
```

- [ ] **Step 5: Run to verify it passes**

Run: `npm run test-ci`
Expected: PASS for `picking-test`.

- [ ] **Step 6: Wire picking into the engine (manual verify)**

In `engine.cljs`:
- add `[de.explorama.frontend.map.pixi.picking :as picking]` to `:require`;
- keep the current rendered nodes available for picking: in the node-render on-change callback, also `(swap! state assoc :nodes nodes)`;
- add `(defn on-pick [engine f] (swap! (:pick-callbacks engine) conj f))` and initialise `:pick-callbacks (atom [])` in the `engine` map;
- in the `end`/click handler, before the cluster check, compute `picked = (picking/pick (map #(assoc % :radius 8) (:nodes @state)) (:viewport @state) sx sy)` and invoke pick callbacks with it; on `pointermove` (non-drag), set the hovered node and re-render highlight.

Verify: run `clojure -M:sandbox`; clicking a single dot logs/marks it (a `println` in a temporary `on-pick` callback is fine for this step), hovering enlarges it. No errors.

- [ ] **Step 7: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/picking.cljs \
        plugins/frontend_test/de/explorama/frontend/map/pixi/picking_test.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs \
        bundles/browser/test/de/explorama/test_runner.cljs \
        bundles/browser/test/de/explorama/test_runner_ci.cljs
git commit -m "feat(map): pointer picking + hover/click wiring

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: `popup` — Reagent DOM overlay (manual verify)

**Files:**
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/popup.cljs`
- Modify: `sandbox.cljs` (render the popup, drive it from `on-pick` + viewport changes)

**Interfaces:**
- Consumes: `viewport/->screen`, Reagent.
- A **popup state** atom holds `nil` or `{:lon :lat :content <hiccup or string>}`.
- Produces:
  - `(popup-view popup-state engine-ref) -> hiccup` — an absolutely-positioned `.sandbox-popup` div at the screen position of the popup's lon/lat; renders nothing when the popup state is `nil`. Repositions on viewport change (the sandbox forces a re-render via a viewport tick atom).

- [ ] **Step 1: Write `popup.cljs`**

Create `plugins/frontend/de/explorama/frontend/map/pixi/popup.cljs`:

```clojure
(ns de.explorama.frontend.map.pixi.popup
  (:require [de.explorama.frontend.map.pixi.engine :as engine]
            [de.explorama.frontend.map.pixi.viewport :as vp]))

(defn popup-view
  "popup-state: reagent atom of nil | {:lon :lat :content}.
   tick: reagent atom bumped on every viewport change (forces reposition).
   engine-ref: atom holding the engine."
  [popup-state tick engine-ref]
  @tick ;; deref so we re-render on viewport changes
  (when-let [{:keys [lon lat content]} @popup-state]
    (when-let [e @engine-ref]
      (let [[sx sy] (vp/->screen (engine/get-viewport e) lon lat)]
        [:div.sandbox-popup {:style {:left (str sx "px") :top (str sy "px")}}
         content]))))
```

- [ ] **Step 2: Drive the popup from the sandbox**

In `sandbox.cljs`:
- add `[de.explorama.frontend.map.pixi.popup :as popup]` and `[de.explorama.frontend.map.pixi.engine :as engine]` to `:require`;
- add `(defonce popup-state (r/atom nil))` and `(defonce vp-tick (r/atom 0))`;
- in `boot!`, after creating the engine, register `(engine/on-change! e (fn [_] (swap! vp-tick inc)))` and an `on-pick` callback:

```clojure
    (engine/on-pick e
      (fn [node]
        (reset! popup-state
                (when (and node (not (:cluster? node)))
                  {:lon (:lon node) :lat (:lat node)
                   :content [:div
                             [:strong "Event " (str (:id node))]
                             [:div (str "lon " (.toFixed (:lon node) 3)
                                        ", lat " (.toFixed (:lat node) 3))]]}))))
```

- render the popup in `page` alongside the canvas:

```clojure
       [popup/popup-view popup-state vp-tick engine-ref]
```

- [ ] **Step 3: Run the sandbox and verify manually**

Run: `clojure -M:sandbox`
Expected: clicking a single dot opens a small DOM popup above it showing the event id + coordinates; the popup follows the dot when panning/zooming; clicking empty space or a cluster closes it.

- [ ] **Step 4: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/popup.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs
git commit -m "feat(map): reagent DOM popup overlay

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: zoom-to-data button + dev README + acceptance pass

Add the final feature (zoom-to-data via `viewport/fit-extent`) and a short dev README, then run the full success-criteria checklist.

**Files:**
- Modify: `plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs` (add `fit-markers!`)
- Modify: `sandbox.cljs` (add "Zoom to data" button)
- Create: `plugins/frontend/de/explorama/frontend/map/pixi/README.md`

**Interfaces:**
- Consumes: `viewport/fit-extent`, existing engine helpers.
- Produces: `(fit-markers! engine)` — `fit-extent`s the viewport to the bbox of all current markers.

- [ ] **Step 1: Add `fit-markers!` to the engine**

In `engine.cljs` add:

```clojure
(defn fit-markers! [engine]
  (let [{:keys [markers]} @(:state engine)]
    (when (seq markers)
      (let [lons (map :lon markers) lats (map :lat markers)
            bbox [(apply min lons) (apply min lats) (apply max lons) (apply max lats)]]
        (swap! (:state engine) update :viewport vp/fit-extent bbox)
        (notify engine)))))
```

- [ ] **Step 2: Add the toolbar button**

In `sandbox.cljs`, add to `.sandbox-toolbar`:

```clojure
        [:button {:on-click #(engine/fit-markers! @engine-ref)} "Zoom to data"]
```

- [ ] **Step 3: Write the dev README**

Create `plugins/frontend/de/explorama/frontend/map/pixi/README.md`:

```markdown
# PixiJS Map Engine (prototype)

Standalone prototype of an in-house map renderer on PixiJS, evaluating a
replacement for the OpenLayers-based `map` plugin. No new npm dependencies.

## Run the sandbox

    cd bundles/browser
    npm install
    clojure -M:sandbox
    # opens http://localhost:8020/sandbox.html

## What it demonstrates

- WMTS raster basemap (basemap.de), Web-Mercator projection, smooth
  pan / wheel-zoom / pinch-zoom camera.
- ~1000 markers rendered as tinted Pixi sprites, pinned to geo positions.
- Grid-bin clustering with count bubbles + click-to-zoom.
- Hover highlight, click picking, Reagent DOM popup.
- "Zoom to data" (fit all markers).

## Modules

- `projection` — Web Mercator math (unit-tested)
- `viewport`   — camera math: ->screen / ->lonlat / pan / zoom-around / fit-extent (unit-tested)
- `tiles`      — visible-tile math (unit-tested) + Pixi tile rendering/cache
- `clustering` — grid-bin clustering (unit-tested)
- `picking`    — hit-test (unit-tested)
- `markers`    — Pixi marker + cluster rendering
- `engine`     — Pixi app, viewport, camera events, layer wiring
- `popup`      — Reagent DOM overlay
- `sandbox`    — dev entry (not shipped)

## Deferred (post-prototype)

Feature layers (heatmap / movement / area), GeoJSON+ESRI overlays,
WMS/ArcGIS sources, supercluster-quality clustering, the search
bounding-box widget, and wiring behind the plugin's `impl/pixi/*`
protocol implementations (`:pixi` case in `map/api.cljs`).
```

- [ ] **Step 4: Run the full test suite**

Run (from `bundles/browser`): `npm run test-ci`
Expected: PASS, including all five new pixi test namespaces (`projection`, `viewport`, `tiles`, `clustering`, `picking`). No regressions in the existing suite.

- [ ] **Step 5: Run the acceptance checklist in the sandbox**

Run: `clojure -M:sandbox`. Confirm each success criterion from the spec:
1. WMTS basemap renders and pans/zooms (wheel + pinch) smoothly.
2. ~1k markers render, styled, with working hover highlight.
3. Grid clustering shows count bubbles; clicking a cluster zooms/expands.
4. Hover/click resolves correctly and opens a positioned re-frame/DOM popup.
5. "Zoom to data" fits all markers.
6. No new npm dependency was added (`git diff main -- bundles/browser/package.json` is empty).

- [ ] **Step 6: Commit**

```bash
git add plugins/frontend/de/explorama/frontend/map/pixi/engine.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/sandbox.cljs \
        plugins/frontend/de/explorama/frontend/map/pixi/README.md
git commit -m "feat(map): zoom-to-data + pixi map prototype dev README

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- WMTS basemap → Tasks 4, 6. ✓
- Web Mercator projection → Task 2. ✓
- Smooth pan/wheel/pinch camera → Tasks 3 (math), 5 (events). ✓
- ~1k markers, styling + highlight → Tasks 7, 10 (hover highlight). ✓
- Grid clustering + count + click-to-zoom → Tasks 8, 9. ✓
- Picking hover/click → Task 10. ✓
- Reagent DOM popup → Task 11. ✓
- Zoom-to-data → Task 12. ✓
- Standalone figwheel sandbox deliverable → Task 1 + used throughout. ✓
- No new npm deps → Global Constraints + verified in Task 12 step 5. ✓
- Engine written protocol-agnostic (no `impl/pixi/*` wiring in the prototype) → all engine modules are plugin-independent; Phase B deferral documented in README (Task 12). ✓
- Deferred items (feature layers, overlays, WMS/ArcGIS, supercluster, search widget, project/replay) → explicitly not tasked; recorded in README. ✓

**Placeholder scan:** No "TBD"/"handle edge cases"/"similar to Task N". The one `requiring-resolve` dead-end in Task 6 is explicitly called out and replaced with the passed-in `on-change` function to avoid a cyclic require — intentional guidance, not a placeholder.

**Type consistency:**
- `viewport` map shape `{:center [lon lat] :zoom :width :height :min-zoom :max-zoom}` used identically across viewport/tiles/clustering/picking/engine tests and code. ✓
- `->screen`/`->lonlat`/`pan`/`zoom-around`/`fit-extent` signatures consistent between Task 3 definition and all callers (tiles, clustering, picking, engine). ✓
- Marker shape `{:id :lon :lat :color :highlighted?}` consistent between markers, clustering, picking, sandbox. ✓
- Node shape from `clustering/cluster` (`:cluster? :count :lon :lat :members`) consistent between Task 8 output and Task 9 renderer. ✓
- `tile-url`/`tile-key`/`visible-tiles` names consistent between Task 4 and Task 6. ✓
- Engine API (`create!`, `on-change!`, `set-markers!`, `on-pick`, `get-viewport`, `fit-markers!`) consistent between engine tasks and sandbox/popup consumers. ✓
