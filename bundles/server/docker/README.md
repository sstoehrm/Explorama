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

The default `CASDOOR_CLIENT_ID` and `CASDOOR_CLIENT_SECRET` must match the first-run seed data in `docker/casdoor/init_data.json`. If you change them after Casdoor has initialized, either update the application in the Casdoor UI or reset the `casdoor_data` volume.

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

- Replace development secrets and default users.
- Configure a real hostname and HTTPS in Caddy.
- Set oauth2-proxy cookies to secure mode.
- Decide whether Explorama services run as compose services or external upstreams.
- Persist and back up Casdoor data.
