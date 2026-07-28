# Agent request API

Plugins file requests carrying input data and a declared output format. An
agent pulls open requests from this API, produces the output and posts it back.
The API is served by the server bundle under `/api/agent-requests` and speaks
EDN in both directions.

## Authentication

The API is served behind the server bundle's Docker harness: Caddy is the
public entry point and auth gate, `forward_auth`-ing every request to
oauth2-proxy, which validates the session against Casdoor (the identity
provider) before the request is ever forwarded to the backend. The
application itself reads the principal the proxy asserts:

```
X-Auth-Request-User: <principal>
```

A request without that header is refused with 401 — the application never
trusts an unauthenticated caller even if the proxy is bypassed.
`EXPLORAMA_AGENT_REQUESTS_PRINCIPALS` names the principals allowed on this
API. It is empty by default, which denies everyone: the API is inert until a
deployment names at least one principal. An authenticated but unlisted
principal is refused with 403, not 401. The header name is configurable
through `EXPLORAMA_AGENT_REQUESTS_PRINCIPAL_HEADER`.

Authentication is a pluggable gate: `de.explorama.backend.agent-requests.auth`
denies every request by default, and each bundle installs its own
authenticator — the server bundle's
`de.explorama.backend.agent-requests.proxy-auth` is the implementation
described above. This means the electron bundle can later authenticate
against a credential it generates at startup without any change to the
routes or the store.

**The security of this API depends entirely on the backend being unreachable
except through that authenticating proxy** — the application trusts the
header at face value and performs no verification of its own. See
`bundles/server/docker/README.md` (Production Notes) for the deployment
trust contract this relies on: which component strips and re-sets the
header, and why the backend must never be directly reachable.

## Configuration

| Environment variable | Default | Meaning |
|---|---|---|
| `EXPLORAMA_AGENT_REQUESTS_PRINCIPALS` | `#{}` | allowed principals; empty denies everyone and the api stays inert |
| `EXPLORAMA_AGENT_REQUESTS_PRINCIPAL_HEADER` | `x-auth-request-user` | header carrying the proxy-asserted principal |
| `EXPLORAMA_AGENT_REQUESTS_LEASE_MS` | `60000` | claim lease |
| `EXPLORAMA_AGENT_REQUESTS_TTL_MS` | `900000` | request lifetime |
| `EXPLORAMA_AGENT_REQUESTS_MAX_REJECTIONS` | `3` | schema rejections before a request fails |
| `EXPLORAMA_AGENT_REQUESTS_MAX_WAIT` | `30` | long-poll cap in seconds |
| `EXPLORAMA_AGENT_REQUESTS_RAW_HEAD_LINES` | `20` | raw csv lines sent with a mapping request |

## Endpoints

`$EXPLORAMA_URL` in the examples is the public entry point — Caddy, e.g.
`http://localhost` for the dev harness. Never call the backend's own port
(`:4001` in the harness) directly: nothing in front of it authenticates, so a
caller reaching it can set `X-Auth-Request-User` itself and impersonate any
principal (`bundles/server/docker/README.md`, Production Notes). Caddy strips
any client-supplied `X-Auth-Request-User` and oauth2-proxy sets it from the
authenticated session, so the header shown below is what the backend receives,
not something the agent gets to choose — an agent presents whatever credential
the proxy is configured to accept.

### List the declared request types

```bash
curl -s -H "X-Auth-Request-User: $PRINCIPAL" \
     $EXPLORAMA_URL/api/agent-requests/types
```

```clojure
{:types [{:id :data-transformer/mapping
          :description "Produce a data-transformer mapping descriptor ..."
          :output-schema [:schema {:registry {...}} ...]
          :output-example {:meta-data {...} :mapping {...}}}]}
```

### Pull open requests

```bash
curl -s -H "X-Auth-Request-User: $PRINCIPAL" \
     "$EXPLORAMA_URL/api/agent-requests?type=:data-transformer/mapping&wait=30"
```

`wait` long-polls for up to that many seconds (capped at
`EXPLORAMA_AGENT_REQUESTS_MAX_WAIT`; `0` or an unparseable/negative value
returns immediately). The answer is a possibly empty list, and each entry
carries only its id, type, input and creation time — nothing about who filed
it or its internal status:

```clojure
{:requests [{:id "6f1c..."
             :type :data-transformer/mapping
             :created-at 1769500000000
             :input {:file-name "cases.csv"
                     :raw-head ["id;country;date;cases" "1;Germany;01.02.2021;12"]
                     :meta-data {:file-format :csv :csv {:separator ";" :quote "\""}}}}]}
```

### Claim a request

```bash
curl -s -X POST -H "X-Auth-Request-User: $PRINCIPAL" \
     -H "Content-Type: application/edn" \
     -d '{}' \
     $EXPLORAMA_URL/api/agent-requests/6f1c.../claim
```

The claim is held by the authenticated principal; the body carries nothing the
server trusts. `200 {:lease-expires-at 1769500060000}`, `409` if another agent holds it,
`404` for an unknown id, `410` if it is already finished or cancelled. The
claim must be renewed by submitting before the lease expires; otherwise the
request returns to the open list for another agent to pick up.

### Submit the result

```bash
curl -s -X POST -H "X-Auth-Request-User: $PRINCIPAL" \
     -H "Content-Type: application/edn" \
     -d @mapping.edn \
     $EXPLORAMA_URL/api/agent-requests/6f1c.../result
```

`200 {:status :fulfilled}` on acceptance. Submitting without holding the
claim — including as a different principal than the one that claimed it — is
`409`. A result that does not satisfy the type's schema comes back
as `422` with the reason, and may be corrected and resubmitted while the
lease holds:

```clojure
{:error :invalid
 :explanation {:mapping {:items [{:features ["missing required key"]}]}}}
```

After `EXPLORAMA_AGENT_REQUESTS_MAX_REJECTIONS` rejections the request is
failed and the user is told.

### Give up

```bash
curl -s -X POST -H "X-Auth-Request-User: $PRINCIPAL" \
     -H "Content-Type: application/edn" \
     -d '{:reason "cannot infer a date column"}' \
     $EXPLORAMA_URL/api/agent-requests/6f1c.../fail
```

`200 {:status :failed}`, and `409` from anyone but the claim holder. The
reason is shown to the waiting user.

## The mapping request type

The input holds the first lines of the raw csv file and its meta-data. The
output is a data-transformer mapping descriptor: the `:meta-data` from the
input plus a `:mapping` naming the datasource and describing one item with its
`:facts`, `:locations`, `:contexts`, `:dates` and `:texts`. Field references
are `[:field "column"]`, constants are `[:value "x"]`, dates carry their format
as `[:date-schema "dd.MM.YYYY" [:field "column"]]`. Fetch
`/api/agent-requests/types` for the authoritative schema and a full example.
