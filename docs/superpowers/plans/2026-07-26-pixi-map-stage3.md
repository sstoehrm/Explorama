# Pixi Map Stage 3 Implementation Plan (heatmap + movement)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Real movement (tapered flow arrows with hover tooltip) and heatmap feature layers on the Pixi backend, replacing their stage-1 stubs.

**Architecture:** Pure `arrows` math + canvas-2D `heatmap` renderer → engine arrow/heatmap layer APIs + arrow hover → adapter branches for `:movement`/`:heatmap` layer-data with OL-parity driver semantics (hide = clear features + keep layer; create-*-features refills). Spec: `docs/superpowers/specs/2026-07-26-pixi-map-stage3-design.md`.

## Global Constraints

- Suite baseline at stage start: **194 deftests 0/0**. New test namespaces in BOTH browser runners.
- Coordinate orders: arrow descs carry `:from`/`:to` as `[lat lon]` (plugin boundary); heatmap points `{:lat :lng}`. Convert to world coords (`projection/project lon lat`) in the ADAPTER conversion fns; engine consumes world coords only.
- Driver sequences (from `map/core.cljs:400-424`, binding): `create-feature-layer` receives the whole backend layer-data (`{:layer-id :name :type :data-set [:config]}`); arrows arrive via `create-arrow-features id (:data-set layer-data)` (vector of descs); heatmap via `create-heatmap-features id (get-in layer-data [:data-set :data])`. `hide-feature-layer` on the reuse path must clear rendered features but KEEP the layer entry (`feature-layer-created?` stays true).
- Arrow visuals: taper weight→2 (target→source), head at target, color 0x1b1c1e alpha 0.4, hover 0x10a3a3 alpha 1.0.
- Heatmap: radius 5, blur 15, gradient `#00f #0ff #0f0 #ff0 #f00`; weight rule per spec.
- Hover tooltip text: `"<(:attribute-label extra-fns) attribute>: <(:localize-number extra-fns) original-if-number-else-str>"`, HTML-escaped.
- Commits conventional, one per task, trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Foreground only; nothing to bare /tmp; NODE_COMPILE_CACHE for npm.

## Tasks

### Task 1: `arrows` pure math (TDD)
Create `map/pixi/arrows.cljs` + `plugins/frontend_test/.../pixi/arrows_test.cljs` (+ both runners).
Produces: `(arrow-polygon [tx ty] [sx sy] w-target w-source) -> [x0 y0 x1 y1 ...]` — vertices of ONE filled polygon: tapered body quad from source (half-width `(max 1 (/ w-source 2))`) to the head base, plus arrowhead triangle at the target end (head length `(max 6 (* 1.8 w-target))`, head half-width `(max 3 w-target)`); degenerate zero-length segments return nil. `(point-segment-dist-sq [px py] [ax ay] [bx by]) -> d²` (project-clamp-t formula; endpoint cases when t≤0/t≥1).
Tests: horizontal arrow (assert head vertices at target side, taper widths at correct ends via y-extents at the two x-ends, polygon symmetric about the axis), vertical arrow, zero-length → nil, dist-sq on-segment/at-endpoints/beyond-ends exact values.
RED → GREEN → kondo → commit `feat(map): arrow geometry and segment distance math`.

### Task 2: heatmap renderer + engine arrow/heatmap layers + hover + sandbox demos
Create `map/pixi/heatmap.cljs`; modify `engine.cljs`, `vector_layer.cljs` only if needed (arrows draw in their own Graphics via `arrows/arrow-polygon` + `.drawPolygon`), `sandbox.cljs`.
- `heatmap/render!` per spec: offscreen canvas sized to viewport (device-pixel 1:1), grayscale pass (radial gradient per point, `globalAlpha` = normalized weight, radius+blur via a pre-rendered blob canvas stamped per point — the simpleheat technique), then colorize: `getImageData`, LUT from a 1×256 gradient canvas, write back. Points given in world coords; screen via `viewport/world->screen`; skip points > radius+blur off-viewport.
- Engine: `add-arrow-layer!`/`remove-arrow-layer!`/`set-arrow-layer-visible!` (registry `:arrow-layers` in state; Graphics children of vector-container added after polygon layers), redraw in the node on-change callback (visible layers; hovered arrow in hover color, drawn last); `add-heatmap-layer!`/`remove-heatmap-layer!`/`set-heatmap-layer-visible!` (`:heatmap-layers`; one Sprite per layer at container bottom via `addChildAt 0`; canvas + `Texture.from canvas`; on redraw call `heatmap/render!` then `(.update (.-baseTexture texture))`; sprite hidden ↔ `:visible?`).
- Hover: in the non-drag pointermove branch, after the existing marker-hover logic and only when no marker is hovered: nearest arrow across visible arrow layers within 36 px² (6 px) using `arrows/point-segment-dist-sq` on screen-projected endpoints; state `:hovered-arrow [layer-id arrow-id]`; change → `(doseq [f @(:hover-arrow-callbacks engine)] (f hit evt))` with hit `{:layer-id .. :arrow ..}|nil` + notify-light (redraw arrow layers only — reuse full notify; acceptable). `(on-hover-arrow! engine f)` registers callbacks. Cursor pointer handling optional.
- Sandbox: "Demo arrows" button (a handful of hardcoded arrows across Germany, weights 2/8/16) and "Demo heatmap" button (~200 random weighted points), both add-then-toggle.
Verify: test-ci no regressions (state count), dev+sandbox build-once clean, kondo clean. Commit `feat(map): pixi arrow and heatmap layer rendering with arrow hover`.

### Task 3: adapter wiring (TDD for conversions)
Modify `map/map/impl/pixi/{object_manager,state_handler,instance}.cljs`; create `.../impl/pixi/movement_heatmap_test.cljs` (+ runners).
- `instance.cljs` pure fns (TDD): `(arrow-desc->world {:keys [id from to weight original attribute]})` → `{:id :fw [wx wy] :tw [wx wy] :weight :original :attribute}` (from/to are `[lat lon]`! project with lon first; nil on missing endpoint); `(heatmap-point->world desc extrema)` → `{:wx :wy :weight}` with weight = the single non-`:lat`/`:lng` key's numeric value when `extrema = :local` (fallback 1 when missing/non-numeric), else 1; `(heatmap-extrema feature-layer-desc)` → `:local|:global` (default `:global`).
- Registry: stage `{:kind :movement :arrows [] :visible? false}` / `{:kind :heatmap :points [] :visible? false}` entries; staged→engine registration on boot extended (arrow + heatmap layers after vector layers); create-*-features convert + stage + push-to-engine when booted; clear-* empty the data (keep entry).
- `object_manager.cljs`: `create-feature-layer` branches `:movement`/`:heatmap` (stage entry; NO notice); `create-arrow-features`/`clear-arrow-features`/`create-heatmap-features`/`clear-heatmap-features` real per above; `feature-layer-created?`/`get-feature-layer-obj` extended to the new kinds; `get-arrow-features`/`arrow-feature-ids` stay no-ops.
- `state_handler.cljs`: display/hide/remove/clear/list-active extended to `:movement`/`:heatmap` kinds (hide = clear rendered data via engine but KEEP entry + created?; remove = drop entry + engine layer). Hover tooltip: in ensure-engine!, `(engine/on-hover-arrow! ...)` → popup-state `{:lat :lon :html}` at the cursor's lonlat with escaped `"label: value"` text (use `(:attribute-label extra-fns)` + `(:localize-number extra-fns)` for numbers; goog.string/htmlEscape); nil → clear ONLY if the popup was hover-originated (track `:popup-source :hover|:click` alongside popup-state; click popups from display-popup set `:click` and are never cleared by hover-nil).
- Remove `:movement`/`:heatmap` notices from stubs usage; `display-feature-layer` fallback notice stays only for genuinely unknown kinds.
Verify: RED→GREEN; full test-ci (state count); dev+sandbox builds; kondo. Commit `feat(map): real movement arrows and heatmap on pixi backend`.

### Task 4: verification + bookkeeping
Full test-ci; browser dev + sandbox + electron frontend + server frontend build-once all clean; seam greps (no `:movement`/`:heatmap` notices remain; hide-vs-remove semantics vs core.cljs reuse path — trace `movement-feature-layer` twice-render flow). Comment on #71 and #77 (progress + acceptance additions: sandbox demo buttons; real check needs a movement/heatmap layer configured in the overlayer designer). Commit only if fixes needed.

## Self-Review
Spec coverage: arrows math (T1), heatmap+engine+hover (T2), adapters+conversions+tooltip+notices (T3), verification (T4). Placeholders: none — shapes from the excavation are embedded verbatim (arrow-desc keys, heatmap point rule, driver sequences with file:line). Type consistency: world-coord arrow/point shapes defined in T3 conversions match T2 engine consumption (`:fw`/`:tw`/`:weight`; `:wx`/`:wy`/`:weight`); style constants stated once in Global Constraints.
