# Pixi Map Stage 3: Heatmap + Movement Feature Layers

**Date:** 2026-07-26 · **Tracking:** #71 (roadmap #77) · **Prerequisite:** stage 2 (PR #61)

## Scope decisions

- **Zero new npm dependencies.** The heatmap is a canvas-2D density texture (radial-alpha blobs → gradient LUT colorize → Pixi texture); arrows are filled tapered quads + arrowhead triangles on Pixi Graphics.
- OL parity notes from excavation: movement had HOVER interaction only (highlight + tooltip "attr-label: value"), no click; heatmap had no interaction at all. Arrowhead sits at the TARGET (`:to`) end; line width tapers from `weight` (target end) to fixed 2 (source end); flat color `rgba(27,28,30,0.4)`, hover color `rgb(16,163,163)` — kept, since FlowLine's gradient capability was never used.
- `get-arrow-features`/`arrow-feature-ids` protocol methods were never implemented by OL nor called by any driver — they stay as no-ops (dead surface).
- Heatmap weight semantics: per-point weight from the desc's single non-`:lat`/`:lng` key when the layer config's `:extrema` is `:local`, else flat 1. The config is read at `create-heatmap-features` time (OL read it live per render; config changes re-fire the `:feature-layer` task and re-create the features, so create-time reads are equivalent in practice — documented here).
- Heatmap visual params: radius 5 px, blur 15 px, OL's default gradient ramp `#00f → #0ff → #0f0 → #ff0 → #f00`.
- Driver call sequence honored exactly (`core.cljs` `movement-feature-layer`/`heatmap-feature-layer`): first render `create-feature-layer` (layer-data with `:type :movement|:heatmap`), re-render `hide-feature-layer` + reuse; then `create-arrow-features id (:data-set layer-data)` / `create-heatmap-features id (get-in layer-data [:data-set :data])`; then `display-feature-layer`.

## Design

**New engine modules** (`map/pixi/`):
- `arrows.cljs` (pure, TDD): `(arrow-polygon [sx1 sy1] [sx2 sy2] w-target w-source) -> flat js/vector vertex list` for the tapered quad + head triangle (head at the [sx1 sy1] target end, sized from w-target, min sizes so weight-1 arrows stay visible); `(point-segment-dist-sq [px py] [ax ay] [bx by])` for hover hit-testing. Both screen-space pure math.
- `heatmap.cljs`: offscreen canvas 2D — `(render! canvas points vpt {:keys [radius blur gradient]})` draws grayscale radial blobs (alpha = weight/max-weight, weight ≥ 0 clamped) at `world->screen` positions, colorizes via a 256-entry gradient LUT applied to the alpha channel, returns the canvas. Engine wraps it in a Pixi Sprite (`Texture.from canvas` + `.update`) covering the viewport, regenerated on each viewport/data change.

**Engine API** (`engine.cljs`):
- `(add-arrow-layer! engine id {:arrows [{:id :fw [wx wy] :tw [wx wy] :weight :original :attribute}] :visible? bool})` / `remove-arrow-layer!` / `set-arrow-layer-visible!` — one Graphics per layer in the vector container (above polygon layers); redraw on change; hovered arrow drawn in hover color.
- Arrow hover: in the existing non-drag `pointermove` path, after marker hover misses, nearest visible arrow within tolerance (6 px screen, squared-distance compare) becomes `:hovered-arrow`; change fires registered `(on-hover-arrow! engine f)` callbacks with `(f {:layer-id .. :arrow ..}|nil evt)` and triggers a redraw of that layer only.
- `(add-heatmap-layer! engine id {:points [{:wx :wy :weight}] :visible?})` / `remove-heatmap-layer!` / `set-heatmap-layer-visible!` — sprite child of the vector container (below arrow layers), canvas re-rendered on viewport change when visible.

**Adapters**:
- `object_manager.cljs`: `create-feature-layer` gains `:movement`/`:heatmap` branches → stage `{:kind :movement|:heatmap}` entries in the shared `:vector-layers` registry (staged/boot-replay mechanics reused); `create-arrow-features [_ id descs]` converts descs (`:from`/`:to` `[lat lon]` → world via `projection/project`, keep `:id :weight :original :attribute`) and stages/pushes; `clear-arrow-features` empties the layer's arrows; `create-heatmap-features [_ id data]` converts `{:lat :lng <attr> v}` points to `{:wx :wy :weight}` honoring `:extrema` from `((:feature-layer-desc extra-fns) id)`; `clear-heatmap-features` empties points. Notices for `:movement`/`:heatmap` removed.
- `state_handler.cljs`: `display-feature-layer`/`hide-feature-layer`/`remove-feature-layer`/`clear-feature-layers`/`list-active-feature-layers` extended to the two new kinds (hide keeps the staged entry — reuse semantics; remove drops it). Hover tooltip: `on-hover-arrow!` callback sets the existing popup overlay state with escaped text `"<attribute-label>: <localized-or-raw original>"` at the cursor lonlat (hide on nil) — reusing `.map-popup`; marker-click popups take precedence (hover popup never overwrites an open click popup; track which kind is showing).
- Boot replay covers the new kinds.

## Testing

- `arrows_test.cljs` (pure): quad vertex geometry (symmetry, taper direction, head at target), min-width floor, point-segment distance (on-segment, endpoints, beyond ends).
- Adapter pure conversions TDD: arrow desc → world arrow; heatmap point conversion incl. `:extrema :local` vs `:global` weights and the "single non-lat/lng key" rule.
- Suite green; dev + sandbox + electron + server builds clean; sandbox gains "Demo arrows" and "Demo heatmap" buttons.

## Risks / accepted limitations

- Heatmap texture regenerates per viewport change (full canvas redraw); fine at the ~1k-point target, revisit under #72 if janky.
- Hover tooltip uses the shared popup overlay: a click-opened popup suppresses hover tooltips until closed.
- Arrows render straight (no great-circle curvature), as OL did.
- Heatmap gradient/radius/blur fixed constants (OL parity), not configurable.
