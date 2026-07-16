# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Explorama is a data analytics tool written in Clojure/ClojureScript. The codebase supports three deployment models (browser, server, electron) from a shared plugin architecture.

## Development Commands

### Browser Bundle (bundles/browser)

Development:
```bash
cd bundles/browser
npm install
bb gather-assets.bb.clj dev
clj -M:dev  # Starts Figwheel on port 8020
```

Build production:
```bash
cd bundles/browser
./build.sh
```

Tests:
```bash
cd bundles/browser
npm run test-ci  # Runs all tests in CI mode (140/0/0 as of this writing)
clj -M:test      # Runs tests with interactive REPL
```

### Electron Bundle (bundles/electron)

Split into `backend/` (Clojure backend + Electron main/worker process source)
and `frontend/` (ClojureScript UI), each with its own `deps.edn`/`package.json`.

Development (two terminals; both Figwheel configs default to port 8020, so
only one half can bind it at a time):
```bash
cd bundles/electron
make assets build_mode=dev   # populate resources/public/{css,fonts,img}

cd backend  && clj -M:dev    # terminal 1 - backend Figwheel REPL
cd frontend && clj -M:dev    # terminal 2 - frontend Figwheel REPL
```

Tests:
```bash
cd bundles/electron
make test           # both suites
make test-backend   # 112/0/0 - needs a Node with better-sqlite3 9.4.0
                     # prebuilds (the CI container's Node); locally, override
                     # with `npm install better-sqlite3@12 --no-save` in backend/
make test-frontend  # 71/0/0
```

App packaging (`dev-app`/`build-win`/`build-linux`) is currently unsupported -
orphaned by the backend/frontend split; tracked in issue #28.

### Server Bundle (bundles/server)

Note: For the auth/routing harness and the full containerized mode, see
`bundles/server/docker/README.md`. Backend and frontend have separate
`clj.deps.edn`/`cljs.deps.edn` (there is no plain `deps.edn`), so aliases
need `-Sdeps`.

Backend development (Clojure REPL on port 7888):
```bash
cd bundles/server
npm install
bb gather-assets.bb.clj dev
clojure -Sdeps "$(cat clj.deps.edn)" -M:dev
```

Frontend development (ClojureScript):
```bash
cd bundles/server
clojure -Sdeps "$(cat cljs.deps.edn)" -M:dev  # Figwheel on port 8020
```

Tests:
```bash
cd bundles/server
clojure -Sdeps "$(cat clj.deps.edn)" -M:test      # Backend tests (130/0/0 via :test-ci)
clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci  # Frontend tests in CI mode (71/0/0)
```

### Linting

```bash
# Lint Clojure/ClojureScript code
clj-kondo --lint plugins/
clj-kondo --lint bundles/browser/
clj-kondo --lint bundles/electron/
clj-kondo --lint bundles/server/
```

## Architecture

### Plugin System

Plugins are the core architectural unit. Each plugin follows a three-layer structure:

- **Backend** (`plugins/backend/de/explorama/backend/{plugin-name}/`): Clojure server-side logic
- **Frontend** (`plugins/frontend/de/explorama/frontend/{plugin-name}/`): ClojureScript UI and client logic
- **Shared** (`plugins/shared/de/explorama/shared/{plugin-name}/`): Code shared between frontend and backend

Key plugins include: `table`, `charts`, `map`, `mosaic`, `indicator`, `algorithms`, `projects`, `reporting`, `search`, `configuration`, `expdb`, `data-atlas`, `woco` (workspace core).

Plugin initialization pattern:
```clojure
;; Backend (backend.cljc)
(defn init []
  (frontend-api/register-routes websocket/endpoints))

;; Frontend (core.cljs)
(re-frame/reg-event-fx ::init-event
  (fn [{db :db} _]
    {:dispatch-n [[::register-plugin]
                  [::init-client user-info]]}))
```

### Frontend Architecture (Re-frame)

The frontend uses **re-frame** for state management:

- **Single app-db**: All state in one atom
- **Events**: Trigger state changes via `(re-frame/dispatch [::event-name params])`
- **Subscriptions**: Derive data from app-db via `(re-frame/subscribe [::sub-name])`
- **Effects**: Side effects (HTTP, backend calls) as data

Event naming convention:
- Namespaced: `:plugin-name/event-name`
- Private: `::event-name` (expands to current namespace)

Frontend Interface (FI) API provides plugin registry and shared services. Located in `plugins/frontend/de/explorama/frontend/woco/api/core.cljs`.

Naming conventions for FI API:
- `*-raw`: Direct values
- `*-fn`: Callable functions
- `*-sub`: Re-frame subscription functions
- `*-sub-vec`: Subscription vectors for @(subscribe ...)
- `*-db-get`: Direct DB access functions
- `*-event-vec`: Event vectors
- `*-event-dispatch`: Dispatch functions

### Backend Communication

Communication between frontend and backend uses **pneumatic-tubes** (WebSocket-based):

Frontend sends events:
```clojure
(backend-api/dispatch [route-keyword {:client-callback [::response-event]} ...params])
```

Backend defines routes:
```clojure
;; In websocket.cljc
(def endpoints
  {route-keyword handler-fn})

;; In backend.cljc
(frontend-api/register-routes endpoints)
```

Metadata options for requests:
- `:client-callback`: Event to dispatch on success
- `:failed-callback`: Event to dispatch on failure
- `:broadcast-callback`: Event to broadcast to all clients
- `:user-info`: User information
- `:client-id`: Unique client identifier

### Deployment Models

**Browser Bundle**: Frontend and backend both run in browser as ClojureScript. No network required. Data stored in IndexedDB.

**Server Bundle**: Frontend (ClojureScript) in browser, backend (Clojure) on JVM server. WebSocket communication. Multi-user capable.

**Electron Bundle**: Desktop app with separate processes:
- Main Process: Electron app coordinator
- UI Window: ClojureScript frontend
- Worker Window: Clojure backend
- Communication via MessagePorts

### Data Flow

1. User imports data (CSV/file)
2. Data Transformer validates and maps schema (`plugins/shared/de/explorama/shared/data_transformer/`)
3. EXPDB persists data with indexing (`plugins/backend/de/explorama/backend/expdb/`)
4. Visualization plugins query data via backend routes
5. Frontend receives filtered/aggregated data
6. Plugin renders visualization

Data provider system allows plugins to register data sources:
```clojure
(data-provider/register-provider "search"
  {:data-tiles handler-fn
   :data-tile-ref handler-fn})
```

### Frame Pattern

Visualizations are "frames" (windows/cards in workspace). Frame descriptor defines UI:
```clojure
{:loading? loading-impl
 :frame-header header-impl
 :toolbar toolbar-impl
 :legend legend-impl
 :filter filter-impl}
```

## File Organization

- **bundles/**: Bundle-specific code for each deployment model (browser, electron, server)
- **plugins/**: Shared plugin code (backend, frontend, shared, tests)
- **styles/**: Stylesheets and images
- **tools/**: Build tools and utilities
- **assets/**: Static assets
- **data/**: Sample data files

## Styles / Tailwind

- Sass is fully retired. Component + base styles live as native-nested CSS
  under `styles/src/css/` (`base/` and `components/`), assembled by the plain
  `@import` manifest `styles/src/css/style.css` and bundled with lightningcss
  via `npm run css:dist` (`css:watch` / `watch:css` is the chokidar watcher);
  `build.sh` renames the bundled output to `3_style.css`. Lightningcss does not
  emit `.css.map` sourcemap sidecars the way sass did — an accepted dev-only
  loss.
- Utility classes (`flex`, `p-2`, `gap-8`, ...) are generated by Tailwind v4
  from `styles/src/tailwind.css`, which scans cljs/cljc markup (`@source`
  entries) for class literals — no utility partials are hand-authored anymore.
- Build with `npm run tailwind:dist` in `styles/`; it's wired into `build`,
  `build:prod`, and `watch:all`, so a normal styles build/watch already
  regenerates it.
- `dist/css/5_utilities.css` must remain the **last** stylesheet link in every
  bundle's `index.html`: utilities are imported unlayered, so link order (not
  `@layer`) is what lets them win equal-specificity ties against component css.
- `styles/src/tailwind.css`'s `@theme` block is the single source of truth for
  design tokens (colors, spacing, etc.) — there is no separate colormap file
  or generator anymore; edit the `@theme` block directly.
- Component primitives are being migrated sheet-by-sheet from
  `styles/src/css/components/*.css` into utility stacks defined directly in
  their `ui_base` namespaces (e.g. `formular/button.cljs`, `formular/card.cljs`).
- Rules that can't become component-owned utilities (vendor-DOM, shared
  markers, global element selectors) live in `phase-2 remnants` sections of
  `styles/src/tailwind.css` and in residual `*_domain.css` sheets under
  `styles/src/css/components/` — one per plugin/feature area (e.g.
  `forms_domain.css`, `search_domain.css`, `table_domain.css`), each holding
  what's left after its source sheet was fully migrated and deleted; they're
  expected to shrink as later migration batches land.

## Key Dependencies

- Clojure 1.12.4
- ClojureScript 1.12.134
- re-frame 1.2.0: Frontend state management
- Reagent 1.0.0: React wrapper
- Figwheel Main 0.2.18: ClojureScript hot reloading
- pneumatic-tubes 0.3.0: WebSocket communication
- Mount 0.1.17: Component lifecycle
- Malli 0.12.0: Schema validation
- Timbre 5.1.2: Logging
- Babashka: Build automation

Frontend JavaScript dependencies include React 17, OpenLayers 7, Chart.js 3, Pixi.js 7, Quill, PapaParse.

## Common Patterns

### Path-based DB Access
```clojure
;; Define paths as constants
(def path/slides [:slides])

;; Consistent access
(get-in db path/slides)
(assoc-in db path/slides [...])
```

### Event Registration
```clojure
;; Register event handler
(re-frame/reg-event-fx ::my-event
  (fn [{db :db} [_ param]]
    {:db (assoc db :key param)
     :dispatch [::next-event]}))
```

### Subscription Registration
```clojure
;; Register subscription
(re-frame/reg-sub ::my-sub
  (fn [db _]
    (get-in db [:my :path])))
```

### Backend Route Handler
```clojure
(defn handler-fn
  [_ [params] {:keys [client-callback]}]
  (let [result (process params)]
    (when client-callback
      (tube/dispatch client-callback result))))
```

## Notes

- Browser bundle uses ClojureScript for backend (runs in browser, no server)
- Server bundle builds and runs containerized (compose full mode); it is less mature than the other bundles
- Electron is the primary deployment target, but its app-packaging pipeline (`dev-app`/`build-win`/`build-linux`) is currently unsupported - tracked in issue #28; dev and test flows work
- Three separate test suites: backend tests (Clojure), frontend tests (ClojureScript), electron tests
- Hot reloading available in development via Figwheel
- Production builds use advanced ClojureScript optimization (the server bundle uses `:simple` plus webpack bundling)
