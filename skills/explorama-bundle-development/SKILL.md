---
name: explorama-bundle-development
description: Use when working on Explorama bundle commands, local development, build scripts, tests, CI runners, or dependency setup for browser, Electron, or server deployment models.
---

# Explorama Bundle Development

## Overview

Use this skill to choose the right bundle, command, and verification path for Explorama's browser, Electron, and server builds. Start from `AGENTS.md` for global project rules.

## Bundle Map

- `bundles/browser`: easiest development target; both frontend and backend run as ClojureScript in the browser.
- `bundles/electron`: desktop target with frontend, backend worker, and Electron main process. The `Makefile` drives setup, tests, and packaging.
- `bundles/server`: JVM backend plus browser frontend. Existing docs say this bundle is not currently functional, so verify assumptions before relying on it for runtime behavior.

## Workflow

1. Identify which bundle owns the changed file before running broad commands.
2. Read the local `package.json`, `deps.edn`, `Makefile`, or runner file instead of assuming script behavior.
3. Prefer targeted verification first. Broaden to another bundle when shared plugin code or shared data contracts changed.
4. Treat `make dev`, `make test`, and production builds as expensive: they can clean `node_modules`, `target`, generated resources, or dist folders.

## Commands

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
make build-win
make build-linux
```

Server:

```bash
cd bundles/server
npm install
bash gather-assets.sh dev
clj -M:dev
clj -M:test
clj -M:test-ci
```

Lint:

```bash
clj-kondo --lint plugins/
clj-kondo --lint bundles/browser/
clj-kondo --lint bundles/electron/
clj-kondo --lint bundles/server/
```

## Verification Selection

- Browser or cross-platform plugin behavior: use `cd bundles/browser && npm run test-ci` when dependencies are installed.
- JVM backend or server-only backend code: use `cd bundles/server && clj -M:test` or `clj -M:test-ci`.
- Electron frontend/backend changes: use `cd bundles/electron && make test-frontend`, `make test-backend`, or `make test`.
- Build script changes: run the exact script or a dry equivalent if the full build is too costly, and report any command that was skipped.

Do not claim a dev server, test, or build passed unless the command completed in the current session.
