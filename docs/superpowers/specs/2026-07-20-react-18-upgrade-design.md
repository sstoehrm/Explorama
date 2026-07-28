# React 18 Upgrade — Design

Date: 2026-07-20
Status: approved (legacy-render mode chosen over createRoot)

## Goal

Upgrade React 17.0.2 → 18.3.1 across the three React-bearing bundles
(browser, electron frontend, server), lifting reagent and re-frame to their
React-18-capable versions. Mounting stays on the legacy `reagent.dom/render`
API, so React 18 runs the tree in legacy mode with React-17 semantics — no
behavioral change expected anywhere in the app.

## Version changes

| Dependency | From | To | Where |
| --- | --- | --- | --- |
| `react`, `react-dom` | 17.0.2 | 18.3.1 | `bundles/browser/package.json`, `bundles/electron/frontend/package.json`, `bundles/server/package.json` |
| `reagent/reagent` | 1.0.0 | newest 1.x (≥ 1.2.0, first release with official React 18 support; exact version confirmed against current docs at implementation time) | `bundles/browser/deps.edn`, `bundles/electron/backend/deps.edn`, `bundles/electron/frontend/deps.edn`, `bundles/server/cljs.deps.edn` |
| `re-frame/re-frame` | 1.2.0 | newest 1.4.x | same 4 files |

The reagent coordinate keeps its `:exclusions [cljsjs/react cljsjs/react-dom]`
(npm supplies React). `day8.re-frame/http-fx` 0.2.4 and `re-frame-utils` 0.1.0
stay put unless dependency resolution forces a bump.

## Code changes

None planned:

- The single mount point (`plugins/frontend/de/explorama/frontend/woco/core.cljs`,
  two `dom/render` calls) keeps the legacy API. React 18 logs a one-time
  ReactDOM.render deprecation warning — accepted.
- `reagent.dom/dom-node` (used in ~6 components: ui_base select/icon-select/
  context-menu, map, reporting context-menu, woco collision) is deprecated but
  functional on reagent 1.2+/React 18. It is a React 19 blocker, out of scope
  here.
- `reagent.dom.server/render-to-string` (reporting screenshot/png.cljs)
  remains available.

## Satellite npm libraries

`react-beautiful-dnd` 13.1.1, `react-d3-cloud` 1.0.6, `react-number-format`
5.4.2, `react-toastify` 9.1.3, `react-tooltip-lite` 1.12.0,
`react-virtualized` 9.22.6, `react-window` 1.8.10 are NOT proactively bumped.

If `npm install` fails peer resolution on the older ones, the first remedy is
a bundle-local `.npmrc` with `legacy-peer-deps=true` (documents the stale peer
ranges without churning library versions). A library version bump happens only
if a test suite actually fails against React 18.

## Verification

Success = all suites green with no new React *errors* (new deprecation
warnings are expected and accepted):

1. `bundles/browser`: `npm run test-ci` (140/0/0 baseline)
2. `bundles/electron`: `make test` — backend 112/0/0 (its deps.edn changes
   too) and frontend 71/0/0
3. `bundles/server`: `clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci`
   (71/0/0 baseline)
4. Smoke test of a real render: `make build-linux` in `bundles/electron`
   (xvfb boot verification)

The visual parity harnesses were removed after the Tailwind migration, so
there is no automated pixel-level check; any styling/behavior spot checks are
manual.

## Risks

- Old satellite libs may log UNSAFE_ lifecycle warnings under React 18 —
  cosmetic, not gating.
- Automatic batching does NOT apply to legacy-mode roots, so re-frame
  dispatch/render timing is unchanged — this is precisely why legacy mode was
  chosen.
- A later createRoot migration (and React 19) remains possible; its known
  prerequisites are replacing `dom-node`/findDOMNode usage and re-verifying
  the drag-and-drop and virtualization libraries.

## Rollout

Branch `react-18` off `main`; spec, plan, and implementation commits land
there; merge to `main` after all suites pass.
