# Agent Onboarding

Explorama is a Clojure/ClojureScript data analytics application with three deployment models:
browser, Electron, and server. Most product behavior lives in the shared plugin system under
`plugins/`; bundle-specific bootstrapping and platform code lives under `bundles/`.

## Project Skills

Project-local skills are stored in `skills/` because `.agents/` is read-only in this workspace.
Load the matching skill before task-specific work:

- `skills/explorama-bundle-development/SKILL.md`: bundle setup, dev servers, builds, tests, CI runners, package scripts, or dependency setup.
- `skills/explorama-plugin-work/SKILL.md`: plugin frontend/backend/shared code, re-frame events, frame integrations, Frontend Interface API usage, backend routes, or shared plugin contracts.
- `skills/explorama-data-import/SKILL.md`: CSV import, mapping EDN, data transformer code, sample data, EXPDB ingestion, or import tests.

## Repository Map

- `plugins/frontend/de/explorama/frontend/<plugin>/`: ClojureScript UI, re-frame events/subs, frame views, and plugin registration.
- `plugins/backend/de/explorama/backend/<plugin>/`: Clojure or CLJC backend handlers and route registration.
- `plugins/shared/de/explorama/shared/<plugin>/`: shared route keys, schemas, config, and pure logic.
- `plugins/*_test/`: shared plugin tests used by the bundle test runners.
- `bundles/browser/`: browser deployment. Frontend and backend both compile to ClojureScript.
- `bundles/electron/`: desktop app with frontend, backend worker, and Electron main process.
- `bundles/server/`: JVM backend plus browser frontend. The server bundle is documented as not currently functional.
- `styles/`, `assets/`, `data/`, and `tools/`: styling, static assets, sample data, and project utilities.

## Development Commands

Prerequisites used by the repo include Java, Clojure CLI, Leiningen, Node/npm, Make, and Babashka.

Browser:

```bash
cd bundles/browser
npm install
bb gather-assets.bb.clj dev
clj -M:dev
npm run test-ci
```

Current browser dev workaround from existing docs:

```bash
cd bundles/browser
npx shadow-cljs compile app
vite build --mode development
npx shadow-cljs watch app
```

Electron:

```bash
cd bundles/electron
make dev
make dev-app
make test
make test-backend
make test-frontend
```

Server:

```bash
cd bundles/server
clj -M:test
clj -M:test-ci
```

Lint Clojure/ClojureScript with `clj-kondo --lint plugins/` plus the touched bundle directory.

## Coding Guidance

- Prefer existing plugin patterns over new abstractions. Most verticals have a frontend `core.cljs`, backend `backend.cljc`, shared `ws-api` or config namespace, and tests in `plugins/*_test`.
- Keep shared code in `.cljc` portable across CLJ and CLJS. Avoid JVM-only or browser-only APIs in shared namespaces unless reader conditionals already establish that pattern.
- Use re-frame effects as data. Register events/subscriptions near the plugin namespace that owns the state.
- Use the Frontend Interface API through `de.explorama.frontend.common.frontend-interface`; respect its suffix conventions such as `*-event-vec`, `*-db-get`, `*-sub`, and `*-raw`.
- Frontend/backend calls usually flow through shared route keys, frontend `backend-api/dispatch` or `:backend-tube`, and backend `frontend-api/register-routes`.
- Put platform-specific behavior in the relevant bundle, not in shared plugin code.
- Do not commit generated build output such as `node_modules`, `target`, `dist`, or compiled JS assets unless the existing repo convention explicitly tracks that file.

## Verification

Choose the smallest meaningful check for the files touched, then broaden if shared behavior changed.

- Plugin/shared logic: run the owning bundle test command, commonly `cd bundles/browser && npm run test-ci` or `cd bundles/server && clj -M:test`.
- Electron-specific work: run the relevant `make test-*` target from `bundles/electron`.
- Clojure/ClojureScript changes: run `clj-kondo --lint` on the touched plugin or bundle path when available.
- Documentation-only changes do not require full test suites, but validate skill/frontmatter files if editing `skills/`.
