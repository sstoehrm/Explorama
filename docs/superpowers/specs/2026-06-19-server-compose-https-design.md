# Opt-in HTTPS for the Server Compose Harness

Date: 2026-06-19
Status: Approved (design); implementation pending
Scope: `bundles/server` Docker compose harness

## Problem

The compose harness (`bundles/server/docker-compose.yml`) is a zero-config local
dev setup: everything runs over `http://localhost`. Caddy has `auto_https off`
and listens on `:80`; oauth2-proxy uses `--cookie-secure=false` and an
`http://localhost` redirect; Casdoor's `origin` is `http://localhost:8000`. The
README's "Production Notes" list "Configure a real hostname and HTTPS in Caddy"
and "Set oauth2-proxy cookies to secure mode" as open TODOs.

We want production-grade HTTPS using Caddy's automatic Let's Encrypt
certificate provisioning, **without** breaking the frictionless localhost dev
flow.

## Goals

- One-variable opt-in: setting a small, consistent set of env vars switches the
  whole stack (Caddy, oauth2-proxy, Casdoor) to HTTPS.
- Zero-config dev unchanged: `docker compose up` with no `.env` behaves exactly
  as today (`http://localhost`, `http://localhost:8000`).
- The full OAuth flow runs over HTTPS in production, including Casdoor's
  browser-facing login page.

## Non-goals

- Reintroducing the entrypoint/templating machinery this branch removed. The one
  value that cannot be env-driven without templating (the Casdoor seed redirect
  URI) is handled by a documented one-time manual step instead.
- A full deployment guide. Docs get concise HTTPS additions only.

## Architecture

Caddy becomes the single public entry point for **both** the app and Casdoor, so
both obtain Let's Encrypt certificates from the same mechanism. The topology is
identical in dev and prod — only hostnames/ports differ via env vars.

```text
                   Caddy
  app host      →  oauth gate → socat-frontend (host frontend)
                              → socat-backend  (host backend)
  casdoor host  →  reverse_proxy casdoor:8000

dev:  app=:80            casdoor=:8000             → plain HTTP, no certs
prod: app=app.example.com casdoor=auth.example.com → Let's Encrypt on :443
```

Caddy derives cert behavior from the site address alone: a bare port (`:80`)
serves plain HTTP and requests no certificate; a hostname enables automatic
HTTPS. Removing the current `auto_https off` is therefore safe — dev keeps bare
ports (no ACME), prod uses hostnames (ACME). Server-to-server OIDC calls (token
redeem, JWKS) stay on the internal Docker network over HTTP in both modes; they
never reach the browser, so they need no TLS.

This changes one thing in dev: Caddy now fronts Casdoor on `:8000` instead of
Casdoor publishing its own host port. `http://localhost:8000` continues to work.

## Environment variable model

Everything derives from the variables below. All default to today's
`http://localhost` values, so dev is unchanged. A new
`.env.production.example` ships the consistent HTTPS set.

| Variable | Dev default | Production example |
|---|---|---|
| `EXPLORAMA_APP_HOST` | `:80` | `app.example.com` |
| `CASDOOR_SITE_ADDRESS` | `:8000` | `auth.example.com` |
| `EXPLORAMA_PUBLIC_URL` | `http://localhost` | `https://app.example.com` |
| `CASDOOR_LOGIN_URL` | `http://localhost:8000/login/oauth/authorize` | `https://auth.example.com/login/oauth/authorize` |
| `CASDOOR_ISSUER_URL` | `http://casdoor:8000` | `https://auth.example.com` |
| `CASDOOR_ORIGIN` | `http://localhost:8000` | `https://auth.example.com` |
| `OAUTH2_PROXY_COOKIE_SECURE` | `false` | `true` |
| `OAUTH2_PROXY_SKIP_OIDC_DISCOVERY` | `false` | `true` |
| `OAUTH2_PROXY_SKIP_ISSUER_VERIFICATION` | `true` | `false` |
| `EXPLORAMA_ACME_EMAIL` | _(empty)_ | `admin@example.com` |

Existing vars (`EXPLORAMA_HTTP_PORT`, `CASDOOR_PORT`/host ports,
`HOST_FRONTEND_PORT`, `HOST_BACKEND_PORT`, client id/secret, cookie secret) are
retained; a new `EXPLORAMA_HTTPS_PORT` (default `443`) is added for the Caddy
HTTPS publish.

The oauth2-proxy redirect URL is derived in-compose as
`${EXPLORAMA_PUBLIC_URL}/oauth2/callback`, so it needs no separate variable.

### Why the issuer-skip hack can be dropped in prod

Today oauth2-proxy sets `--insecure-oidc-skip-issuer-verification=true` because
the internal issuer URL (`http://casdoor:8000`) never matches Casdoor's
browser-facing origin (`http://localhost:8000`). In production:

- Casdoor `origin` = `https://auth.example.com` → JWT `iss` = that URL.
- oauth2-proxy `CASDOOR_ISSUER_URL` = the same `https://auth.example.com`.
- `OAUTH2_PROXY_SKIP_OIDC_DISCOVERY=true` plus explicit internal `redeem-url`
  and `oidc-jwks-url` (`http://casdoor:8000/...`) avoid a public hairpin while
  still validating `iss` against the public issuer.

Issuer verification therefore passes, and `SKIP_ISSUER_VERIFICATION` is set to
`false` in prod. Dev keeps the current behavior (`skip=true`, discovery on).

## File-by-file changes

1. **`docker/caddy/Caddyfile`**
   - Remove `auto_https off` from global options.
   - Add `email {$EXPLORAMA_ACME_EMAIL:}` to global options (optional ACME
     contact). See Verification — confirm an empty value is accepted; if not,
     drop the directive (anonymous ACME) and document setting email another way.
   - App site address: `:80` → `{$EXPLORAMA_APP_HOST::80}`.
   - Add a Casdoor site block `{$CASDOOR_SITE_ADDRESS::8000}` that
     `reverse_proxy casdoor:8000` (with `encode gzip`).

2. **`docker-compose.yml`**
   - Caddy: publish `${EXPLORAMA_HTTPS_PORT:-443}:443` in addition to `:80`;
     reuse the existing `CASDOOR_PORT` var for the dev Casdoor publish
     (`${CASDOOR_PORT:-8000}:8000`, now served by Caddy's Casdoor block instead
     of the Casdoor container); add `casdoor` to `depends_on`.
   - Casdoor: remove the `8000:8000` host port mapping (Caddy fronts it now);
     pass `CASDOOR_ORIGIN` into the container's environment.
   - oauth2-proxy: convert from `command:` flags to `environment:` `OAUTH2_PROXY_*`
     vars so booleans (`COOKIE_SECURE`, `SKIP_OIDC_DISCOVERY`,
     `INSECURE_OIDC_SKIP_ISSUER_VERIFICATION`) toggle cleanly via env. Keep
     internal endpoints (`REDEEM_URL`, `OIDC_JWKS_URL`) as constants pointing at
     `http://casdoor:8000`. Map `OIDC_ISSUER_URL`, `LOGIN_URL`, `REDIRECT_URL`,
     and the boolean toggles to the env vars above.

3. **`docker/casdoor/app.conf`**
   - `origin = http://localhost:8000` → `origin = ${CASDOOR_ORIGIN||http://localhost:8000}`
     (Beego env interpolation). See Verification.

4. **`.env.example`** — add the new vars at their dev defaults.

5. **`.env.production.example`** (new) — the consistent HTTPS set, with secrets
   (client id/secret, cookie secret) clearly marked as must-change.

6. **`README.md` + `docker/README.md`** — concise HTTPS section: the env-var
   table, DNS/port requirements (ports 80 + 443 publicly reachable; one A record
   per host), and the one-time Casdoor redirect-URI step. Convert the relevant
   "Production Notes" TODO lines into short instructions. Mention the Let's
   Encrypt staging endpoint as an optional tip for testing without hitting rate
   limits.

## Casdoor seed redirect URI (the one manual step)

`docker/casdoor/init_data.json` is static seed data written only on first run
(`initDataNewOnly = true`). It seeds the dev callbacks
(`http(s)://localhost/oauth2/callback`). Casdoor validates the OAuth
`redirect_uri` against this list, so production also needs
`https://app.example.com/oauth2/callback` registered.

Per the decision to keep the static seed (honoring this branch's removal of
templating), this is handled by a **documented one-time step**: before the first
`docker compose up`, edit `init_data.json` to add the production callback, **or**
add it afterward in the Casdoor admin UI. Everything else is env-var-driven.

## Testing / verification plan

Dev (must remain unchanged):
- `docker compose up` with no `.env`; `http://localhost` loads through the oauth
  gate; `http://localhost:8000` reaches Casdoor; full login with `dev`/`dev123`
  succeeds.

Production shape (validated as far as possible without a public domain):
- `caddy validate --config docker/caddy/Caddyfile --adapter caddyfile` passes
  for both the dev defaults and a production env set.
- `docker compose config` renders the expected oauth2-proxy env and Caddy
  publishes 80/443.
- Optional local HTTPS smoke test: set the hosts to `localhost`-style values so
  Caddy uses its internal CA, confirming the HTTPS site blocks and oauth2-proxy
  `cookie-secure`/redirect wiring are consistent.

Points to confirm during implementation (Caddy/oauth2-proxy/Casdoor specifics):
- Caddy accepts `email {$EXPLORAMA_ACME_EMAIL:}` with an empty value; fall back
  to omitting the directive if not.
- Beego `${CASDOOR_ORIGIN||default}` interpolation works in Casdoor's `app.conf`.
- oauth2-proxy with `SKIP_OIDC_DISCOVERY=true` + explicit `LOGIN_URL`,
  `REDEEM_URL`, `OIDC_JWKS_URL` validates `iss` against `OIDC_ISSUER_URL`.

## Out of scope / future

- Routing Casdoor under a sub-path of a single domain (rejected: fragile).
- HTTPS-by-default for the harness (rejected: breaks zero-config dev).
- Automated seeding of the production redirect URI (would require templating).
