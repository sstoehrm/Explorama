# Custom PixiJS Map Engine — Prototype Design

**Date:** 2026-07-23
**Status:** Approved (design); prototype not yet implemented
**Topic:** Replace the OpenLayers-based map rendering with an in-house PixiJS engine, starting with a de-risking prototype.

## Context & Motivation

The `map` plugin currently renders on **OpenLayers 7.5.2 + ol-ext 4.0.37**. All OL code is
isolated under `plugins/frontend/de/explorama/frontend/map/map/impl/openlayers/`
(~2,000 lines) behind two protocols (`mapObjectManager`, `mapStateHandler` in
`map/protocol/`), fronted by the `map/api.cljs` façade, which selects the implementation
with a single switch: `(def map-type :openlayers)`.

We evaluated three directions:

1. **deck.gl** — GPU-accelerated, but adds a heavy new engine (deck.gl + luma.gl, ~1 MB+)
   and a declarative paradigm that fights the plugin's imperative task-queue orchestration;
   still needs a basemap and still requires rebuilding the ol-ext features by hand.
2. **MapLibre GL** (basemap) — solid modern map lib, but reintroduces a map dependency and,
   if paired with a Pixi data overlay, adds two-canvas camera-sync friction.
3. **Full custom PixiJS engine (chosen).** PixiJS 7.4.2 is **already a first-class
   dependency in all three bundles** (browser, server, electron) and already has a mature
   in-house codebase (`mosaic/render/pixi/{core,shapes,lod}.cljs`, the woco workspace
   background, and the minimap renderer). Building our own map engine on Pixi
   **consolidates onto one rendering engine** rather than maintaining Pixi + OpenLayers,
   gives full control/customization, and adds no new dependency.

**Decision:** build Explorama's own map rendering engine on PixiJS, owning the full
substrate (tiles + projection + camera) as well as data rendering. Drop OpenLayers.

### Reframed goals (post-discussion)

- **Perf is not the primary driver.** ~100k single events render but are not *usable*;
  realistic usable datasets are ~1k markers, which Pixi handles trivially. The genuine
  justification is **integration/customization + engine consolidation**, not raw throughput.
- **Clustering is a required feature, not a perf crutch.** It is needed for *legibility*
  regardless of framerate (overlapping events are unreadable). The existing config
  `explorama-map-marker-cluster-threshold` (default 1000) already treats ~1k as the
  clustering boundary.
- **Visual fidelity is relaxed.** The prototype need not reproduce today's exact look
  (donut-chart clusters, spiral spread-on-click, convex-hull hover). **Feature parity of
  behavior** is the goal; simpler visual equivalents are acceptable.
- **Minimize dependencies.** Popups/UI chrome are plain re-frame/DOM. No new npm deps in
  the prototype.

## Scope

This spec covers a **de-risking prototype only**. Its job is to kill the two real unknowns
cheaply:

1. **The substrate** — does an in-house tile/projection/camera feel as good as OL?
2. **The clustering/legibility path** — can we render + cluster ~1k markers cleanly with
   our own code and zero new deps?

Marker *throughput* is explicitly **not** a risk to prove (mosaic already demonstrates
Pixi rendering many objects).

### In scope (the vertical slice the prototype must demonstrate)

- WMTS raster basemap (RESTful `z/x/y` URL template).
- Web Mercator projection (lon/lat ↔ world ↔ screen).
- Smooth camera: pan, wheel-zoom, pinch-zoom, animated `move-to` / `fit-extent`.
- ~1k markers from lat/lon: batched sprites, color/radius/opacity styling, highlight state.
- Grid-bin clustering (zero-dep) with count labels and click-to-zoom/expand.
- Picking: hover/click hit-test → marker or cluster.
- Popup: plain re-frame DOM overlay positioned via projection, reusing existing popup content.
- Zoom-to-data (extent fit).

### Out of scope (deferred to post-prototype)

- Feature layers: heatmap, movement/flow arrows, area/choropleth coloring.
- GeoJSON + ESRI vector overlays.
- WMS and ArcGIS tile sources (same tile machinery as WMTS/XYZ; different URL templates).
- `supercluster`-quality clustering (quality upgrade over grid-bin).
- The search bounding-box draw widget (`search/.../location.cljs`).
- Project save / event replay wiring, and the plugin task-queue integration.
- ol-ext visual flourishes: donut-chart clusters, spiral spread, convex-hull hover, FlowLine arrows.

## Architecture

A new in-house engine — working name **`expmap`** — written in plain ClojureScript over
PixiJS, with **no map-lib dependency**. Each module has a single responsibility and is
independently testable. The engine is written **protocol-agnostic** so it can later be
wrapped behind the existing `mapObjectManager` / `mapStateHandler` protocols without a
rewrite.

| Module | Responsibility |
|---|---|
| `projection` | Pure Web Mercator math: lon/lat ↔ world-pixels ↔ screen. |
| `viewport` (camera) | center + fractional zoom + size → transform; pan/wheel/pinch input; animated `move-to` / `fit-extent`. |
| `tile-layer` | visible-tile calculation, WMTS fetch via URL template, LRU tile cache, Pixi sprites, dateline wrap, retina/`devicePixelRatio`. |
| `marker-layer` | batched Pixi sprites from lat/lon; shared circle texture; color/radius/opacity; highlight. |
| `cluster-layer` | grid-bin markers → cluster bubbles + count labels; click-to-zoom/expand. |
| `picking` | spatial hit-test of pointer → marker/cluster (hover + click). |
| `popup` | plain re-frame DOM overlay positioned via `projection`; reuses existing popup content. |

### Core rendering strategy

Markers and tiles are placed **once** at world-Mercator coordinates inside a Pixi
container. Pan/zoom then only sets `container.position` / `container.scale` instead of
reprojecting every object each frame. This is the idiomatic Pixi approach, is what buys
smooth 60fps interaction, and matches the pattern already used in `mosaic/render/pixi`.

### Coordinate & data conventions

- Public API accepts `[lat lon]` (consistent with the current plugin's `extra-fns`
  callbacks), converted to world-Mercator internally.
- Base tile pyramid uses the Web-Mercator quadtree / GoogleMapsCompatible tile matrix, so a
  WMTS RESTful template is structurally identical to the existing XYZ `{z}/{x}/{y}.png`
  templates in `shared/map/config.cljc`.

## Build & Integration Plan

- **Phase A — the prototype (this spec).** A standalone **figwheel dev page** that renders
  the engine directly: WMTS basemap + ~1k markers + pan/zoom/pinch + hover/click popup +
  grid clustering + zoom-to-data. Isolated from the plugin's task-queue so we iterate on the
  risky substrate quickly. This is the deliverable.
- **Phase B — deferred (not in prototype scope).** Wrap the same engine modules as
  `impl/pixi/{object_manager,state_handler}` satisfying the existing two protocols, and add
  a `:pixi` case in `map/api.cljs`. Because the engine is protocol-agnostic, this is a thin
  adapter, not a rewrite.

## Testing

- **`projection`** — pure functions; unit tests for known lon/lat ↔ world ↔ screen
  reference points (equator, prime meridian, a mid-latitude city, dateline edges), round-trip
  invariants, and zoom-level scaling. Frontend test suite (`clj -M:test` in the bundle;
  parity with existing `plugins/frontend_test/.../map/impl/openlayers/util_test.cljs`).
- **`viewport`** — unit tests for pan/zoom math and `fit-extent` producing the expected
  center/zoom for a given bounding box.
- **`tile-layer`** — unit tests for visible-tile enumeration at a given viewport and for
  WMTS URL templating; tile fetch/cache exercised manually in the sandbox.
- **`cluster-layer` / `picking`** — unit tests for grid binning and hit-test resolution.
- **Camera *feel*, tile loading, and interaction smoothness** — validated **manually** in
  the sandbox page (not automatable); this is the core acceptance signal for the substrate.

## Success Criteria (prototype acceptance)

The prototype is successful if, in the sandbox page:

1. A WMTS basemap renders and pans/zooms (wheel + pinch) **smoothly** (subjectively on par
   with OpenLayers).
2. ~1k markers render from lat/lon, styled, with a working highlight/selection state.
3. Grid clustering shows cluster bubbles with counts; clicking a cluster zooms/expands.
4. Hover/click on a marker or cluster resolves correctly and opens a re-frame DOM popup
   positioned over the map.
5. Zoom-to-data fits all markers in view.
6. **No new npm dependencies** were added.

## Risks & Open Questions

- **Camera feel** is the main subjective risk — inertia, animated zoom, and pinch must feel
  as good as OL. Mitigated by isolating it in the sandbox and iterating.
- **Tile correctness** (dateline wrap, retina, cache eviction) is the main correctness risk;
  it is the "boring but mandatory" substrate cost we are choosing to own.
- **Grid-bin clustering** is a deliberate simplification; if visual quality is inadequate,
  the noted upgrade is `supercluster` (~13 KB) in Phase B — a swap behind `cluster-layer`,
  not a redesign.
- **Where the engine namespaces live** (`map.pixi.*` vs. a shared `de.explorama.frontend`
  location reusable by mosaic) is an implementation-plan detail, deferred to writing-plans.
