# Pixi Map Stage 2: GeoJSON Overlayers + Area Feature Layer

**Date:** 2026-07-25 · **Tracking:** #70 (roadmap #77) · **Prerequisite:** stage-1 cutover (PR #61)

## Scope decisions

- **No new npm dependencies.** Historical OL code had MVT/VectorTile imports but never used them; no config anywhere declares an MVT source. Stage 2 supports the two real source kinds: `"geojson"` (in-memory geojson store keyed by `:file-path`) and `"esri"` (ArcGIS FeatureServer query URL fetched client-side with `f=geojson`). MVT stays out of scope permanently unless a deployment ever needs it (then the per-library approval rule applies).
- The dead protocol surface `create-area-features`/`remove-area-features` stays as no-ops (nothing calls it); the real area path is `create-feature-layer`/`display-feature-layer` with layer type `:feature`.
- OL's dashed overlayer strokes become solid strokes (Pixi Graphics has no native dash; "better than before" fidelity decision applies).
- Esri area-layer pagination (OL paginated 50-value EsriJSON queries) is simplified to a single `f=geojson` query fetch in stage 2; pagination noted as debt if a deployment's data needs it.
- Backend TODO (client_api.cljc `load-layer-config` ships `[]` instead of slurping geojson files) is out of scope — the frontend consumes the geojson store as designed; wiring real file data is a backend task tracked in #77.

## Design

**New engine modules** (`plugins/frontend/de/explorama/frontend/map/pixi/`):

- `geo.cljs` (pure, TDD): GeoJSON → render-ready features and hit-testing.
  - `(parse-features geojson) -> [feature]` where feature = `{:properties {..} :kind :polygon|:line|:point :rings [[[wx wy] ...] ...]}` — geometry coordinates projected once to normalized world coords (`projection/project`, `[lon lat]` GeoJSON order handled here); Polygon = outer ring + hole rings; MultiPolygon flattens to multiple features sharing properties (or `:polygons` list — implementation's choice, documented); LineString/MultiLineString → `:line`; Point/MultiPoint → `:point`.
  - `(point-in-feature? feature wx wy) -> bool` — ray-casting incl. holes (polygons only; lines/points use distance threshold in screen space at pick time — engine concern).
  - `(feature-bbox feature)` / `(features-bbox features)` for zoom-to-layer needs.
- `vector_layer.cljs` (Pixi): draws a feature collection into one Graphics per layer, screen-space redraw on viewport change (consistent with the engine's strategy). Style = `{:stroke-width :stroke-color :stroke-alpha :fill-color :fill-alpha}` or a per-feature `(style-fn feature) -> style|nil` (nil = skip feature — matches OL area semantics where unmatched features are invisible). Polygon holes via Pixi `beginHole`/`endHole`.

**Engine API** (`engine.cljs`): `(add-vector-layer! engine id {:features .. :style .. :style-fn .. :below-markers? true})`, `(remove-vector-layer! engine id)`, `(set-vector-layer-visible! engine id bool)`, `(pick-vector-feature engine sx sy)` → `{:layer-id .. :feature ..}|nil` topmost visible hit. Vector container sits between tiles and markers. Redraw registered on `on-change!`.

**Adapters**:
- `object_manager.cljs` `create-overlayer`: resolve geometry (`"geojson"` → `(:geojson-object extra-fns)` by `:file-path`; `"esri"` → `js/fetch` on the query URL, async) and stage the layer (`:vector-layers` map in instance state: `{:features :style :visible? false}`); engine-registered on boot or immediately when booted. Async fetches route `settle` load-start/end through the engine when booted so render-done waits for them.
- `state_handler.cljs`: `display-overlayer`/`hide-overlayer`/`list-active-overlayers` become real (visible toggles + active set). `create-feature-layer`(type `:feature`)/`display-feature-layer`/`hide-feature-layer`/`remove-feature-layer`/`clear-feature-layers`/`list-active-feature-layers` become real for area layers: geometry from `(:feature-layer-config extra-fns)` static desc, per-feature style-fn joins `feature.properties` → `data-set` via `(select-keys props (keys feature-properties))` (exact OL semantics; pure helper + TDD), color/opacity from the matched entry. Movement/heatmap keep their stubs and notices.
- Click flow: in the adapter's on-pick wiring, when NO marker/cluster is hit, consult `pick-vector-feature`; overlayer hit → `(:overlayer-feature-clicked extra-fns)` with `(overlayer-id, properties-minus-geometry, [lat lon], view-pos)`; area hit → `(:area-feature-clicked extra-fns)` same shape. Existing render_helper popup builders then work unchanged.

**Interim UX change**: the `:overlayer` and `:feature-layer`(area) notices are removed (features are real now); `:movement`/`:heatmap` notices remain.

## Testing

- `geo_test.cljs` (pure): polygon/multipolygon/hole parsing, projection of known coords, point-in-polygon incl. hole exclusion, bbox.
- Data-join helper test (adapter): properties→data-set key matching per OL semantics.
- Suite green in browser bundle; dev + sandbox builds clean; sandbox gains a "Demo overlay" toolbar button adding a small in-line GeoJSON polygon layer for visual checking.

## Risks / accepted limitations

- Esri single-fetch (no pagination) — debt if large datasets appear.
- Solid strokes instead of dashed.
- Lines/points in overlayer data render with basic styles; picking radius fixed.
- Very large GeoJSON (tens of MB) untested; redraw is full-Graphics-rebuild per viewport change — optimize only if real data shows jank (roadmap #72 perf pass).
