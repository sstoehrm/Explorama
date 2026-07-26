# Pixi Map Stage 4: WMS/ESRI Sources, Preference Cleanup, Replay Hardening

**Date:** 2026-07-26 · **Tracking:** #72 (roadmap #77) · **Prerequisite:** stages 1-3 + #73 (PR #61)

## Scope decisions (from excavation)

- **WMS/ESRI base layers**: restore as tile sources on the engine's standard EPSG:3857 slippy grid (OL used the same grid for all types; zoom clamps stay layer-level). Zero new deps: per-tile URL construction is pure math.
  - WMS (OL parity): GetMap, version 1.3.0 (`CRS=EPSG:3857`), `LAYERS` = comma-joined `:wms-layers`, `STYLES=` (empty, mandatory param), `TILED=true`, `FORMAT=image/png`, `WIDTH=256&HEIGHT=256`, per-tile `BBOX` in 3857 meters. No `TRANSPARENT` param (OL didn't set one).
  - ESRI (OL parity with `TileArcGISRest` defaults): `<url>/export?F=image&FORMAT=PNG32&TRANSPARENT=true&SIZE=256,256&BBOXSR=3857&IMAGESR=3857&BBOX=<per-tile 3857>`. No layers param (whole service), no DPI.
  - No real wms/esri config exists in the repo — validation = exact-URL unit tests + a sandbox "Demo WMS" button against a public OSM WMS (terrestris) for the human visual check.
  - Adapter: `resolve-base-layer-desc` stops falling back for wms/esri; `switch-base-layer` handles them; the `:base-layer-type` notice is removed entirely (all four config types now real).
- **Mouse preferences — closed as already-satisfied + cleanup**: the excavation proved OL never consulted `select-event?`/`context-menu-event?` for map clicks (hardcoded: plain click = popup, ctrl+click = highlight — exactly what the Pixi adapter does today); the only live preference was `do-panning?`, already wired with identical button semantics. Stage 4: delete the two dead predicates from `map/map/config.cljs` (no other consumers — woco/mosaic own their copies), replace the adapter's "deferred, tracked as follow-up" comment with the factual statement of the hardcoded-parity semantics, and document the close-out on #72.
- **Marker shapes — closed as N/A**: OL event markers were always circles (`marker-data->circle-style` was the only style path). Note for the future: OL overlayer *point* features used a PNG icon — a separate, currently-unscoped nicety.
- **Replay hardening = targeted tests**, not rewrites:
  - `build-base-payload` (tasks.cljs) is pure over the db — test that the vis-state snapshot payload and the live `:copy-frame` payload produce the same shape/keys (the two entry paths must stay compatible).
  - The sync-replay direct `move-to` path (`event_replay.cljs` `operation-event-sync`) can carry nil center/zoom — the adapter's `valid-move-to?` guard exists; add tests covering exactly the payload shapes that path produces (nil, partial, valid).
  - Extract-and-test the popup/position payload destructuring used by `operation-event-sync` if trivially separable; otherwise cover via the guard tests and leave a named note.
- **Perf pass (world-space container transforms): explicitly deferred** until the user's real-data acceptance feedback; if no jank is reported, it never happens. Recorded on #72.

## Design

- New pure ns `map/pixi/tile_source.cljs`: `(tile->bbox-3857 {:z :x :y}) -> [minx miny maxx maxy]` meters (world half-extent 20037508.342789244; y axis: row 0 = north); `(source-url source tile) -> string` where source = `{:type :xyz|:wms|:esri :url <str> :wms-layers <str|nil>}` (`:xyz` keeps `{z}/{x}/{y}` template replacement). `tiles.cljs`'s `tile-url` call sites move to `source-url`; a plain string source is normalized to `{:type :xyz :url s}` for back-compat (engine opts, sandbox, existing tests untouched).
- Engine: `:tile-template` opt/state key renamed in meaning only — it now holds a source desc or string; `set-tile-template!` accepts both (normalizes); no API rename (avoids churn across adapters/sandbox).
- Adapter (`state_handler.cljs`): `resolve-base-layer-desc` maps config descs to source descs for all four types (`"default"`/`"tms"` → `:xyz`, `"wms"` → `:wms` + `:wms-layers`, `"esri"` → `:esri`); boot + `switch-base-layer` pass the source desc; notice + fallback removed.

## Testing

- `tile_source_test.cljs` (TDD): bbox exactness at z0 (full world extent, both corners), z1 quadrants (sign correctness / y-flip), z2 spot tile; WMS URL contains every mandatory param with exact values incl. joined layers + bbox ordering `minx,miny,maxx,maxy`; ESRI URL exact; xyz template replacement; string normalization.
- Replay tests per scope above (payload parity + move-to guard shapes).
- Suite green; 4 bundles compile; local e2e re-run (tile path changed; the map e2e spec must stay green).
