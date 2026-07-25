# Pixi Map Integration — Stage 1: Cutover (replace OpenLayers)

**Date:** 2026-07-25
**Status:** Approved design, pending implementation plan
**Prerequisite:** PixiJS map-engine prototype (branch `pixi-map-engine-prototype`, spec `2026-07-23-pixi-map-engine-prototype-design.md`) — validated: basemap, ~1k markers, pan-invariant world-space clustering with donut rings, picking/hover, Reagent popup, zoom-to-data. Suite 161/0/0.

## Decision Summary (user-confirmed)

- **Goal:** Replace OpenLayers outright. Stage 1 deletes `impl/openlayers/` and the `ol`/`ol-ext` npm dependencies; the Pixi backend takes over immediately.
- **Staging:** Full replacement is decomposed into sub-projects, each with its own spec → plan → implementation cycle:
  1. **Stage 1 (this spec):** cutover on core features; advanced features alert-stubbed.
  2. Stage 2: GeoJSON/MVT overlayers + area feature layer (polygon rendering & picking).
  3. Stage 3: heatmap + movement (flow arrows).
  4. Stage 4: WMS/ESRI base-layer sources, replay/vis-state hardening, performance pass (world-space container transforms if needed).
- **Dependency policy:** small, single-purpose npm libs are allowed, but **every individual library requires explicit user approval before it is added**. Stage 1 needs none.
- **Visual fidelity:** "better than before" — functional parity with a deliberately redesigned Pixi aesthetic (donut clusters, smoother interactions). No obligation to clone ol-ext animations/styling.
- **Interim UX for missing features:** the legend/toolbar/filter UI keeps offering heatmap/movement/area/overlayers; selecting one shows a **simple alert-style notice** ("not yet available in the new renderer"). Use the existing woco notification API if reachable in one call, else `js/alert`. Throttled per feature+frame.

## Architecture

The plugin already has a clean backend boundary (verified: zero `ol` imports outside `impl/openlayers/`):

- `map/protocol/object_manager.cljs` — `mapObjectManager` protocol (object/DOM lifecycle).
- `map/protocol/state_handler.cljs` — `mapStateHandler` protocol (view/state operations).
- `map/api.cljs` — facade; `map-type` constant + `case` dispatch in `create-object-manager`/`create-state-handler` is the designed switch point; per-frame `instances` atom.

### New namespaces (`plugins/frontend/de/explorama/frontend/map/impl/pixi/`)

| Namespace | Responsibility |
|---|---|
| `object_manager.cljs` | `deftype PixiObjectManager` implementing `mapObjectManager`. Thin adapter: marker/layer lifecycle onto the engine; feature-layer/arrow/heatmap/area/overlayer methods delegate to `stubs/notify-unavailable!`; base layers: `"default"`(XYZ)/`"tms"` real, `"wms"`/`"esri"` → notice + fall back to the default base layer. |
| `state_handler.cljs` | `deftype PixiStateHandler` implementing `mapStateHandler`: render-map, render-done listener registration, move-to/move-to-data/move-to-marker/view-position, display-markers/display-marker-cluster, update-marker-styles, highlight fns, cache-event-data, display-popup/hide-popup, switch-base-layer, resize-map, select-cluster-with-marker, destroy. |
| `instance.cljs` | Per-frame adapter state: engine ref (nil while headless), cached marker/event data, pending view operations queued while headless, config (base-layer descs, mouse-button prefs from `extra-fns`). |
| `stubs.cljs` | `notify-unavailable!` — one helper for all not-yet-implemented features; woco notification if available as a single `fi/call-api`, else `js/alert`; throttled per `[frame-id feature]`. |

The prototype's engine modules (`map/pixi/*`) remain the rendering core — standalone, sandbox-runnable, unit-tested. Adapters speak "plugin protocol" on one side and "engine API" on the other; no protocol types leak into engine namespaces.

### Engine additions (in `map/pixi/*`)

1. **Render-done signal** — engine tracks pending tile loads after each viewport/data change; when a change has occurred and pending count reaches zero, fires registered one-shot listeners. Replaces OL's `loadend`; required by popup/zoom choreography (`render_helper.cljs`) and project replay (`vis_state.cljs`).
2. **Lazy creation / headless support** — a map instance may exist with **no engine and no canvas** (headless frames, `:interaction-mode :render-db-get?` perf mode). The adapter records state (markers, viewport, base layer) and boots the engine on the first `render-map` call that finds the frame's DOM container (`config/frame-body-dom-id`). Pending view ops replay on boot.
3. **Marker-layout styling** — map the plugin's marker-layout descriptors (color rules, size) to sprite tint/scale. Stage 1 renders circles only; non-circle shapes in layouts degrade to circles (documented limitation).
4. **Highlight API** — set/clear highlighted marker ids (`:highlighted?` flag → 1.6× scale, consistent with prototype hover).
5. **`select-cluster-with-marker`** — given a marker id, locate the cluster node containing it, zoom to its members (`fit-extent`) — used by replay/highlight restore.
6. **`resize!`** — resize renderer + viewport width/height (wired to the existing debounced `resize-map` call in `views/map.cljs`).
7. **Idempotent `destroy!`** — tear down Pixi app, listeners, DOM popup mount; safe to call twice; safe on never-booted (headless) instances.
8. **Mouse-button preferences** — respect the plugin's `do-panning?`/`select-event?`/`context-menu-event?` fns (already delivered via `extra-fns`) in the engine's pointer handlers.
9. **`preserveDrawingBuffer: true`** on the Pixi Application so the woco DOM-screenshot export (`toCanvas`) captures the WebGL canvas.

### Deletions

- `plugins/frontend/de/explorama/frontend/map/impl/openlayers/**` (~1,997 LOC).
- `ol` and `ol-ext` from `bundles/{browser,electron/frontend,server}/package.json` (+ lockfiles via install).
- `de.explorama.frontend.map.impl.openlayers.util-test` file and its registration in both browser test runners (port still-relevant coordinate/util cases into pixi engine tests where they apply).
- Stale OpenLayers references in docs (`CLAUDE.md` dependency list note).

## Data Flow & Contracts

- **Call path:** task queue (`operations/tasks.cljs` `::execute`) → `map/api.cljs` → protocol deftype → `instance` state → engine atoms → Pixi. All eleven task types (`:base-layer`, `:marker`, `:cluster-switch`, `:overlayer`, `:feature-layer`, `:hide-feature-layer`, `:popup`, `:position-change`, `:filter`, `:init-di`, `:copy-frame`) must resolve without throwing — real behavior or stub-notice.
- **Synchronous mutation contract:** the task queue dispatches `::ddq/finish-task` immediately after protocol calls return. Therefore every protocol method completes its state mutation synchronously; only *visual settling* is asynchronous, exposed via the render-done listener (the two task types that wait on it today — `:popup`, `:position-change` — keep working unchanged).
- **Popup:** Reagent DOM overlay mounted in the frame body (adapted from the prototype's `popup.cljs`), positioned via `viewport/->screen`, repositioned on every engine viewport change. Content comes from the plugin's existing popup-content functions (`render_helper.cljs`); `display-popup`/`hide-popup` drive it.
- **Base-layer config:** read from the existing db config (`[:map :config :layers :base-layers]`, shared `config.cljc` defaults). `:type "default"`/`"tms"` → engine tile template; `"wms"`/`"esri"` → `notify-unavailable!` + fall back to the configured default layer (documented stage-1 degradation).

## Error Handling

- `notify-unavailable!` throttles (one notice per feature per frame per session) — no alert spam.
- `render-map` with a missing DOM container: stays headless, logs a warning, no throw.
- `destroy-instance` idempotent; instance-registry entry always removed.
- Unknown base-layer type: warn + default layer.

## Testing

- All existing `map.pixi.*` pure tests remain (161 baseline).
- New pure unit tests: marker-layout → style mapping; base-layer type selection/fallback; render-done bookkeeping (pure part: pending-counter/one-shot semantics); select-cluster-with-marker node lookup.
- Both browser test runners updated for added/removed namespaces; full `npm run test-ci` green.
- Electron frontend + server frontend compile without `ol` (shared plugin source); their test suites stay green.
- Manual acceptance (user): real app run (browser bundle dev) with imported data — markers/clusters/popup/highlight/zoom-to-data on real events; stub notices for heatmap/movement/area/overlayers; screenshot export shows the map.

## Risks & Accepted Stage-1 Limitations

| Risk / limitation | Handling |
|---|---|
| Replay/popup choreography timing | render-done signal designed to OL-`loadend` semantics; replay manually verified in acceptance |
| Non-circle marker shapes in layouts | degrade to circles; revisit in a later stage |
| WMS/ESRI base-layer deployments | degrade to default layer + notice until stage 4 |
| Heatmap/movement/area/overlayers | alert-stubbed until stages 2–3 |
| WebGL screenshot capture | `preserveDrawingBuffer: true`; verified in acceptance |
| Headless→visible transition | lazy boot + pending-op replay; covered by acceptance (minimize/restore a map frame) |
