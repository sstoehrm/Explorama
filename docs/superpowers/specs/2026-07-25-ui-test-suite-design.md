# UI Test Suite Design

Date: 2026-07-25

## Goal

An end-to-end UI test suite that validates Explorama's user-facing interactions,
gating every pull request in CI. Bundle priority: browser and server first,
electron last.

## Feasibility: established, not assumed

Every load-bearing claim below was verified against a running application
before this design was written.

**Browser bundle.** The production bundle was built (`bb gather-assets.bb.clj dev`
plus `clojure -M:prod`), served statically, and driven headless through a
complete analysis workflow: create a search frame, select the Netflix
datasource, wait for the backend's result-set estimate ("Moderate result set
(323 Events)"), run the search, then drag the search frame onto Table, Mosaic
and Map frames. All three rendered real data.

**Server bundle.** `docker-compose.yml` + `docker-compose.full.yml` built and
came up cleanly. Playwright followed the redirect chain to Casdoor, signed in as
`dev`/`dev123`, and landed on a booted Explorama exposing the same `#tool-*`
ids as the browser bundle. `storageState` reuse works: a fresh browser context
boots straight into the app with no identity-provider round trip.

**ClojureScript authoring.** A cljs spec namespace compiled to Node and executed
by `@playwright/test` runs green with parallel workers, retries, traces and
JUnit output. Exit code is 1 on failure and `report.xml` records `failures="1"`.

**Canvas determinism.** Two independent runs produced byte-identical Mosaic
screenshots (`sha256 e239354f…`) on the same machine.

## Language and runner

Specs are ClojureScript. `@playwright/test` is the runner.

`@playwright/test` resolves fixtures by calling `fn.toString()` and
regex-requiring the first parameter to be an object destructuring pattern
(`innerFixtureParameterNames` in `playwright/lib/common/index.js`).
ClojureScript emits `function(p__123)`, so cljs functions cannot be handed to
`test()` directly. A fixed JS bridge owns that signature and delegates:

```js
const { test, expect } = require('@playwright/test');
globalThis.self = globalThis;          // Closure bootstrap reads `self`
require('./out-test/specs.js');

for (const spec of global.explorama_e2e.specs) {
  test(spec.name, async ({ page }) => {
    await spec.run(page, expect);
  });
}
```

The bridge is written once and does not grow as specs are added. `expect` is
passed through to cljs, so specs use auto-retrying web-first assertions rather
than fixed sleeps.

Build settings that matter:

- `:target :nodejs`, `:optimizations :simple`
- `(def pw (js/require "playwright"))` — the string require form
  `(:require ["playwright" :as pw])` fails to resolve under `:target :nodejs`
- `:source-map` plus `NODE_OPTIONS=--enable-source-maps`, without which stack
  traces point into compiled JS instead of `.cljs` line numbers

Rejected alternatives: a hand-rolled `cljs.test` runner (loses parallelism,
retries, traces, and requires reimplementing web-first assertions); Playwright's
Java bindings (Clojure rather than ClojureScript, and a second toolchain for no
remaining benefit).

## Layout

One top-level workspace, with each bundle as a Playwright project. The frontend
under test is identical across bundles — the same `plugins/frontend/` code,
confirmed by identical `#tool-*` ids and `woco_frame-<plugin>-<uuid>` frame ids
in both — so specs are shared and only bring-up differs.

```
e2e/
  deps.edn                     # :build-specs alias
  package.json
  playwright.config.js         # projects: browser-bundle, server-bundle
  explorama.spec.js            # the JS bridge
  test-opts.edn
  src/e2e/
    registry.cljs              # defspec macro + global registration
    pages/
      workspace.cljs           # boot, overlay dismissal, frame create/move/connect/zoom
      search.cljs              # datasource combobox, traffic light, run search
      table.cljs mosaic.cljs map.cljs charts.cljs
      import.cljs              # CSV upload and schema mapping
    fixtures/
      browser.cljs
      server.cljs
    specs/
      core_data_journey.cljs
      workspace_mechanics.cljs
      data_import.cljs
      server_auth.cljs         # server project only
```

A `defspec` macro keeps specs declarative while the bridge stays fixed:

```clojure
(defspec "search connects to a table and renders rows"
  [page expect]
  (p/do
    (search/run page {:datasource "Netflix"})
    (workspace/connect page :search :table)
    (-> (expect (table/frame page)) (.toContainText "323 Events"))))
```

## Bundle bring-up

**Browser.** `playwright.config.js`'s `webServer` builds and serves `dist/`,
waits for the port, and tears down after. No seeding: the bundle compiles the
netflix and roadmap fixtures in via `data_loader.cljs`.

**Server.** `globalSetup` brings up the full compose stack, polls `GET /`
through Caddy until ready, signs in once, writes `storageState` to disk, and
seeds data. Readiness polls `GET /` because `/probe/liveness` and
`/probe/readiness` are commented out in `handler.clj`.

**Clean workspace.** The welcome overlay and the `.window-handling-tour` hint
both intercept pointer events and must be cleared before interaction. Dismiss
the tour with its `Close` button — its `next` button advances a carousel
instead. Pre-seeding the `localStorage` preferences read by
`woco/preferences/client.cljs` via `addInitScript` would skip these entirely and
is faster, but the preference key is unverified; clicking is the baseline and
the localStorage path is an optimization to confirm during implementation.

## Data seeding

The server bundle's `load-data` is a no-op, so it starts with no datasources.
The expdb import API is websocket-only, and the REST route that would serve
seeding is present but disabled:

```clojure
#_["/datasources" {:post (fn [req] ... (import/transform->import body options bucket) ...)}]
```

This route is re-enabled behind `EXPLORAMA_ENABLE_IMPORT_API`, which only the
test compose profile sets. With the flag unset the route is not registered, so
the production surface is unchanged.

`transform->import` is the product's real ingest function — the same call the
browser and electron `data_loader` namespaces make, and where the UI's import
flow terminates. Seeding therefore exercises real validation and indexing, but
enters below CSV parsing, schema mapping and the transaction wrapper:

```
UI:   upload-file → csv-parse → schema/explain → mapping → generator/finalize
                  → begin-transaction → transform->import → commit-import
POST /datasources:                        transform->import
```

That is intentional. Seeding stays fast and deterministic, and the skipped
pipeline is covered by `data_import.cljs`, which drives the real CSV upload
through the UI.

The seed payload comes from `assets/data/dummy_data_netflix.cljc`, the same
fixture the browser bundle compiles in. The server bundle already converts it
to EDN via `bundles/server/convert-big-defs.bb.clj`, so seeding reuses existing
machinery rather than introducing a parallel fixture. Both bundles therefore
test against identical data, and any browser-vs-server discrepancy indicates a
real defect rather than a fixture difference.

## Assertions

Three tiers, chosen per view.

**DOM and text, via web-first assertions.** The default. Frame headers are
strong oracles because they reflect the bound data instance —
`Table 1 - 323 Events - Netflix 2021 , Algeria, …` proves the search ran, the
connection landed, and the right instance is bound. The table is virtualized, so
`tr` elements do not exist; assertions target text content, never row counts.

**app-db reads via an exported accessor.** The answer for canvas-backed views:
read a frame's event count and render state out of re-frame rather than
inspecting pixels. An export is required because `re_frame` is not reachable
from the page under webpack bundling (only `de` is global). Scoped to a
read-only accessor, debug-gated.

**Visual snapshots, sparingly.** Only for "did it paint at all", which neither
DOM nor state expresses. Determinism holds on a single machine but is unverified
across machines, so snapshots are pinned to the CI container image with a
`maxDiffPixelRatio` tolerance. One or two in v1; broader visual coverage is v2.

**Map tiles are mocked.** The map issues live requests to
`a.tile.openstreetmap.de`. `page.route()` stubs them so the suite stays hermetic
and independent of a third-party service.

## Flake policy

No fixed sleeps. Two application-specific gates are encoded explicitly:

- Frame creation waits for `.window-placement-overlay` to reach
  `state: "detached"`.
- The Search button is gated on the backend's result-set estimate — measured
  disabled at t+0 and enabled by t+3s — expressed as
  `(.toBeEnabled #js {:timeout 30000})`.

CI runs with `retries: 1` and `trace: 'retain-on-failure'`; local runs use no
retries so flakes surface rather than hide.

## CI

A new `e2e_test.yml` mirroring the existing workflows, with a matrix over the
two projects. The browser project needs the production compile, cached on the
same `deps.edn` and `package-lock.json` hash keys the current workflows use. The
server project needs `docker compose up` plus readiness polling.

No new reporting infrastructure: Playwright's JUnit reporter emits the
`report.xml` that the existing `mikepenz/action-junit-report@v5` step already
consumes.

## Product-code changes

Deliberately small, and limited to what removes real brittleness:

- `data-testid` attributes on widgets with no stable handle today: the
  datasource combobox and its options, virtualized table rows and cells, and the
  traffic light.
- One `^:export` read-only app-db accessor.
- `POST /datasources` re-enabled behind the test-only flag.

Everything else uses ids that already exist: `#tool-*`, `#viewport-*`,
`woco_frame-<plugin>-<uuid>`, `.frame`.

## Scope

In scope for v1:

- Core data journey — search, result-set estimation, connect to Table, Mosaic,
  Map and Charts by drag and drop, assert rendered data.
- Workspace mechanics — frame create, move, resize, maximize, close; zoom and
  fit-to-content; window list; minimap; multi-select; tabs.
- Data import — CSV upload, schema mapping, commit, then search the imported
  data.

Out of scope for v1:

- Projects save, load and share. Deferred to v2.
- The electron bundle. Added later as a third Playwright project plus one
  fixture, driven through `_electron.launch()` against the `make prepare-prod`
  output, which already boots under xvfb via `verify-boot.sh`. `make dev-app` is
  unsuitable because its renderer windows stay blank by design.
- Broad visual-snapshot coverage.

## Risks

**Full compose mode is documented as less mature.** It built and ran during
validation, but expect to fix issues as coverage widens.

**Cross-machine canvas determinism is unproven.** Mitigated by pinning snapshots
to the CI image and keeping their number small.

**Browser compile time.** The production build takes roughly seven minutes;
without effective caching this dominates PR feedback time.

**Translated labels.** Default language is `:en-GB` and many accessible names
are translated strings. Prefer id-based selectors wherever both are available.

## Incidental observation

The compiled browser bundle includes `clojure.browser.repl` and issues a
CSP-blocked request to `http://localhost:9000/repl` at startup. This was seen in
a hand-assembled `dist` that used `resources/public/index.html` rather than
`build.sh`'s template, so it should be confirmed against a real `build.sh`
output before being treated as a live defect. Unrelated to this suite.
