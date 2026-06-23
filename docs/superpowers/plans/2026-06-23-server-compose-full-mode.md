# Full Containerized Mode for the Server Compose Harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in "full" mode where the Explorama app runs in containers (built by a multi-stage Dockerfile, fronted by Caddy) via a compose override file, while the zero-config dev mode (socat → host) stays unchanged.

**Architecture:** Caddy uses env-driven upstreams (`FRONTEND_UPSTREAM`/`BACKEND_UPSTREAM`) defaulting to the dev `socat-*` services. A new `docker-compose.full.yml` override adds an `app-backend` (JRE + uberjar, serves `/ws`) and `app-frontend` (nginx serving the static build) container and repoints Caddy at them. A frontend fix makes the WebSocket protocol-correct and same-origin.

**Tech Stack:** Docker (multi-stage, multi-target build; compose override files), Caddy 2 (env-var upstreams), nginx (static SPA), Clojure/ClojureScript (`build.sh` → uberjar + `resources/public/`).

## Global Constraints

- **Dev parity:** `docker compose up` with no `.env` must behave exactly as today (Caddy → `socat-frontend:8020`/`socat-backend:4001` → host). Every change keeps dev defaults.
- **Build context is the repo root:** `build.sh` and deps reference `../../plugins/*`, so the Dockerfile build context is the repository root, with `dockerfile: bundles/server/Dockerfile`.
- **Backend run config:** `EXPLORAMA_BIND_ADDRESS=0.0.0.0`, `EXPLORAMA_PORT=4001`; JVM opts `-XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=compact --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true`.
- **Full-mode command:** `docker compose -f docker-compose.yml -f docker-compose.full.yml up --build`.
- **Build is the gating risk:** Task 1 is a build spike. If the WIP server bundle cannot build in Docker, report BLOCKED with the error. Tasks 2–5 (Caddy upstreams, override, frontend fix, docs) are still valuable and MUST proceed even if Task 1 is BLOCKED — only Task 6 (full-mode up) requires a successful build.

---

### Task 1: Multi-stage Dockerfile + build spike (gating)

**Files:**
- Create: `bundles/server/Dockerfile`
- Create: `bundles/server/docker/nginx/default.conf`
- Create: `.dockerignore` (repo root)
- Modify: `bundles/server/build.sh`

**Interfaces:**
- Produces: build targets `backend` (image runs `explorama-standalone.jar`, serves `:4001`) and `app-frontend` (nginx serves static `resources/public` on `:80`), built from a shared `builder` stage. Consumed by Task 3's compose override.

**Known blocker (must fix first):** there is **no `deps.edn`** in `bundles/server`. `build.sh` calls bare `clojure -M:prod` / `-A:prod`, but those aliases live in `cljs.deps.edn` (the `:prod` cljs build) and `clj.deps.edn` (the `:prod` uberjar) — which the `clojure` CLI does not read by default. So `build.sh` cannot run as written. The styles step (`../../styles/build.sh`) also runs its own `npm` build using a `.npmrc`. Repairing the build invocation is part of this gating spike.

- [ ] **Step 1: Add a repo-root `.dockerignore`** to keep the context lean

Create `/.dockerignore`:
```
.git
**/node_modules
**/target
**/.cpcache
**/.shadow-cljs
.superpowers
docs/superpowers
**/*.log
```

- [ ] **Step 2: Create the nginx SPA config**

Create `bundles/server/docker/nginx/default.conf`:
```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 3: Create the multi-stage Dockerfile**

Create `bundles/server/Dockerfile`:
```dockerfile
# syntax=docker/dockerfile:1

# ---- builder: JDK + Clojure CLI + Node + Babashka, runs build.sh ----
FROM clojure:temurin-21-tools-deps-bookworm AS builder
WORKDIR /build
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm curl bash \
 && curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install -o /tmp/install-bb \
 && bash /tmp/install-bb \
 && rm -rf /var/lib/apt/lists/* /tmp/install-bb
# Copy the inputs build.sh needs (paths relative to repo root).
COPY plugins ./plugins
COPY styles ./styles
COPY assets ./assets
COPY bundles/server ./bundles/server
WORKDIR /build/bundles/server
RUN ./build.sh

# ---- backend: JRE + uberjar, serves /ws on :4001 ----
FROM eclipse-temurin:21-jre-noble AS backend
WORKDIR /app
COPY --from=builder /build/bundles/server/target/explorama-standalone.jar ./app.jar
ENV EXPLORAMA_BIND_ADDRESS=0.0.0.0 \
    EXPLORAMA_PORT=4001
EXPOSE 4001
ENTRYPOINT ["java", \
  "-XX:+UnlockExperimentalVMOptions", "-XX:+UseShenandoahGC", \
  "-XX:ShenandoahGCHeuristics=compact", \
  "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
  "-Dio.netty.tryReflectionSetAccessible=true", \
  "-jar", "app.jar"]

# ---- app-frontend: nginx serving the static build ----
FROM nginx:alpine AS app-frontend
COPY --from=builder /build/bundles/server/resources/public /usr/share/nginx/html
COPY bundles/server/docker/nginx/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

- [ ] **Step 4: Repair `build.sh` so its Clojure steps resolve aliases**

`build.sh` has no `deps.edn` to read, so its bare `clojure -M:prod`/`-A:prod` calls cannot find the aliases. Load the right deps file per step. In `bundles/server/build.sh`, change the cljs build line:
```bash
clojure -M:prod -m cljs.main -co prod-opts.edn -c de.explorama.frontend.woco.app.core
```
to:
```bash
clojure -Sdeps "$(cat cljs.deps.edn)" -M:prod -m cljs.main -co prod-opts.edn -c de.explorama.frontend.woco.app.core
```
and the uberjar build line:
```bash
clojure -A:prod -m uberdeps.uberjar \
```
to:
```bash
clojure -Sdeps "$(cat clj.deps.edn)" -A:prod -m uberdeps.uberjar \
```
(`cljs.deps.edn` carries the `:prod` ClojureScript build; `clj.deps.edn` carries the `:prod` uberdeps build.) This makes `build.sh` runnable in Docker and on the host. If `-A:prod` does not apply `:prod`'s deps with the explicit `-m` in your Clojure CLI version, switch that step to `-M:prod` and verify it still invokes `uberdeps.uberjar` (not `:prod`'s `:main-opts`) — adjust as the spike requires.

- [ ] **Step 5: Run the build spike (the gating check)**

Run from the repo root:
```bash
docker build -f bundles/server/Dockerfile --target builder -t explorama-server-builder .
```
Expected: the build reaches `RUN ./build.sh` and finishes with `Build complete!` / `Uberjar: target/explorama-standalone.jar`.

Iteration allowed: if the build fails because `build.sh`/`gather-assets.bb.clj` needs an input directory not copied (e.g. `data/`), add the missing `COPY` line in the builder stage and re-run. The styles step runs `../../styles/build.sh` (its own `npm` build with a `.npmrc`); if it needs a registry the builder can't reach, capture that. This is expected bring-up work, not a placeholder.

**If the build fails on application code / cljs that cannot compile (WIP server bundle):** capture the exact error, set status **BLOCKED**, and report. Do not fake a success. The controller will continue with Tasks 2–5 (which do not need a working build).

- [ ] **Step 6: Build the two runtime targets**

```bash
docker build -f bundles/server/Dockerfile --target backend -t explorama-server-backend .
docker build -f bundles/server/Dockerfile --target app-frontend -t explorama-server-frontend .
```
Expected: both images build. Verify the artifacts landed:
```bash
docker run --rm explorama-server-backend ls -la /app/app.jar
docker run --rm explorama-server-frontend ls /usr/share/nginx/html/index.html
```
Expected: `app.jar` exists; `index.html` exists.

- [ ] **Step 7: Commit**

```bash
git add bundles/server/Dockerfile bundles/server/docker/nginx/default.conf .dockerignore bundles/server/build.sh
git commit -m "server compose: add multi-stage Dockerfile for containerized app"
```

---

### Task 2: Env-driven Caddy upstreams

**Files:**
- Modify: `bundles/server/docker/caddy/Caddyfile`

**Interfaces:**
- Consumes (env, from compose): `FRONTEND_UPSTREAM` (default `socat-frontend:8020`), `BACKEND_UPSTREAM` (default `socat-backend:4001`).
- Produces: a Caddyfile whose app/backend upstreams are env-driven; consumed by Task 3's override.

- [ ] **Step 1: Replace the three upstream targets with env vars**

In `bundles/server/docker/caddy/Caddyfile`, change the app site block. Replace:
```caddyfile
	handle /api/* {
		import oauth_gate
		reverse_proxy socat-backend:4001
	}

	handle /ws* {
		import oauth_gate
		reverse_proxy socat-backend:4001
	}

	handle {
		import oauth_gate
		reverse_proxy socat-frontend:8020
	}
```
with:
```caddyfile
	handle /api/* {
		import oauth_gate
		reverse_proxy {$BACKEND_UPSTREAM:-socat-backend:4001}
	}

	handle /ws* {
		import oauth_gate
		reverse_proxy {$BACKEND_UPSTREAM:-socat-backend:4001}
	}

	handle {
		import oauth_gate
		reverse_proxy {$FRONTEND_UPSTREAM:-socat-frontend:8020}
	}
```
Leave the globals, `oauth_gate`, `/health`, `/oauth2/*`, and the Casdoor block unchanged.

- [ ] **Step 2: Validate dev defaults (no env)**

```bash
cd bundles/server
docker run --rm -v "$PWD/docker/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -E "Valid|Error"
```
Expected: `Valid configuration`, no `Error`.

- [ ] **Step 3: Validate with full-mode upstreams set**

```bash
cd bundles/server
docker run --rm -e FRONTEND_UPSTREAM=app-frontend:80 -e BACKEND_UPSTREAM=app-backend:4001 \
  -v "$PWD/docker/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -E "Valid|Error"
```
Expected: `Valid configuration`, no `Error`.

- [ ] **Step 4: Commit**

```bash
cd bundles/server
git add docker/caddy/Caddyfile
git commit -m "server compose: make Caddy app/backend upstreams env-driven"
```

---

### Task 3: docker-compose.full.yml override

**Files:**
- Create: `bundles/server/docker-compose.full.yml`

**Interfaces:**
- Consumes: Dockerfile targets `backend`/`app-frontend` (Task 1); env vars `FRONTEND_UPSTREAM`/`BACKEND_UPSTREAM` (Task 2).
- Produces: a compose override that adds `app-backend`/`app-frontend` and repoints Caddy.

- [ ] **Step 1: Create the override file**

Create `bundles/server/docker-compose.full.yml`:
```yaml
# Override for the full containerized mode. Runs the Explorama app in
# containers instead of bridging to host services via socat.
#
#   docker compose -f docker-compose.yml -f docker-compose.full.yml up --build
#
# The base socat-frontend/socat-backend services still start but sit idle —
# Caddy's upstreams are repointed to the app containers below.
services:
  caddy:
    environment:
      FRONTEND_UPSTREAM: "app-frontend:80"
      BACKEND_UPSTREAM: "app-backend:4001"
    depends_on:
      - app-backend
      - app-frontend

  app-backend:
    build:
      context: ../..
      dockerfile: bundles/server/Dockerfile
      target: backend
    environment:
      EXPLORAMA_BIND_ADDRESS: "0.0.0.0"
      EXPLORAMA_PORT: "4001"
    expose:
      - "4001"
    restart: unless-stopped

  app-frontend:
    build:
      context: ../..
      dockerfile: bundles/server/Dockerfile
      target: app-frontend
    expose:
      - "80"
    restart: unless-stopped
```

- [ ] **Step 2: Verify the merged config renders the app services + repointed Caddy**

```bash
cd bundles/server
docker compose -f docker-compose.yml -f docker-compose.full.yml config 2>/dev/null \
  | grep -E "app-backend|app-frontend|FRONTEND_UPSTREAM|BACKEND_UPSTREAM|target: (backend|app-frontend)"
```
Expected: `app-backend` and `app-frontend` services present; `FRONTEND_UPSTREAM: "app-frontend:80"`, `BACKEND_UPSTREAM: "app-backend:4001"`; `target: backend` and `target: app-frontend`.

- [ ] **Step 3: Verify the base (dev) config is unaffected**

```bash
cd bundles/server
docker compose --env-file /dev/null config 2>/dev/null | grep -cE "app-backend|app-frontend"
```
Expected: `0` (the dev base has no app containers).

- [ ] **Step 4: Commit**

```bash
cd bundles/server
git add docker-compose.full.yml
git commit -m "server compose: add docker-compose.full.yml for containerized app"
```

---

### Task 4: Frontend WebSocket scheme + same-origin fix

**Files:**
- Modify: `bundles/server/frontend/de/explorama/frontend/backend_api.cljs`
- Modify: `bundles/server/shared/de/explorama/shared/common/configs/platform_specific.cljs`
- Modify: `bundles/server/shared/de/explorama/shared/common/configs/platform_specific.clj`

**Interfaces:**
- Produces: a WebSocket URL that uses `wss://` on HTTPS pages and connects same-origin by default (through Caddy's `/ws` gate).

- [ ] **Step 1: Confirm no consumer depends on the `localhost:4001` origin default**

```bash
cd /home/soeren/repos/private/Explorama
grep -rn "explorama-origin" bundles/server plugins | grep -v "platform_specific\|backend_api"
```
Expected: review the hits. If none depend on the value being `localhost:4001` (only same-origin/proxy usage), proceed with the default change in Step 3. If a consumer requires `localhost:4001`, SKIP Step 3 (leave the default) — Step 2's `(not-empty …)` + same-origin fallback still works because the URL falls back to `location.host` only when origin is blank; in that case note the deviation in the report.

- [ ] **Step 2: Fix the WebSocket URL in `backend_api.cljs`**

Replace:
```clojure
(def ^:private websocket-url
  (str "ws://" (or config-shared-platform/explorama-origin "") "/ws"))
```
with:
```clojure
(def ^:private websocket-url
  (let [proto (if (= "https:" (.. js/window -location -protocol)) "wss://" "ws://")
        host  (or (not-empty config-shared-platform/explorama-origin)
                  (.. js/window -location -host))]
    (str proto host "/ws")))
```

- [ ] **Step 3: Change the `explorama-origin` default to same-origin**

In BOTH `platform_specific.cljs` and `platform_specific.clj`, change:
```clojure
     :default "localhost:4001"
```
to:
```clojure
     :default ""
```
(within the `explorama-origin` `defconfig` only — leave `explorama-asset-origin` and others untouched).

- [ ] **Step 4: Verify the change compiles (cljs)**

The advanced compile in Task 1's builder is the heaviest check. For a fast local check that the namespace reads/parses, run clj-kondo on the changed files:
```bash
cd /home/soeren/repos/private/Explorama
clj-kondo --lint bundles/server/frontend/de/explorama/frontend/backend_api.cljs \
  bundles/server/shared/de/explorama/shared/common/configs/platform_specific.cljs \
  bundles/server/shared/de/explorama/shared/common/configs/platform_specific.clj
```
Expected: `errors: 0` (warnings unrelated to these edits are acceptable; the websocket-url form must not add errors). Note: full runtime behavior is verified in Task 6 (HTTP → `ws://`, HTTPS → `wss://`).

- [ ] **Step 5: Commit**

```bash
cd /home/soeren/repos/private/Explorama
git add bundles/server/frontend/de/explorama/frontend/backend_api.cljs \
        bundles/server/shared/de/explorama/shared/common/configs/platform_specific.cljs \
        bundles/server/shared/de/explorama/shared/common/configs/platform_specific.clj
git commit -m "server: derive WebSocket scheme from page protocol, default to same-origin"
```

---

### Task 5: Document full mode

**Files:**
- Modify: `bundles/server/docker/README.md`
- Modify: `bundles/server/README.md`

**Interfaces:**
- Consumes: the full-mode command and services from Tasks 1–3.

- [ ] **Step 1: Add a "Full mode (run the app in containers)" section to `docker/README.md`**

Insert this section immediately before the existing "## HTTPS (production)" section:
```markdown
## Full mode (run the app in containers)

By default the harness bridges to host-run frontend/backend via `socat` (dev
mode). To run the Explorama app itself in containers, add the override file:

```bash
docker compose -f docker-compose.yml -f docker-compose.full.yml up --build
```

This builds two images from `Dockerfile` and repoints Caddy at them:

- `app-backend`: the backend uberjar, serving `/ws` on `:4001`.
- `app-frontend`: nginx serving the advanced-compiled frontend.

The base `socat-frontend`/`socat-backend` services still start but are idle in
full mode (Caddy routes to the app containers via `FRONTEND_UPSTREAM` /
`BACKEND_UPSTREAM`). Full mode composes with the HTTPS env: add your production
`.env` to serve it over Let's Encrypt.

Note: the server bundle is still incomplete, so full mode runs the build but the
application is not yet fully functional.
```

- [ ] **Step 2: Add the two upstream vars to the Configuration table in `docker/README.md`**

Add these rows to the "Supported variables" table (after the `CASDOOR_ORIGIN` row):
```markdown
| `FRONTEND_UPSTREAM` | `socat-frontend:8020` | Caddy frontend upstream (full mode sets `app-frontend:80`) |
| `BACKEND_UPSTREAM` | `socat-backend:4001` | Caddy backend upstream (full mode sets `app-backend:4001`) |
```

- [ ] **Step 3: Add a short note to `bundles/server/README.md`**

After the existing "Detailed Docker documentation lives in [docker/README.md](docker/README.md)." line, add:
```markdown

To run the app itself in containers (instead of bridging to host dev servers),
use full mode: `docker compose -f docker-compose.yml -f docker-compose.full.yml up --build`.
See [docker/README.md](docker/README.md#full-mode-run-the-app-in-containers).
```

- [ ] **Step 4: Commit**

```bash
cd bundles/server
git add docker/README.md README.md
git commit -m "server compose: document full containerized mode"
```

---

### Task 6: Integration verification (full mode) — requires Task 1 build success

**Files:**
- None (verification only). **Skip if Task 1 reported BLOCKED.**

**Interfaces:**
- Consumes: all prior tasks.

- [ ] **Step 1: Build and start full mode**

```bash
cd bundles/server
docker compose -f docker-compose.yml -f docker-compose.full.yml up -d --build
```
Expected: `caddy`, `casdoor`, `casdoor-init`, `oauth2-proxy`, `app-backend`, `app-frontend` (and idle `socat-*`) start.

- [ ] **Step 2: Confirm Caddy serves the static frontend**

```bash
curl -s http://localhost/health
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/
```
Expected: `OK` for `/health`; the root redirects to the oauth sign-in (`302`) — i.e., the gate is in front of the static frontend, same as dev.

- [ ] **Step 3: Confirm the backend container serves `/ws`**

The oauth gate protects `/ws`, so check the backend container directly on the compose network:
```bash
cd bundles/server
docker compose -f docker-compose.yml -f docker-compose.full.yml exec -T caddy \
  wget -q -S -O /dev/null http://app-backend:4001/ws 2>&1 | head -5
```
Expected: a response from the backend (HTTP status line; a WebSocket endpoint typically returns `400`/`200` to a plain GET, not connection-refused). Connection-refused means the backend did not start — capture `docker compose ... logs app-backend`.

- [ ] **Step 4: Confirm the static asset is reachable through the stack**

```bash
cd bundles/server
docker compose -f docker-compose.yml -f docker-compose.full.yml exec -T caddy \
  wget -q -S -O /dev/null http://app-frontend:80/index.html 2>&1 | head -5
```
Expected: `HTTP/1.1 200 OK` from nginx.

- [ ] **Step 5: Tear down**

```bash
cd bundles/server
docker compose -f docker-compose.yml -f docker-compose.full.yml down
```
Expected: all services stop; named volumes retained.

- [ ] **Step 6: Record the result**

Report whether full mode came up, the `/health` + static + backend checks, and any `app-backend`/`app-frontend` log errors. If the app is non-functional beyond serving (WIP), note it — that is expected and out of scope.

---

## Out of scope / future

- Completing the WIP server bundle so the app is functionally usable.
- A `/search` reverse-proxy path prefix (full mode runs at root).
- Pushing built images to a registry / non-build deployment.
- A browser-based end-to-end login + WebSocket test (no browser in this environment; Task 6 verifies the transport plumbing, not full app behavior).
