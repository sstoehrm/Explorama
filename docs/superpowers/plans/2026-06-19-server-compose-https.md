# Opt-in HTTPS for the Server Compose Harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `bundles/server` Docker compose harness serve production HTTPS via Caddy's automatic Let's Encrypt provisioning when domain env vars are set, while keeping zero-config local dev on `http://localhost` unchanged.

**Architecture:** Caddy becomes the single public entry point for both the app and Casdoor. The site address of each Caddy block comes from an env var: a bare port (`:80`) serves plain HTTP (dev), a hostname triggers automatic HTTPS (prod). oauth2-proxy and Casdoor flip to HTTPS via a small, consistent set of env vars, all defaulting to today's localhost values.

**Tech Stack:** Docker Compose, Caddy 2 (Caddyfile + env substitution `{$VAR:default}`), oauth2-proxy (env-var config), Casdoor (Beego `app.conf` env interpolation `${VAR||default}`).

## Global Constraints

- **Dev parity:** `docker compose up` with **no** `.env` file must behave exactly as it does on `main` of this branch today — app on `http://localhost`, Casdoor on `http://localhost:8000`, insecure cookies, login with `dev`/`dev123`. Every env var introduced has an inline `${VAR:-default}` (compose) or `{$VAR:default}` (Caddyfile) default that reproduces current behavior.
- **No templating:** Do not reintroduce `entrypoint.sh` or `init_data.json.template`. `init_data.json` stays static. The production redirect URI is a documented manual step.
- **No `email` global in the Caddyfile:** an empty value fails `caddy validate` (`wrong argument count`). Caddy provisions Let's Encrypt without an account email. Docs mention adding one manually is optional.
- **Internal OIDC calls stay HTTP:** oauth2-proxy → Casdoor token redeem and JWKS fetch use `http://casdoor:8000` over the Docker network in both dev and prod; they never touch the browser.
- **Docs:** concise additions only (no full deployment guide).

---

### Task 1: HTTPS-capable Caddyfile

**Files:**
- Modify: `bundles/server/docker/caddy/Caddyfile`

**Interfaces:**
- Consumes (env, from compose): `EXPLORAMA_APP_HOST` (default `:80`), `CASDOOR_SITE_ADDRESS` (default `:8000`).
- Produces: an app site block (oauth gate + frontend/backend routing) and a Casdoor site block reverse-proxying `casdoor:8000`. Both serve plain HTTP for bare-port addresses and automatic HTTPS for hostnames.

- [ ] **Step 1: Replace the Caddyfile contents**

Replace the entire file with:

```caddyfile
{
	admin off
}

(oauth_gate) {
	forward_auth oauth2-proxy:4180 {
		uri /oauth2/auth
		copy_headers X-Auth-Request-User X-Auth-Request-Email

		@error status 401
		handle_response @error {
			redir * /oauth2/sign_in?rd={http.request.uri} 302
		}
	}
}

{$EXPLORAMA_APP_HOST::80} {
	encode gzip

	handle /health {
		respond "OK" 200
	}

	handle /oauth2/* {
		reverse_proxy oauth2-proxy:4180
	}

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
}

{$CASDOOR_SITE_ADDRESS::8000} {
	encode gzip
	reverse_proxy casdoor:8000
}
```

Changes vs. current: removed `auto_https off` from globals; app address `:80` → `{$EXPLORAMA_APP_HOST::80}`; added the Casdoor site block.

- [ ] **Step 2: Validate the dev shape (bare ports → HTTP only)**

Run:
```bash
cd bundles/server
docker run --rm -v "$PWD/docker/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -E "Valid|Error|no automatic HTTPS"
```
Expected: a line containing `Valid configuration` and a warning that the server listens only on the HTTP port (no automatic HTTPS). No `Error`.

- [ ] **Step 3: Validate the production shape (hostnames → auto-HTTPS)**

Run:
```bash
cd bundles/server
docker run --rm \
  -e EXPLORAMA_APP_HOST=app.example.com -e CASDOOR_SITE_ADDRESS=auth.example.com \
  -v "$PWD/docker/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | grep -E "Valid|Error|enabling automatic"
```
Expected: `Valid configuration` and `enabling automatic HTTP->HTTPS redirects`. No `Error`.

- [ ] **Step 4: Commit**

```bash
cd bundles/server
git add docker/caddy/Caddyfile
git commit -m "server compose: make Caddy site addresses env-driven for HTTPS"
```

---

### Task 2: Env files (dev defaults + production example)

**Files:**
- Modify: `bundles/server/.env.example`
- Create: `bundles/server/.env.production.example`

**Interfaces:**
- Produces: two internally-consistent env sets consumed by `docker-compose.yml` (Task 3), the Caddyfile (Task 1), and `app.conf` (Task 4). Variable names: `EXPLORAMA_APP_HOST`, `CASDOOR_SITE_ADDRESS`, `EXPLORAMA_HTTP_PORT`, `EXPLORAMA_HTTPS_PORT`, `CASDOOR_PORT`, `EXPLORAMA_PUBLIC_URL`, `CASDOOR_LOGIN_URL`, `CASDOOR_ISSUER_URL`, `CASDOOR_ORIGIN`, `OAUTH2_PROXY_COOKIE_SECURE`, `OAUTH2_PROXY_SKIP_OIDC_DISCOVERY`, `OAUTH2_PROXY_SKIP_ISSUER_VERIFICATION`, `HOST_FRONTEND_PORT`, `HOST_BACKEND_PORT`, `CASDOOR_CLIENT_ID`, `CASDOOR_CLIENT_SECRET`, `OAUTH2_PROXY_COOKIE_SECRET`.

- [ ] **Step 1: Replace `.env.example` with the dev defaults**

```bash
# Development defaults for bundles/server/docker-compose.yml.
# Copy to .env and adjust for local ports or rotated development secrets.
# This file documents the same values compose falls back to with no .env.

# --- Public entry (Caddy) ---
# Bare ports keep plain HTTP for local dev. Set these to real hostnames
# (e.g. app.example.com / auth.example.com) to enable automatic HTTPS via
# Let's Encrypt. See .env.production.example.
EXPLORAMA_APP_HOST=:80
CASDOOR_SITE_ADDRESS=:8000

EXPLORAMA_HTTP_PORT=80
EXPLORAMA_HTTPS_PORT=443
CASDOOR_PORT=8000

# --- Browser-facing URLs ---
EXPLORAMA_PUBLIC_URL=http://localhost
CASDOOR_LOGIN_URL=http://localhost:8000/login/oauth/authorize

# --- OIDC wiring ---
CASDOOR_ISSUER_URL=http://casdoor:8000
CASDOOR_ORIGIN=http://localhost:8000
OAUTH2_PROXY_COOKIE_SECURE=false
OAUTH2_PROXY_SKIP_OIDC_DISCOVERY=false
OAUTH2_PROXY_SKIP_ISSUER_VERIFICATION=true

# --- Host app ports (socat bridges) ---
HOST_FRONTEND_PORT=8020
HOST_BACKEND_PORT=4001

# These values must match docker/casdoor/init_data.json for first-run seeding.
CASDOOR_CLIENT_ID=explorama-dev-client-id
CASDOOR_CLIENT_SECRET=explorama-dev-client-secret

# Regenerate with:
# python3 -c 'import os,base64; print(base64.urlsafe_b64encode(os.urandom(32)).decode())'
OAUTH2_PROXY_COOKIE_SECRET=F1y4cx_-xaykqU0qroC6yIGjnzLcq-JEIRedKFXIrbE=
```

- [ ] **Step 2: Create `.env.production.example`**

```bash
# Production HTTPS example for bundles/server/docker-compose.yml.
# Copy to .env, replace the example.com hosts, and change ALL secrets.
#
# Requirements:
#   - DNS A records for both hosts pointing at this server.
#   - Inbound ports 80 and 443 reachable from the internet (ACME + HTTPS).
#   - Caddy obtains and renews Let's Encrypt certificates automatically.
#   - Register the production redirect URI in Casdoor (see docker/README.md).

# --- Public entry (Caddy) ---  hostnames trigger automatic HTTPS
EXPLORAMA_APP_HOST=app.example.com
CASDOOR_SITE_ADDRESS=auth.example.com

EXPLORAMA_HTTP_PORT=80
EXPLORAMA_HTTPS_PORT=443
CASDOOR_PORT=8000

# --- Browser-facing URLs (HTTPS) ---
EXPLORAMA_PUBLIC_URL=https://app.example.com
CASDOOR_LOGIN_URL=https://auth.example.com/login/oauth/authorize

# --- OIDC wiring ---
# Issuer matches Casdoor's public origin, so issuer verification is enabled
# and the insecure skip is turned off.
CASDOOR_ISSUER_URL=https://auth.example.com
CASDOOR_ORIGIN=https://auth.example.com
OAUTH2_PROXY_COOKIE_SECURE=true
OAUTH2_PROXY_SKIP_OIDC_DISCOVERY=true
OAUTH2_PROXY_SKIP_ISSUER_VERIFICATION=false

# --- Host app ports (socat bridges) ---
HOST_FRONTEND_PORT=8020
HOST_BACKEND_PORT=4001

# --- Secrets: MUST be changed for production ---
# Must match docker/casdoor/init_data.json (or update in the Casdoor UI).
CASDOOR_CLIENT_ID=change-me-client-id
CASDOOR_CLIENT_SECRET=change-me-client-secret

# Regenerate with:
# python3 -c 'import os,base64; print(base64.urlsafe_b64encode(os.urandom(32)).decode())'
OAUTH2_PROXY_COOKIE_SECRET=change-me-32-byte-base64-secret
```

- [ ] **Step 3: Verify both files contain the key switches**

Run:
```bash
cd bundles/server
grep -E "EXPLORAMA_APP_HOST|OAUTH2_PROXY_COOKIE_SECURE" .env.example .env.production.example
```
Expected: `.env.example` shows `EXPLORAMA_APP_HOST=:80` and `OAUTH2_PROXY_COOKIE_SECURE=false`; `.env.production.example` shows `EXPLORAMA_APP_HOST=app.example.com` and `OAUTH2_PROXY_COOKIE_SECURE=true`.

- [ ] **Step 4: Commit**

```bash
cd bundles/server
git add .env.example .env.production.example
git commit -m "server compose: add HTTPS env vars and production example"
```

---

### Task 3: Wire env vars into docker-compose.yml

**Files:**
- Modify: `bundles/server/docker-compose.yml`

**Interfaces:**
- Consumes: all env vars from Task 2.
- Produces: Caddy publishing 80/443/8000; oauth2-proxy configured purely via `OAUTH2_PROXY_*` environment vars; Casdoor receiving `CASDOOR_ORIGIN`.

- [ ] **Step 1: Replace docker-compose.yml contents**

```yaml
services:
  caddy:
    image: caddy:2-alpine
    ports:
      - "${EXPLORAMA_HTTP_PORT:-80}:80"
      - "${EXPLORAMA_HTTPS_PORT:-443}:443"
      - "${CASDOOR_PORT:-8000}:8000"
    volumes:
      - ./docker/caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - oauth2-proxy
      - casdoor
      - socat-frontend
      - socat-backend
    restart: unless-stopped

  casdoor:
    image: casbin/casdoor:latest
    expose:
      - "8000"
    environment:
      CASDOOR_ORIGIN: "${CASDOOR_ORIGIN:-http://localhost:8000}"
    volumes:
      - ./docker/casdoor/app.conf:/conf/app.conf:ro
      - ./docker/casdoor/init_data.json:/conf/init_data.json:ro
      - casdoor_data:/data
    restart: unless-stopped

  oauth2-proxy:
    image: quay.io/oauth2-proxy/oauth2-proxy:latest
    environment:
      OAUTH2_PROXY_HTTP_ADDRESS: "0.0.0.0:4180"
      OAUTH2_PROXY_PROVIDER: "oidc"
      OAUTH2_PROXY_OIDC_ISSUER_URL: "${CASDOOR_ISSUER_URL:-http://casdoor:8000}"
      OAUTH2_PROXY_LOGIN_URL: "${CASDOOR_LOGIN_URL:-http://localhost:8000/login/oauth/authorize}"
      OAUTH2_PROXY_REDEEM_URL: "http://casdoor:8000/api/login/oauth/access_token"
      OAUTH2_PROXY_OIDC_JWKS_URL: "http://casdoor:8000/.well-known/jwks"
      OAUTH2_PROXY_INSECURE_OIDC_SKIP_ISSUER_VERIFICATION: "${OAUTH2_PROXY_SKIP_ISSUER_VERIFICATION:-true}"
      OAUTH2_PROXY_SKIP_OIDC_DISCOVERY: "${OAUTH2_PROXY_SKIP_OIDC_DISCOVERY:-false}"
      OAUTH2_PROXY_REDIRECT_URL: "${EXPLORAMA_PUBLIC_URL:-http://localhost}/oauth2/callback"
      OAUTH2_PROXY_CLIENT_ID: "${CASDOOR_CLIENT_ID:-explorama-dev-client-id}"
      OAUTH2_PROXY_CLIENT_SECRET: "${CASDOOR_CLIENT_SECRET:-explorama-dev-client-secret}"
      OAUTH2_PROXY_COOKIE_SECRET: "${OAUTH2_PROXY_COOKIE_SECRET:-F1y4cx_-xaykqU0qroC6yIGjnzLcq-JEIRedKFXIrbE=}"
      OAUTH2_PROXY_COOKIE_SECURE: "${OAUTH2_PROXY_COOKIE_SECURE:-false}"
      OAUTH2_PROXY_EMAIL_DOMAINS: "*"
      OAUTH2_PROXY_UPSTREAMS: "static://202"
      OAUTH2_PROXY_REVERSE_PROXY: "true"
      OAUTH2_PROXY_SKIP_PROVIDER_BUTTON: "true"
      OAUTH2_PROXY_SCOPE: "openid profile email"
    expose:
      - "4180"
    depends_on:
      - casdoor
    restart: unless-stopped

  socat-frontend:
    image: alpine/socat
    command: "tcp-listen:8020,fork,reuseaddr tcp-connect:host.docker.internal:${HOST_FRONTEND_PORT:-8020}"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    expose:
      - "8020"
    restart: unless-stopped

  socat-backend:
    image: alpine/socat
    command: "tcp-listen:4001,fork,reuseaddr tcp-connect:host.docker.internal:${HOST_BACKEND_PORT:-4001}"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    expose:
      - "4001"
    restart: unless-stopped

volumes:
  caddy_data:
  caddy_config:
  casdoor_data:
```

Changes vs. current: Caddy now publishes 443 and 8000 and `depends_on` casdoor; Casdoor lost its own `8000:8000` host mapping (Caddy fronts it) and gained `CASDOOR_ORIGIN`; oauth2-proxy converted from `command:` flags to `environment:` vars.

- [ ] **Step 2: Verify dev defaults render correctly (no .env)**

`--env-file /dev/null` makes compose ignore any local `.env`, so only the
inline `${VAR:-default}` values apply:
```bash
cd bundles/server
docker compose --env-file /dev/null config 2>/dev/null | grep -E "OAUTH2_PROXY_COOKIE_SECURE|OAUTH2_PROXY_REDIRECT_URL|target: (80|443|8000)"
```
Expected: `OAUTH2_PROXY_COOKIE_SECURE: "false"`, `OAUTH2_PROXY_REDIRECT_URL: "http://localhost/oauth2/callback"`, and `target:` lines for 80, 443, and 8000.

- [ ] **Step 3: Verify production env renders correctly**

Run:
```bash
cd bundles/server
docker compose --env-file .env.production.example config | grep -E "OAUTH2_PROXY_COOKIE_SECURE|OAUTH2_PROXY_REDIRECT_URL|OAUTH2_PROXY_OIDC_ISSUER_URL|CASDOOR_ORIGIN"
```
Expected: `OAUTH2_PROXY_COOKIE_SECURE: "true"`, `OAUTH2_PROXY_REDIRECT_URL: "https://app.example.com/oauth2/callback"`, `OAUTH2_PROXY_OIDC_ISSUER_URL: "https://auth.example.com"`, `CASDOOR_ORIGIN: "https://auth.example.com"`.

- [ ] **Step 4: Commit**

```bash
cd bundles/server
git add docker-compose.yml
git commit -m "server compose: env-driven Caddy ports, oauth2-proxy, and Casdoor origin"
```

---

### Task 4: Make Casdoor origin env-driven

**Files:**
- Modify: `bundles/server/docker/casdoor/app.conf`

**Interfaces:**
- Consumes (env, from compose): `CASDOOR_ORIGIN` (default `http://localhost:8000`).
- Produces: Casdoor's `origin` (and thus the OIDC `issuer` / JWT `iss` claim) follows the env var.

- [ ] **Step 1: Make the `origin` line env-driven**

Change line 8 of `app.conf` from:
```ini
origin = http://localhost:8000
```
to (Beego ini env interpolation):
```ini
origin = ${CASDOOR_ORIGIN||http://localhost:8000}
```

- [ ] **Step 2: Verify the file content**

Run:
```bash
cd bundles/server
grep "^origin" docker/casdoor/app.conf
```
Expected: `origin = ${CASDOOR_ORIGIN||http://localhost:8000}`

(End-to-end confirmation that Casdoor reads this correctly happens in Task 6.)

- [ ] **Step 3: Commit**

```bash
cd bundles/server
git add docker/casdoor/app.conf
git commit -m "server compose: drive Casdoor origin from CASDOOR_ORIGIN env var"
```

---

### Task 5: Document HTTPS (concise)

**Files:**
- Modify: `bundles/server/docker/README.md`
- Modify: `bundles/server/README.md`

**Interfaces:**
- Consumes: the env-var model from Tasks 2-4.
- Produces: a concise HTTPS section and an updated Production Notes list.

- [ ] **Step 1: Extend the Configuration table in `docker/README.md`**

In `docker/README.md`, add these rows to the "Supported variables" table (after the existing `OAUTH2_PROXY_COOKIE_SECRET` row):

```markdown
| `EXPLORAMA_APP_HOST` | `:80` | Caddy app site address; a hostname enables HTTPS |
| `CASDOOR_SITE_ADDRESS` | `:8000` | Caddy Casdoor site address; a hostname enables HTTPS |
| `EXPLORAMA_HTTPS_PORT` | `443` | Host port for Caddy HTTPS |
| `EXPLORAMA_PUBLIC_URL` | `http://localhost` | Browser-facing base URL (oauth2-proxy redirect) |
| `CASDOOR_LOGIN_URL` | `http://localhost:8000/login/oauth/authorize` | Browser-facing Casdoor login URL |
| `CASDOOR_ISSUER_URL` | `http://casdoor:8000` | OIDC issuer oauth2-proxy validates against |
| `CASDOOR_ORIGIN` | `http://localhost:8000` | Casdoor public origin (sets JWT `iss`) |
| `OAUTH2_PROXY_COOKIE_SECURE` | `false` | Set `true` when serving HTTPS |
| `OAUTH2_PROXY_SKIP_OIDC_DISCOVERY` | `false` | Set `true` in prod (uses explicit internal endpoints) |
| `OAUTH2_PROXY_SKIP_ISSUER_VERIFICATION` | `true` | Set `false` in prod once issuer matches |
```

- [ ] **Step 2: Add an "HTTPS (production)" section to `docker/README.md`**

Insert this section immediately before the existing "## Local Backend Reachability" section:

```markdown
## HTTPS (production)

The harness serves plain HTTP on `localhost` by default. To enable HTTPS with
automatic Let's Encrypt certificates, deploy on a host with two public DNS
records (one for the app, one for Casdoor) and inbound ports 80 and 443 open,
then:

1. Copy the production template and edit the hosts and secrets:

   ```bash
   cp .env.production.example .env
   ```

   Set `EXPLORAMA_APP_HOST`/`CASDOOR_SITE_ADDRESS` to your hostnames and change
   `CASDOOR_CLIENT_ID`, `CASDOOR_CLIENT_SECRET`, and `OAUTH2_PROXY_COOKIE_SECRET`.

2. Register the production OAuth callback in Casdoor. Because `init_data.json`
   only seeds on first run, either add
   `https://<app-host>/oauth2/callback` to the application's redirect URIs in
   `docker/casdoor/init_data.json` **before** the first `docker compose up`, or
   add it later in the Casdoor admin UI (Applications → Explorama Dev).

3. Start the stack:

   ```bash
   docker compose up -d
   ```

Caddy serves both hostnames on port 443 and provisions/renews certificates
automatically (certs persist in the `caddy_data` volume). Caddy uses an
anonymous ACME account; to receive expiry notices, add an `email you@example.com`
line to the global block in `docker/caddy/Caddyfile`. While testing, point
Caddy at the Let's Encrypt staging endpoint to avoid rate limits by adding
`acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` to the global
block.
```

- [ ] **Step 3: Update the Production Notes list in `docker/README.md`**

Replace the existing "## Production Notes" list items with:

```markdown
- HTTPS with automatic Let's Encrypt certificates is available — see
  [HTTPS (production)](#https-production).
- Replace development secrets and default users.
- `OAUTH2_PROXY_COOKIE_SECURE=true` is set automatically by the production env
  template.
- Decide whether Explorama services run as compose services or external
  upstreams.
- Persist and back up Casdoor data (the `casdoor_data` volume).
```

- [ ] **Step 4: Add a short HTTPS note to `bundles/server/README.md`**

In `README.md`, immediately after the "Detailed Docker documentation lives in [docker/README.md](docker/README.md)." line, add:

```markdown

For HTTPS in production, copy `.env.production.example` to `.env` and set your
hostnames; Caddy provisions Let's Encrypt certificates automatically. See
[docker/README.md](docker/README.md#https-production).
```

- [ ] **Step 5: Commit**

```bash
cd bundles/server
git add README.md docker/README.md
git commit -m "server compose: document HTTPS opt-in and production setup"
```

---

### Task 6: Integration smoke test (dev unchanged + Casdoor origin)

**Files:**
- None (verification only).

**Interfaces:**
- Consumes: the full stack from Tasks 1-4.
- Produces: evidence that dev behavior is unchanged and that `CASDOOR_ORIGIN` / Beego interpolation works end-to-end.

- [ ] **Step 1: Start the infra stack with dev defaults**

Run (ensure no `.env` is present so inline defaults apply):
```bash
cd bundles/server
docker compose up -d
```
Expected: `caddy`, `casdoor`, `oauth2-proxy`, `socat-frontend`, `socat-backend` all start. The host frontend/backend do **not** need to be running for this test.

- [ ] **Step 2: Confirm Caddy app block serves over HTTP**

Run:
```bash
curl -s http://localhost/health
```
Expected: `OK`

- [ ] **Step 3: Wait for Casdoor and confirm it read CASDOOR_ORIGIN**

Casdoor seeds its DB on first run (several seconds), and oauth2-proxy fetches
OIDC discovery from Casdoor at startup — so wait for Casdoor before testing the
gate. Check the OIDC issuer:
```bash
cd bundles/server
until curl -sf http://localhost:8000/.well-known/openid-configuration >/dev/null; do sleep 2; done
curl -s http://localhost:8000/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
```
Expected: `"issuer":"http://localhost:8000"` (proves Beego `${CASDOOR_ORIGIN||...}` interpolation resolved — not the literal template string).

- [ ] **Step 4: Confirm the oauth gate redirects unauthenticated requests**

Now that Casdoor (and therefore oauth2-proxy) is up, retry until the gate
responds (oauth2-proxy may have restarted while waiting for Casdoor):
```bash
until [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost/)" = "302" ]; do sleep 2; done
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" http://localhost/
```
Expected: `302` with a redirect URL containing `/oauth2/sign_in`.

- [ ] **Step 5: Tear down**

```bash
cd bundles/server
docker compose down
```
Expected: all services stop and are removed; named volumes are retained.

- [ ] **Step 6: Final verification of git state**

Run:
```bash
cd /home/soeren/repos/private/Explorama
git status --short bundles/server
git log --oneline -6
```
Expected: a clean working tree for `bundles/server` (all changes committed) and the six task commits present.

---

## Out of scope / future

- `--trusted-proxy-ip` for oauth2-proxy (pre-existing warning, unchanged here).
- Routing Casdoor under a sub-path of a single domain (rejected: fragile).
- Automated seeding of the production redirect URI (would require templating, which this branch removed).
- A full end-to-end login test through a running host app (the server bundle app is still incomplete per CLAUDE.md); the auth harness is verified independently in Task 6.
