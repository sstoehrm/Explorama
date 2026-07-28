# React 18 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade React 17.0.2 → 18.3.1 with reagent 1.3.0 and re-frame 1.4.7 across the browser, electron, and server bundles, keeping the legacy `reagent.dom/render` mount (React-17 semantics).

**Architecture:** Pure dependency bump — zero application code changes. Each bundle is upgraded and verified independently (its own commit), because each bundle pins its own reagent/re-frame in its deps.edn and its own react in package.json. The shared `plugins/` code is compiled per-bundle, so a green suite in one bundle does not certify another.

**Tech Stack:** ClojureScript + reagent/re-frame, npm, Figwheel test runners, GNU make (electron), xvfb boot verification.

## Global Constraints

- `react` / `react-dom`: exactly `18.3.1` (not `^18`, this repo pins exact versions).
- `reagent/reagent`: exactly `1.3.0`, KEEPING `:exclusions [cljsjs/react cljsjs/react-dom]` (npm supplies React; dropping the exclusions double-loads React via cljsjs).
- `re-frame/re-frame`: exactly `1.4.7`.
- Reagent 2.x exists (2.0.1) but is OUT OF SCOPE — spec mandates newest 1.x.
- `day8.re-frame/http-fx` (0.2.4) and `re-frame-utils` (0.1.0) are NOT touched.
- NO code changes: `dom/render` mounts, `rdom/dom-node`, and `reagent.dom.server/render-to-string` all stay as-is.
- `react-tooltip-lite@1.12.0` peer range (`^15.5.4 || ^16.0.0`) rejects React 18 → every bundle with a react dependency gets a `.npmrc` containing `legacy-peer-deps=true` (checked in; documents the stale peer range instead of churning the lib).
- New DEPRECATION WARNINGS in test/boot output are accepted (ReactDOM.render notice, UNSAFE_ lifecycles). New ERRORS or any change in pass/fail/error counts are failures.
- Commit `package-lock.json` changes together with their `package.json`.
- All commit messages end with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task 1: Browser bundle

**Files:**
- Modify: `bundles/browser/package.json:31,34` (react, react-dom)
- Modify: `bundles/browser/deps.edn:37-38` (reagent, re-frame)
- Create: `bundles/browser/.npmrc`
- Modify (generated): `bundles/browser/package-lock.json`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: the version-string pattern Tasks 2–3 repeat; no code interfaces.

- [ ] **Step 1: Bump npm versions**

In `bundles/browser/package.json` replace:

```json
    "react": "17.0.2",
```
with
```json
    "react": "18.3.1",
```
and
```json
    "react-dom": "17.0.2",
```
with
```json
    "react-dom": "18.3.1",
```

- [ ] **Step 2: Bump clj deps**

In `bundles/browser/deps.edn` replace:

```clojure
        reagent/reagent {:mvn/version "1.0.0" :exclusions [cljsjs/react cljsjs/react-dom]}
        re-frame/re-frame {:mvn/version "1.2.0"}}
```
with
```clojure
        reagent/reagent {:mvn/version "1.3.0" :exclusions [cljsjs/react cljsjs/react-dom]}
        re-frame/re-frame {:mvn/version "1.4.7"}}
```

- [ ] **Step 3: Add .npmrc**

Create `bundles/browser/.npmrc` with exactly:

```
legacy-peer-deps=true
```

- [ ] **Step 4: Install**

Run: `cd bundles/browser && npm install`
Expected: completes WITHOUT `ERESOLVE` errors (the .npmrc suppresses the react-tooltip-lite peer conflict). Verify: `node -p "require('./node_modules/react/package.json').version"` prints `18.3.1`.

- [ ] **Step 5: Run the browser suite**

Run: `cd bundles/browser && npm run test-ci`
Expected: `140` tests, `0` failures, `0` errors (same counts as pre-upgrade baseline). Deprecation warnings in output are OK.

- [ ] **Step 6: Commit**

```bash
git add bundles/browser/package.json bundles/browser/package-lock.json bundles/browser/deps.edn bundles/browser/.npmrc
git commit -m "deps(react18): browser bundle — react 18.3.1, reagent 1.3.0, re-frame 1.4.7

Legacy reagent.dom/render mount is kept (React-17 semantics). .npmrc
legacy-peer-deps covers react-tooltip-lite's stale peer range.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Electron bundle (backend + frontend)

**Files:**
- Modify: `bundles/electron/backend/deps.edn:38-39` (reagent, re-frame)
- Modify: `bundles/electron/frontend/deps.edn:35-36` (reagent, re-frame)
- Modify: `bundles/electron/frontend/package.json:31,34` (react, react-dom)
- Create: `bundles/electron/frontend/.npmrc`
- Modify (generated): `bundles/electron/frontend/package-lock.json`

**Interfaces:**
- Consumes: version choices fixed in Global Constraints (identical strings to Task 1).
- Produces: the upgraded electron tree Task 4's boot verification builds from.

- [ ] **Step 1: Bump clj deps in BOTH deps.edn files**

In `bundles/electron/backend/deps.edn` AND `bundles/electron/frontend/deps.edn` replace:

```clojure
        reagent/reagent {:mvn/version "1.0.0" :exclusions [cljsjs/react cljsjs/react-dom]}
        re-frame/re-frame {:mvn/version "1.2.0"}}
```
with
```clojure
        reagent/reagent {:mvn/version "1.3.0" :exclusions [cljsjs/react cljsjs/react-dom]}
        re-frame/re-frame {:mvn/version "1.4.7"}}
```

(The backend is a CLJS worker process — it pulls reagent/re-frame for re-frame event handling, not for DOM rendering. Its package.json has no react and is NOT touched; the better-sqlite3/electron pins in it are strictly off-limits, see issue #28.)

- [ ] **Step 2: Bump npm versions in the frontend**

In `bundles/electron/frontend/package.json` replace `"react": "17.0.2",` with `"react": "18.3.1",` and `"react-dom": "17.0.2",` with `"react-dom": "18.3.1",`.

- [ ] **Step 3: Add .npmrc**

Create `bundles/electron/frontend/.npmrc` with exactly:

```
legacy-peer-deps=true
```

- [ ] **Step 4: Install**

Run: `cd bundles/electron/frontend && npm install`
Expected: no `ERESOLVE` errors; `node -p "require('./node_modules/react/package.json').version"` prints `18.3.1`.

- [ ] **Step 5: Run both electron suites**

Run: `cd bundles/electron && make test`
Expected: backend `112` tests / 0 failures / 0 errors, frontend `71` / 0 / 0 (baseline counts). The backend run swapping in a better-sqlite3@12 prebuild (`--no-save`) is normal harness behavior, not a dependency change.

- [ ] **Step 6: Commit**

```bash
git add bundles/electron/backend/deps.edn bundles/electron/frontend/deps.edn bundles/electron/frontend/package.json bundles/electron/frontend/package-lock.json bundles/electron/frontend/.npmrc
git commit -m "deps(react18): electron bundle — react 18.3.1, reagent 1.3.0, re-frame 1.4.7

Backend deps.edn bumps too (CLJS worker uses re-frame headlessly).
better-sqlite3/electron pins untouched (issue #28).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Server bundle

**Files:**
- Modify: `bundles/server/cljs.deps.edn:39-40` (reagent, re-frame)
- Modify: `bundles/server/package.json:29,32` (react, react-dom)
- Create: `bundles/server/.npmrc`
- Modify (generated): `bundles/server/package-lock.json`

**Interfaces:**
- Consumes: version choices fixed in Global Constraints (identical strings to Task 1).
- Produces: nothing downstream.

- [ ] **Step 1: Bump clj deps**

In `bundles/server/cljs.deps.edn` replace:

```clojure
        reagent/reagent {:mvn/version "1.0.0" :exclusions [cljsjs/react cljsjs/react-dom]}
        re-frame/re-frame {:mvn/version "1.2.0"}}
```
with
```clojure
        reagent/reagent {:mvn/version "1.3.0" :exclusions [cljsjs/react cljsjs/react-dom]}
        re-frame/re-frame {:mvn/version "1.4.7"}}
```

(`clj.deps.edn` — the JVM backend — has no reagent/re-frame and is NOT touched.)

- [ ] **Step 2: Bump npm versions**

In `bundles/server/package.json` replace `"react": "17.0.2",` with `"react": "18.3.1",` and `"react-dom": "17.0.2",` with `"react-dom": "18.3.1",`.

- [ ] **Step 3: Add .npmrc**

Create `bundles/server/.npmrc` with exactly:

```
legacy-peer-deps=true
```

- [ ] **Step 4: Install**

Run: `cd bundles/server && npm install`
Expected: no `ERESOLVE` errors; `node -p "require('./node_modules/react/package.json').version"` prints `18.3.1`.

- [ ] **Step 5: Run the server frontend suite**

Run: `cd bundles/server && clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci`
Expected: `71` tests / 0 failures / 0 errors (baseline counts). The JVM backend suite is unaffected (clj.deps.edn untouched) and is not rerun.

- [ ] **Step 6: Commit**

```bash
git add bundles/server/cljs.deps.edn bundles/server/package.json bundles/server/package-lock.json bundles/server/.npmrc
git commit -m "deps(react18): server bundle — react 18.3.1, reagent 1.3.0, re-frame 1.4.7

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Real-render smoke test (electron boot verification)

**Files:**
- None modified. Build output lands in `dist/electron/` (gitignored).

**Interfaces:**
- Consumes: the upgraded electron tree committed in Task 2.
- Produces: the upgrade's end-to-end evidence; nothing downstream.

- [ ] **Step 1: Run the boot-verified packaging build**

Run: `cd bundles/electron && make build-linux`
Expected: completes green — prepare-prod, xvfb boot verification, AppImage in `dist/electron/`. This boots the real app and renders the real tree, which unit suites never do.

- [ ] **Step 2: Scan the boot log for React errors**

In the build/boot output, confirm: no `Error:` lines mentioning React/reagent, no "Invalid hook call", no "Cannot read" render crashes. The one-time `Warning: ReactDOM.render is no longer supported in React 18` deprecation IS expected — its presence actually confirms React 18 is live in legacy mode.

- [ ] **Step 3: Report**

No commit (no tracked files changed). Report suite counts and boot result; the branch is ready for merge review.
