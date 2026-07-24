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
