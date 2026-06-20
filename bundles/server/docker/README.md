# Explorama Server Docker Compose

This directory supports the compose-based server harness. It intentionally does not build or run the Explorama server inside a single container. Instead, Docker provides auth and routing while the frontend/backend services can run locally for normal development.

## Architecture

```text
Browser
  |
  v
Caddy (:80)
  |
  +-- /oauth2/* -> oauth2-proxy (:4180)
  +-- /api/*    -> socat-backend (:4001) -> host backend
  +-- /ws*      -> socat-backend (:4001) -> host backend
  +-- /*        -> socat-frontend (:8020) -> host frontend/Figwheel

oauth2-proxy -> Casdoor (:8000)
```

Services:

- `caddy`: public entry point, auth gate, frontend/backend routing.
- `casdoor-init`: one-shot service that fixes `casdoor_data` volume ownership (uid 1000) before Casdoor starts, then exits.
- `casdoor`: identity provider seeded from `docker/casdoor/init_data.json`.
- `oauth2-proxy`: validates sessions with Casdoor and exposes `/oauth2/*`.
- `socat-frontend`: forwards compose traffic to the host frontend port.
- `socat-backend`: forwards compose traffic to the host backend port.

## Quick Start

Start the Docker harness:

```bash
cd bundles/server
docker compose up
```

Start local Explorama services in a second terminal:

```bash
cd bundles/server
npm install
bb gather-assets.bb.clj dev
EXPLORAMA_BIND_ADDRESS=0.0.0.0 clj -M:dev
```

Open `http://localhost`.

## Credentials

Casdoor is seeded on first startup:

| Account | Username | Password |
|---|---|---|
| Casdoor admin | `admin` | `123` |
| Explorama dev user | `dev` | `dev123` |

Casdoor is available at `http://localhost:8000`.

## Configuration

Copy `.env.example` to `.env` when you need custom local ports or development secrets:

```bash
cp .env.example .env
```

Supported variables:

| Variable | Default | Purpose |
|---|---|---|
| `EXPLORAMA_HTTP_PORT` | `80` | Host port for Caddy |
| `CASDOOR_PORT` | `8000` | Host port for Casdoor |
| `HOST_FRONTEND_PORT` | `8020` | Host frontend/Figwheel port |
| `HOST_BACKEND_PORT` | `4001` | Host backend port |
| `CASDOOR_CLIENT_ID` | `explorama-dev-client-id` | oauth2-proxy client ID |
| `CASDOOR_CLIENT_SECRET` | `explorama-dev-client-secret` | oauth2-proxy client secret |
| `OAUTH2_PROXY_COOKIE_SECRET` | development secret | oauth2-proxy cookie encryption secret |
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

The default `CASDOOR_CLIENT_ID` and `CASDOOR_CLIENT_SECRET` must match the first-run seed data in `docker/casdoor/init_data.json`. If you change them after Casdoor has initialized, either update the application in the Casdoor UI or reset the `casdoor_data` volume.

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

## Local Backend Reachability

The backend must listen on an address reachable from Docker. Use:

```bash
EXPLORAMA_BIND_ADDRESS=0.0.0.0 clj -M:dev
```

The compose file maps `host.docker.internal` to Docker's host gateway for Linux. Docker Desktop already provides this host name on macOS and Windows.

## Reset State

Casdoor stores its SQLite database in the `casdoor_data` volume. Caddy stores runtime data in `caddy_data` and `caddy_config`.

To reset all Docker-managed state:

```bash
cd bundles/server
docker compose down -v
```

## Production Notes

This compose file is a development harness and a starting point for a real deployment. Before production use:

- HTTPS with automatic Let's Encrypt certificates is available — see
  [HTTPS (production)](#https-production).
- Replace development secrets and default users.
- `OAUTH2_PROXY_COOKIE_SECURE=true` is set automatically by the production env
  template.
- Decide whether Explorama services run as compose services or external
  upstreams.
- Persist and back up Casdoor data (the `casdoor_data` volume).
