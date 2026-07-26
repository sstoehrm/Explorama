# Search Location-Picker on the Pixi Engine

**Date:** 2026-07-26 · **Tracking:** #73 (roadmap #77) · **Prerequisite:** stages 1-3 (PR #61)

## Contract (from the pre-cutover OL widget, binding)

- Component `location-input {:keys [path frame-id on-change extra-style child]}` (other call-site props ignored, as historically).
- Value shape: flat `[min-lat min-lng max-lat max-lng]`, EPSG:4326 degrees — dispatched on Apply to `[:de.explorama.frontend.search.views.formdata/add-data-for-attr path :ui-selection v]` AND `... :values v`, then the 0-arg `on-change` call. Reset dispatches both to nil + local clear. Cancel reverts local state to the stored `:ui-selection` + `on-change`. Backend consumes the vector verbatim (`:in-geo-rect` bbox test) — no shape change permitted.
- UI states as historical: collapsed 244×50 clickable thumbnail (live mini-map + translucent `:search-location-select` hint) ↔ expanded overlay (`:style extra-style`) with full-size map + action bar (draw-toggle, reset | Apply [disabled unless a region is staged], Cancel) + `:search-location-hint` bubble while drawing. Reuse the historical Tailwind class defs and i18n keys verbatim (all still present; keep the bare `map-input`/`hint-text` literals — Windows High-Contrast CSS targets them).
- Drawing: two-click axis-aligned rectangle (click = corner A, live preview follows the cursor, click = corner B). New draw replaces the old. A stored region is redrawn AND view-fitted on mount/reopen.
- Tiles: search's own `geo-config` (`config.cljs:34`, `EXPLORAMA_SUCHE_GEO_CONFIG` → `{:source {:url <xyz-template>} :maxZoom}`), independent of the map plugin's config — unchanged.
- One instance per search frame; DOM id keyed `(str path frame-id "-loc")` as historically.

## Design

The widget uses the map plugin's engine directly (`de.explorama.frontend.map.pixi.{engine,viewport,projection}`) — the engine is plugin-agnostic by design; this is the first cross-plugin consumer and stays read-only on map-plugin state (no adapter/protocol involvement).

- **Engine per widget**: `engine/create!` on a canvas inside the widget container; `:tile-template` from geo-config `:source :url`, `:max-zoom (or maxZoom 18)`, `:preserve-drawing-buffer? false`; `:do-panning? (fn [_] (not @drawing?))` so draw mode freezes panning (engine passes the raw event; unlike the map adapter no button translation is wanted — any-button drag pans, as OL default did).
- **Pure helpers** (`location_region.cljs`, search plugin, TDD): `(corners->values [lat1 lng1] [lat2 lng2])` → sorted 4-tuple; `(values->feature v)` → engine vector-layer feature `{:kind :polygon :properties {} :rings [<projected world ring, closed>]}`; `(values->extent v)` → `[min-lon min-lat max-lon max-lat]` for `viewport/fit-extent`.
- **Rectangle rendering**: one engine vector layer (`add-vector-layer!` id `"region"`, visible) holding the single feature; preview during drawing = same layer updated on the widget's own canvas `pointermove` listener (corner A + cursor lonlat via `viewport/->lonlat`); second click finalizes → staged value (Apply enables). Style: stroke 2 / 0x1f77b4 / 0.9, fill 0x1f77b4 / 0.15.
- **Clicks**: `engine/on-pick` fires on every non-drag click with `(node evt)`; the widget ignores `node` and uses the event position when `@drawing?`.
- **Resize**: after collapse↔expand toggles (and on mount), call `engine/resize!` on the next animation frame (host size changed; engine measures the host element — stage-1 fix).
- **Lifecycle**: create engine on first mount of the react wrapper; `engine/destroy!` + listener removal on unmount; stored region redrawn + `fit-extent` (with the engine's set-viewport!) at boot and on Cancel-revert.

## Out of scope / accepted

- No workspace-zoom pointer compensation in stage one of this port (the old OL widget's workaround namespace is gone; if coordinates drift under workspace zoom, wire `fi/call-api :workspace-scale-sub` like `map/map/core.cljs:207` does — tracked note, not blocking).
- Required-attributes gating (`:location` needs year/datasource/country in the form) is existing behavior outside this widget — untouched.

## Testing

- `location_region_test.cljs` (TDD): corner sorting (all 4 orderings), values→feature ring closure + projection correctness (spot lon/lat), extent order for fit-extent, degenerate equal-corner handling (zero-area rect allowed — backend test tolerates it).
- Suite green; browser dev build clean; visual/interaction check by the human (draw, redraw, apply persists into search, reopen shows region, reset clears, cancel reverts).
