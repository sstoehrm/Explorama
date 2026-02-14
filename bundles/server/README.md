# Explorama Server Bundle

## Docker Setup

The Dockerfile builds a single container that bundles three services via supervisord:

- **Caddy** -- reverse proxy with automatic HTTPS, handles auth gating
- **oauth2-proxy** -- authenticates users via OpenID Connect
- **Casdoor** -- identity provider (user management, login UI)

On first start the entrypoint generates secrets and seeds Casdoor automatically.

### Prerequisites

- Docker
- The Explorama frontend/backend dev servers running on the host (default port `8020`)

### Build

```bash
docker build -t explorama-server .
```

### Run

```bash
docker run -d \
  --add-host host.docker.internal:host-gateway \
  -p 443:443 -p 8000:8000 \
  -v explorama_data:/data \
  --name explorama \
  explorama-server
```

The container proxies requests to the dev server on the host. Override the upstream with environment variables:

```bash
docker run -d \
  -e HOST_ADDR=host.docker.internal \
  -e HOST_FRONTEND_PORT=8020 \
  ...
```

### Endpoints

| URL | Description |
|---|---|
| `https://localhost` | Application (requires login) |
| `http://localhost:8000` | Casdoor admin panel (default credentials: `admin` / `123`) |

### Data

All persistent state (Casdoor DB, generated secrets, Caddy certificates) is stored in the `/data` volume.

### Docker Compose (development)

A separate docker-compose setup lives in `.clj-kondo/docker/docker-compose.yml`. It runs each service as its own container with socat bridges to the host dev servers. Start it with:

```bash
cd .clj-kondo/docker
docker compose up
```

This variant expects `CASDOOR_CLIENT_ID`, `CASDOOR_CLIENT_SECRET`, and `OAUTH2_PROXY_COOKIE_SECRET` to be set (e.g. in a `.env` file).
