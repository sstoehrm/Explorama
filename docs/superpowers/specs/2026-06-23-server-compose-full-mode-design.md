# Full Containerized Mode for the Server Compose Harness

Date: 2026-06-23
Status: Approved (design); implementation pending
Scope: `bundles/server` Docker compose harness

## Problem

The compose harness routes the Explorama frontend/backend through `socat`
bridges to `host.docker.internal`, so the app must run on the host (`clj -M:dev`).
The harness is therefore **dev-only**: there is no way to run the actual app
inside compose. No version of this harness (old single-container Dockerfile or
current socat setup) has ever run the Explorama app in a container.

We want an opt-in **full** mode where the app itself runs in containers, while
the zero-config dev mode (`docker compose up` → socat → host) stays unchanged.

## Findings that shape the design

- **The build produces two artifacts.** `build.sh` runs `npm ci`, gathers
  assets, compiles the ClojureScript frontend (`:advanced`) into
  `resources/public/`, and builds a backend uberjar
  (`target/explorama-standalone.jar`, main `de.explorama.backend.woco.app.server`).
- **The backend serves only `/ws`.** `bundles/server/backend/.../handler.clj`
  returns `not-found` for everything else — it does not serve the frontend.
  So the frontend must be served separately (static files), and `/ws` (plus
  future `/api`) proxied to the backend.
- **The build needs `../../plugins/*`.** `clj.deps.edn`/`cljs.deps.edn` reference
  `../../plugins/backend`, `../../plugins/shared`, etc. The Docker build context
  must be the **repository root**.
- **Backend config:** `EXPLORAMA_PORT` (default `4001`), `EXPLORAMA_BIND_ADDRESS`
  (default `localhost`; must be `0.0.0.0` in a container).
- **The frontend hardcodes `ws://`.** `backend_api.cljs:26` builds
  `(str "ws://" (or explorama-origin "") "/ws")`; `explorama-origin` defaults to
  `localhost:4001`. A `ws://` socket from an `https://` page is blocked by
  browsers (mixed content), so HTTPS (and the proxied full mode) need a fix.
- **Server bundle is WIP.** Per the repo's own notes and confirmed in code
  (most API routes commented out, token validation bypassed). Containerizing
  changes *where* the app runs; it does not complete the app.

## Goals

- Opt-in full mode where `app-backend` and `app-frontend` run as containers and
  Caddy serves/routes to them.
- Zero-config dev unchanged: `docker compose up` with no `.env` still uses socat
  → host, exactly as today.
- Works together with the HTTPS env model (full mode behind Let's Encrypt).
- Fix the `ws://`/origin handling so the WebSocket works over HTTP and HTTPS.

## Non-goals

- Making the WIP server bundle functionally complete.
- A `/search` reverse-proxy path prefix (kept at root unless the build forces it;
  see Risks).
- Marking PR/branch strategy decisions (handled at finish).

## Architecture

One uniform Caddy front door with **env-driven upstreams**. Dev defaults keep
today's behavior; the override file swaps in app containers.

```text
                         Caddy (env-driven upstreams)
  FRONTEND_UPSTREAM   dev → socat-frontend:8020   |  full → app-frontend:80
  BACKEND_UPSTREAM    dev → socat-backend:4001    |  full → app-backend:4001
  (Casdoor site block unchanged)

dev:   docker compose up
full:  docker compose -f docker-compose.yml -f docker-compose.full.yml up --build
```

In full mode the base `socat-*` services still start but sit idle — Caddy no
longer routes to them (upstreams point at the app containers). This is
documented; they perform no host connection unless something connects to their
listener.

## File-by-file changes

1. **`bundles/server/Dockerfile`** (new; multi-stage, multi-target):
   - `builder`: JDK 21 + Clojure CLI + Node/npm + Babashka. Build context is the
     repo root; copies what `build.sh` needs (`bundles/server`, `plugins/`,
     `styles/`/`assets/` as required) and runs `build.sh`, producing
     `target/explorama-standalone.jar` and `resources/public/`.
   - `backend` target: `eclipse-temurin:21-jre`; copies the uberjar; runs
     `java <jvm-opts> -jar app.jar` with the Shenandoah + `--add-opens`
     `-Dio.netty.tryReflectionSetAccessible=true` opts from the `:prod`/`:dev`
     aliases. Listens on `:4001`, `EXPLORAMA_BIND_ADDRESS=0.0.0.0`.
   - `app-frontend` target: `nginx:alpine` serving `resources/public/` with an
     SPA fallback (`try_files $uri $uri/ /index.html`) on `:80`.

2. **`bundles/server/docker-compose.full.yml`** (new override):
   - `app-backend`: `build: {context: ../.., dockerfile: bundles/server/Dockerfile, target: backend}`, `environment: EXPLORAMA_BIND_ADDRESS=0.0.0.0`, `expose: ["4001"]`, `restart: unless-stopped`.
   - `app-frontend`: same Dockerfile, `target: app-frontend`, `expose: ["80"]`, `restart: unless-stopped`.
   - `caddy`: `environment: FRONTEND_UPSTREAM=app-frontend:80`, `BACKEND_UPSTREAM=app-backend:4001`; add `app-backend`/`app-frontend` to `depends_on`.

3. **`bundles/server/docker/caddy/Caddyfile`** (modify): the app site block's
   `/api/*` and `/ws*` → `reverse_proxy {$BACKEND_UPSTREAM:-socat-backend:4001}`;
   the catch-all frontend handler →
   `reverse_proxy {$FRONTEND_UPSTREAM:-socat-frontend:8020}`. Dev defaults match
   current behavior. Casdoor block unchanged.

4. **`bundles/server/frontend/de/explorama/frontend/backend_api.cljs`** (modify):
   replace the hardcoded `ws://` URL with a protocol-derived scheme and
   same-origin host:

   ```clojure
   (def ^:private websocket-url
     (let [proto (if (= "https:" (.. js/window -location -protocol)) "wss://" "ws://")
           host  (or (not-empty config-shared-platform/explorama-origin)
                     (.. js/window -location -host))]
       (str proto host "/ws")))
   ```

5. **`bundles/server/shared/.../platform_specific.clj(s)`** (modify): change
   `explorama-origin` default from `"localhost:4001"` to `""` so same-origin
   (via Caddy, through the auth gate) is the default — **after** grepping all
   consumers to confirm nothing relies on the `localhost:4001` default.

6. **Docs** (`docker/README.md` + short `README.md` note): how to run full mode
   (`docker compose -f docker-compose.yml -f docker-compose.full.yml up --build`),
   the new services, that it composes with the HTTPS `.env`, and the build/WIP
   caveats.

## Risks & mitigations

- **Build success is the gating risk.** The implementation plan MUST front-load a
  "build spike": get `build.sh` green inside the Docker `builder` stage before
  building the rest. If the WIP app cannot build, the plumbing (compose override,
  Caddy upstreams, ws fix) is still correct but full mode cannot run; stop and
  report rather than build blind.
- **`search` path-prefix / reverse-proxy mode.** `server-config` supports a
  reverse-proxy mode that prefixes asset paths with `search/`. Full mode keeps
  the app at the root path and leaves `reverse-proxy?` off unless the built
  frontend requests assets under a prefix; investigate during implementation and
  adjust nginx/Caddy paths only if the build proves it necessary.
- **`explorama-origin` default change.** Grep all consumers before changing the
  default to `""`; if something depends on `localhost:4001`, keep the default and
  instead special-case same-origin in `backend_api.cljs`.

## Testing / verification plan

- **Build spike (first):** `docker compose -f docker-compose.yml -f docker-compose.full.yml build` (or `docker build --target backend`/`--target app-frontend`). Expected: uberjar + static assets produced. If it fails on WIP code, report and stop.
- **Dev unchanged:** `docker compose up` (no `.env`) still serves via socat; `caddy validate` and `docker compose config` still pass; `/health`→OK, gate 302→sign_in.
- **Full mode up:** `docker compose -f docker-compose.yml -f docker-compose.full.yml up -d`; Caddy serves the static frontend (index.html) at `/`; `/ws` reaches `app-backend` (WebSocket upgrade); Casdoor login still works.
- **HTTPS + full:** with the production `.env`, the page is `https://…` and the WebSocket connects via `wss://…/ws` (the `backend_api.cljs` fix), no mixed-content block.
- **`docker compose config`** for the merged (`-f … -f …`) set renders the app services and the overridden Caddy upstreams.

## Out of scope / future

- Completing the WIP server bundle so the app is functionally usable in full mode.
- A `/search` path-prefixed deployment.
- Pushing built images to a registry / non-build deployment.
