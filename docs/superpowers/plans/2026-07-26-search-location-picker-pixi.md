# Search Location-Picker Pixi Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Replace the stage-1 stub in `plugins/frontend/de/explorama/frontend/search/views/components/location.cljs` with a working region picker on the Pixi engine, restoring `:location` search rows. Spec: `docs/superpowers/specs/2026-07-26-search-location-picker-pixi-design.md` (binding contract inside).

## Global Constraints

- Suite baseline: **208 deftests 0/0**; new test ns in BOTH browser runners.
- Value contract is untouchable: `[min-lat min-lng max-lat max-lng]` into formdata `:ui-selection` + `:values`, 0-arg `on-change` — byte-compatible with the historical widget (`git show aab222a:plugins/frontend/de/explorama/frontend/search/views/components/location.cljs` is the reference; read it before coding the component).
- Reuse the historical file's Tailwind class defs and i18n keys verbatim; keep the bare `map-input`/`hint-text` class literals.
- Cross-plugin require `de.explorama.frontend.map.pixi.{engine,viewport,projection}` is sanctioned (engine is plugin-agnostic); do NOT require anything under `de.explorama.frontend.map.map.*` (adapter/protocol layer stays map-plugin-private).
- Commits conventional, one per task, trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Foreground only; nothing to bare /tmp; NODE_COMPILE_CACHE for npm; never kill processes (repoint figwheel port if 8020 busy, revert before commit).

## Tasks

### Task 1: `location-region` pure helpers (TDD)
Create `plugins/frontend/de/explorama/frontend/search/views/components/location_region.cljs` + test `plugins/frontend_test/de/explorama/frontend/search/location_region_test.cljs` (+ both runners).
Produces (consumed by Task 2):
- `(corners->values [lat1 lng1] [lat2 lng2]) -> [min-lat min-lng max-lat max-lng]` (numeric sort per axis).
- `(values->feature [min-lat min-lng max-lat max-lng]) -> {:kind :polygon :properties {} :rings [ring]}` — ring = the 4 corners projected to world coords via `de.explorama.frontend.map.pixi.projection/project` (lon-first!), closed (first point repeated last), counter-order irrelevant to the renderer.
- `(values->extent v) -> [min-lon min-lat max-lon max-lat]` (note the axis swap: values are lat-first, extent is lon-first for `viewport/fit-extent`).
Tests: all 4 corner orderings sort identically; feature ring closed, 5 points, spot-check one projected coordinate against `(projection/project lng lat)`; extent ordering; degenerate equal corners give zero-area values without throwing.
RED → GREEN → kondo → commit `feat(search): region helpers for pixi location picker`.

### Task 2: the widget
Rewrite `plugins/frontend/de/explorama/frontend/search/views/components/location.cljs` per the spec's Design section. Read FIRST: the historical widget (git show, reference for states/dispatches/classes/i18n/dom-id), the current stub, `search_selection_component.cljs:180-200` (props), `formdata.cljs` `add-data-for-attr`, engine API (`map/pixi/engine.cljs`: create! opts, on-pick `(f node evt)`, add-vector-layer!/set-vector-layer-visible!, set-viewport!, resize!, destroy!, get-viewport; `viewport/->lonlat`, `viewport/fit-extent`), search `config.cljs:34` geo-config.
Component structure mirrors the historical one: outer `location-input` with `alter-state`/`rect-state`/`internal-state` atoms; a `create-class` react wrapper keyed by `(str path frame-id "-loc")` that mounts the canvas + boots the engine once; collapsed and expanded branches share the wrapper (style-only size change + `engine/resize!` on the next animation frame after each toggle). Drawing: `on-pick` for the two corner clicks (ignore picked node; `viewport/->lonlat` on the event's canvas coords), own `pointermove` listener for the preview, region vector layer updated via `add-vector-layer!` (idempotent re-add replaces features). Apply/Cancel/Reset dispatch/revert per the binding contract. Unmount: remove own listeners + `engine/destroy!`.
Verify: `npm run test-ci` (208 + Task-1 count, 0/0); `clojure -M -m figwheel.main --build-once dev` clean (search plugin compiles against the engine); `--build-once sandbox` clean; kondo clean. Manual interaction check deferred to the human.
Commit `feat(search): pixi-based location region picker`.

### Task 3: verification + bookkeeping
Electron frontend + server frontend `--build-once dev` clean (search is shared plugin code). Grep: stub text gone; no `de.explorama.frontend.map.map.` requires from search. Comment on #73 (implementation landed on PR #61 branch; acceptance: draw/apply/reopen/reset/cancel + search actually filters by region; workspace-zoom note) and #77 (progress). Commit only if fixes needed.

## Self-Review
Contract coverage: value 4-tuple + dispatch targets (T2, from spec binding), states/classes/i18n (T2 via historical reference), redraw+fit on reopen (T2), tiles from geo-config (T2), pure math tested (T1), cross-bundle (T3). No placeholders: historical file referenced by exact git-show path as the transcription source; engine API names are in-tree. Types: `values` 4-tuple defined once; feature shape matches the engine's vector-layer contract (`:kind :polygon :rings`); extent lon-first for fit-extent flagged at both definition and use.
