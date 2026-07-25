# Pixi Map Stage 2 Implementation Plan (GeoJSON overlayers + area feature layer)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** Real GeoJSON/Esri overlayers and area-coloring feature layers on the Pixi backend, replacing their stage-1 alert stubs.

**Architecture:** Pure `geo` module (parse + project + hit-test) → `vector_layer` Pixi renderer → engine vector-layer registry (between tiles and markers) → adapter wiring (overlayer + `:feature` layer paths, click→popup callbacks). Spec: `docs/superpowers/specs/2026-07-25-pixi-map-stage2-design.md`.

**Tech Stack:** as stage 1; **zero new npm deps**.

## Global Constraints

- Suite baseline at stage start: **177 deftests 0/0** (`npm run test-ci`, bundles/browser). Both browser test runners get every new test ns.
- Engine speaks `[lon lat]`/world coords; GeoJSON coordinate order is `[lon lat]` already — `geo.cljs` documents this explicitly (NO reversal, unlike the marker path).
- Callback shapes (must match `map/core.cljs:284-287` exactly): `(:overlayer-feature-clicked f) [overlayer-id feature-properties clicked-position view-position]`, `(:area-feature-clicked f) [area-feature-id feature-properties clicked-position view-position]`; `feature-properties` = raw properties map minus `"geometry"`; `clicked-position` `[lat lon]`; `view-position` `{:center [lat lon] :zoom}`.
- Area data-join semantics (OL parity): `data-key = (select-keys (:properties feature) (keys feature-properties-config))`, entry = `(get data-set data-key)` → `{:color "#hex" :opacity n :value .. :attribute .. :feature-names ..}`; nil entry → feature not rendered.
- Movement/heatmap stubs and notices stay untouched.
- Commits conventional, one per task, trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Foreground commands only; nothing to bare /tmp; NODE_COMPILE_CACHE for npm runs.

## Tasks

### Task 1: `geo` — GeoJSON parsing + hit-testing (pure, TDD)
Files: create `plugins/frontend/de/explorama/frontend/map/pixi/geo.cljs`, test `plugins/frontend_test/de/explorama/frontend/map/pixi/geo_test.cljs`, register both runners.
Produces: `(parse-features geojson-js-or-clj) -> [feature]` with feature `{:properties {..} :kind :polygon|:line|:point :rings [[[wx wy]..]..]}` (polygon ring 0 = outer, rest = holes; MultiPolygon → one feature per polygon sharing properties; lines: `:rings` = one path per ring entry; points: single-coord rings). World coords via `projection/project` (returns [wx wy] in [0,1]). Accepts js objects or clj maps (use `js->clj` with keywordize false internally — GeoJSON keys are strings: `"type"`, `"features"`, `"geometry"`, `"coordinates"`, `"properties"`).
`(point-in-feature? feature wx wy)` — ray casting over ring 0 minus holes (polygons; false for other kinds). `(feature-bbox feature) -> [minx miny maxx maxy]`, `(features-bbox features)`.
Tests: a small FeatureCollection literal with one triangle Polygon around a known lon/lat (assert projected containment: point inside → true, outside → false), a Polygon with a hole (point in hole → false), a MultiPolygon (two features out), a LineString (kind :line, point-in-feature? false), properties preserved as string-keyed map, bbox correctness. RED → GREEN → commit `feat(map): geojson parsing and hit-testing for pixi vector layers`.

### Task 2: `vector_layer` renderer + engine vector-layer API + sandbox demo
Files: create `plugins/frontend/de/explorama/frontend/map/pixi/vector_layer.cljs`; modify `engine.cljs`, `sandbox.cljs`.
`vector_layer/draw!`: `(draw! ^js g features vpt {:keys [style style-fn]})` — clears g; per feature: resolve style (style-fn feature → nil skips; else static style); polygons: `beginFill fill-color fill-alpha`, `lineStyle stroke-width stroke-color stroke-alpha`, `drawPolygon` of ring-0 screen points (world→screen: `sx = (+ (* (- wx cwx) s) (/ width 2))` — reuse `viewport/->screen`? rings hold world coords, so add a small `(world->screen vpt wx wy)` helper to `viewport.cljs` mirroring `->screen` without re-projecting, and use it here and in engine picking), holes via `beginHole`/`endHole` per remaining ring; lines: `lineStyle` + moveTo/lineTo per path; points: small `drawCircle` r 4. Engine: state gains `:vector-layers` (ordered map id → `{:features :style :style-fn :visible?}`) + `vector-container` added to stage AFTER tile-container and BEFORE marker-container, one child Graphics per layer (created on add, destroyed on remove); redraw visible layers in the node on-change callback; `add-vector-layer!`/`remove-vector-layer!`/`set-vector-layer-visible!`/`(pick-vector-feature engine sx sy)` (screen→world via `viewport/->lonlat`+`projection/project`, or add `screen->world` helper; iterate visible layers LAST-added-first, features reverse order, polygons only stage-2 picking). Sandbox: "Demo overlay" button adding a hardcoded small GeoJSON polygon layer over Germany + a second click toggles visibility.
Verify: test-ci no regressions (177 + Task-1 count), dev + sandbox build-once clean, kondo clean. Commit `feat(map): pixi vector layers with polygon rendering and picking`.

### Task 3: adapter wiring — overlayers + area feature layer real (TDD for the join)
Files: modify `map/map/impl/pixi/{object_manager,state_handler,instance}.cljs`; create `plugins/frontend_test/de/explorama/frontend/map/map/impl/pixi/area_join_test.cljs` (+ runners).
- `instance.cljs`: add pure `(area-style-fn feature-properties-config data-set)` returning a `style-fn`: for feature → data-key via select-keys of string-keyed properties, entry lookup, nil → nil, else `{:fill-color (style/hex->int (:color entry)) :fill-alpha (or (:opacity entry) 0.5) :stroke-width 1 :stroke-color 0x4b4f53 :stroke-alpha 0.9}`. TDD this (test: matching/non-matching props, hex conversion, opacity default).
- Overlayer style constant (OL parity, solid): stroke 1.5 `0x4b4f53` alpha 0.9, fill `0x364655` alpha 0.15.
- `object_manager.cljs` `create-overlayer`: resolve features per `:type` (`"geojson"` → `(when-let [gj ((:geojson-object extra-fns) (:file-path desc))] (geo/parse-features gj))`; anything else → Esri path: build the query URL exactly as historical `overlayer.cljs:88-101` did (`git show aab222a:...overlayer.cljs` — replicate URL construction with `f=geojson`), `js/fetch` → `.json` → `geo/parse-features`; async: stage into instance `:vector-layers` and, when engine booted, `add-vector-layer!` on resolve, routing settle load-start/end around the fetch via engine hooks (add `(engine/track-async! engine thunk-done)`-style minimal API or reuse the settle wrappers — implementer detail, document). Empty/missing geometry → layer staged with `:features []`, no crash, log a warn.
- `state_handler.cljs`: `display-overlayer`/`hide-overlayer` → set-vector-layer-visible! + `:active-overlayers` set (real `list-active-overlayers`); remove the `:overlayer` notify. `create-feature-layer` (desc from `(:feature-layer-config extra-fns)` — static config with `:type "geojson"|"esri"`, `:file-path`/`:server-url`, `:feature-properties`) + layer-data param from the protocol call (`{:data-set ..}` — read `map/core.cljs:409-439` for the exact call shape BEFORE coding): geometry like overlayers, style-fn = `area-style-fn`; `display-feature-layer`/`hide-feature-layer` visible toggles + `:active-feature-layers` (real list), `remove-feature-layer`/`clear-feature-layers` remove engine layers; remove the `:feature-layer` notify; keep `:movement`/`:heatmap` create-stubs notifying.
- Click wiring in ensure-engine! on-pick: on nil pick (no marker/cluster), `(engine/pick-vector-feature ...)`; area layer ids live in a distinguishable registry (`:vector-layers` entries carry `:kind :overlayer|:area`): overlayer → `overlayer-feature-clicked`, area → `area-feature-clicked`, with properties minus `"geometry"` (geo/parse-features already strips geometry — assert), `[lat lon]` of the click via `->lonlat` reorder, real view-position.
- Boot replay: staged vector layers register at ensure-engine! (after markers push).
Verify: RED→GREEN for the join test; full suite (expect prior + new deftests, 0/0); dev + sandbox builds; kondo. Commit `feat(map): real geojson overlayers and area coloring on pixi backend`.

### Task 4: verification + docs + issue bookkeeping
Update `#77` checklist (overlayers/area now real) and `#70` (close-when-merged note; MVT permanently out of scope per spec) via `gh` — comment, don't close. Full `npm run test-ci` final counts; `--build-once dev|sandbox`; grep: no `:overlayer`/`:feature-layer` notices remain on the now-real paths (movement/heatmap only). Report acceptance additions for the human: configure a real overlayer (or use sandbox demo button), check rendering/click/popup, area coloring with a feature layer, replay. Commit only if fixes needed.

## Self-Review
Spec coverage: geo/vector_layer/engine API (T1-T2), adapters + join + clicks + boot (T3), notices removed only where real (T3), zero deps (all), tests (T1, T3), sandbox visual (T2), bookkeeping (T4). Placeholders: URL construction and call shapes reference exact historical/current sources by path — transcription, not invention. Types: feature shape defined once (T1) and consumed by T2/T3; style maps shared shape; `[lon lat]` no-reversal rule stated in Global Constraints.
