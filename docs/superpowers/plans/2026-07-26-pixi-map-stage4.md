# Pixi Map Stage 4 Implementation Plan (WMS/ESRI, preference cleanup, replay hardening)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** WMS + ESRI base layers real on the Pixi backend; dead mouse-preference predicates removed with documented close-out; replay risk paths pinned by tests. Spec: `docs/superpowers/specs/2026-07-26-pixi-map-stage4-design.md` (binding).

## Global Constraints

- Suite baseline: **212 deftests 0/0**; new test namespaces in BOTH browser runners.
- OL-parity URL shapes are binding (spec's WMS/ESRI bullets — exact params). EPSG:3857 world half-extent constant: `20037508.342789244`. Tile row 0 is the NORTH edge (bbox `maxy` = half-extent at z0 row 0).
- No API renames in the engine (`:tile-template`/`set-tile-template!` keep their names, accept desc-or-string).
- Commits conventional, one per task, trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Foreground only; nothing to bare /tmp; NODE_COMPILE_CACHE for npm; never kill processes (repoint figwheel port if 8020 busy, revert before commit).

## Tasks

### Task 1: `tile-source` pure math + URL construction (TDD)
Create `plugins/frontend/de/explorama/frontend/map/pixi/tile_source.cljs` + test `plugins/frontend_test/de/explorama/frontend/map/pixi/tile_source_test.cljs` (+ both runners).
Produces: `(normalize source) -> {:type :xyz|:wms|:esri :url .. :wms-layers ..}` (string → `{:type :xyz :url s}`; map passed through with `:type` keywordized); `(tile->bbox-3857 {:z z :x x :y y}) -> [minx miny maxx maxy]` doubles; `(source-url source tile) -> string` per the spec's exact URL shapes (WMS param order stable/documented; bbox joined `minx,miny,maxx,maxy`; layers comma-joined from `:wms-layers` which is already a comma-string from config — pass through verbatim, do NOT split/rejoin).
Tests: z0 tile bbox = `[-HE -HE HE HE]`; z1 `{:x 0 :y 0}` = NW quadrant (`[-HE 0 0 HE]` — pins the y-flip), `{:x 1 :y 1}` = SE; a z2 spot value; WMS URL exact-string match for a fixture source+tile (every mandatory param present incl. `STYLES=` empty and `CRS=EPSG:3857`); ESRI exact-string; xyz template; normalization of string + `"wms"`-typed config-style map. RED → GREEN → kondo → commit `feat(map): wms and esri tile source url construction`.

### Task 2: engine + adapter integration + sandbox demo
Modify `map/pixi/tiles.cljs` (tile-url call sites → `tile-source/source-url`; keep `tile-url` for the xyz template case or delegate — implementer's choice, no behavior change for xyz), `map/pixi/engine.cljs` (`create!`/`set-tile-template!` normalize via `tile-source/normalize`; state stores the normalized desc), `map/map/impl/pixi/state_handler.cljs` (`resolve-base-layer-desc`: all four config types → source descs, wms carries `:wms-layers`; boot + `switch-base-layer` pass descs; DELETE the `:base-layer-type` notice + fallback branch), `map/pixi/sandbox.cljs` ("Demo WMS" toolbar button switching to `{:type :wms :url "https://ows.terrestris.de/osm/service" :wms-layers "OSM-WMS"}` and back on second click).
Careful: settle/load tracking and the errored-tile cache eviction key by URL — `source-url` output must be deterministic (stable param order) so cache keys stay consistent. Attribution div update on switch stays as-is (desc `:attribution`).
Verify: `npm run test-ci` (212 + T1 count, 0/0); `--build-once dev` + `sandbox` clean; kondo clean. Commit `feat(map): wms and esri base layers on pixi backend`.

### Task 3: preference cleanup + replay hardening tests
- Delete `select-event?` and `context-menu-event?` from `plugins/frontend/de/explorama/frontend/map/map/config.cljs` (grep first: no consumers inside the map plugin; woco/mosaic own separate copies — do NOT touch theirs). Replace the on-pick comment in `state_handler.cljs` ("deferred, tracked as follow-up") with the factual note: plain-click popup + ctrl-click highlight is OL-parity hardcoded behavior; the only live mouse preference is `do-panning?`, honored at drag start.
- Replay tests, new ns `plugins/frontend_test/de/explorama/frontend/map/operations/payload_parity_test.cljs` (+ runners):
  - `build-base-payload` (in `operations/tasks.cljs` — confirm it is pure over `[db frame-id]`; if it derefs subs, extract the pure core first, minimal refactor) fed a fixture db: assert the snapshot payload contains the exact key set `{:di :local-filter :base-layer :cluster? :marker-layouts :feature-layers :popup-desc :highlighted-markers :overlayers :view-position}` and that vis-state (snapshot) and live `:copy-frame` construction agree on shapes.
  - move-to guard shapes: drive the adapter's `valid-move-to?` (map/map/impl/pixi/instance.cljs) with exactly what `event_replay.cljs` `operation-event-sync` produces for `:position-change` and `:popup` payloads with nil/partial/valid `center`/`zoom` (read event_replay.cljs:174-192 for the real destructuring; replicate those shapes as fixtures).
- Update issue #72 via gh COMMENT: wms/esri landed; mouse-preferences closed (OL never consulted them for map clicks — do-panning? was and remains the only live pref, already wired); marker shapes closed N/A (OL was circles-only; overlayer point icons noted as separate unscoped nicety); perf pass explicitly deferred pending real-data acceptance feedback.
Verify: RED→GREEN for new tests; full test-ci; kondo. Commit `feat(map): stage-4 preference close-out and replay guard tests`.

### Task 4: verification + push-readiness
Full `npm run test-ci`; browser dev + sandbox, electron frontend, server frontend `--build-once` clean; LOCAL E2E RE-RUN (tile path changed): from repo root `cd bundles/browser && ./build.sh` then `cd ../../e2e && npm install && npm test` — all 14 specs green (esp. the map spec). Greps: no `:base-layer-type` notice remains; no `select-event?`/`context-menu-event?` in the map plugin. Comment on #77 (stage 4 complete on branch). Commit only if fixes needed.

## Self-Review
Spec coverage: WMS/ESRI (T1-T2), preference cleanup + close-out (T3), replay tests (T3), perf deferral documented (T3 issue comment), e2e re-verification (T4). Placeholders: URL shapes fully specified in the spec (no invention); event_replay/tasks referenced by exact file:line reading instructions. Types: source desc defined once (T1 normalize) and consumed by T2 engine/adapter; bbox constant stated once.
