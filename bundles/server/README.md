# Explorama Server Bundle

The server bundle is the JVM backend plus browser frontend deployment model. Local server development is still incomplete, but the Docker support now provides a compose-based auth and routing harness for developing the app with production-like Caddy and Casdoor behavior.

## Docker Compose Harness

The supported Docker path is `docker-compose.yml`. It runs the infrastructure services as separate containers and forwards application traffic to services running on the host:

- **Caddy**: public HTTP entry point and auth gate
- **Casdoor**: identity provider and login UI
- **oauth2-proxy**: OIDC bridge between Caddy and Casdoor
- **socat**: TCP bridges from the compose network to local frontend/backend ports

Start the harness:

```bash
cd bundles/server
docker compose up
```

Then run the local development services in another terminal. The backend must bind to an address Docker can reach:

```bash
cd bundles/server
npm install
bb gather-assets.bb.clj dev
EXPLORAMA_BIND_ADDRESS=0.0.0.0 clj -M:dev
```

Access:

| URL | Description |
|---|---|
| `http://localhost` | Explorama through Caddy and oauth2-proxy |
| `http://localhost:8000` | Casdoor admin UI |

Seeded credentials:

| Service | Username | Password |
|---|---|---|
| Casdoor admin | `admin` | `123` |
| Explorama dev user | `dev` | `dev123` |

Detailed Docker documentation lives in [docker/README.md](docker/README.md).

To run the app itself in containers (instead of bridging to host dev servers),
use full mode: `docker compose -f docker-compose.yml -f docker-compose.full.yml up --build`.
See [docker/README.md](docker/README.md#full-mode-run-the-app-in-containers).

For HTTPS in production, copy `.env.production.example` to `.env` and set your
hostnames; Caddy provisions Let's Encrypt certificates automatically. See
[docker/README.md](docker/README.md#https-production).

## Tests

```bash
clj -M:test
clj -M:test-ci
```
