# agent-requests plugin — design

## Purpose

A plugin lets other plugins file a *request*: a piece of input data plus a
declared output format. An external agent (an LLM, a script, any software)
pulls open requests from an HTTP API, produces the output, and posts it back.
The requesting plugin receives the result through a handler it registered. A
sidebar shows the current user's open requests.

The first consumer is CSV import: instead of relying only on the heuristic
mapping produced by `de.explorama.shared.data-transformer.suggestions/create`,
the import dialog can ask an agent to generate the data-transformer mapping
descriptor from the head of the uploaded file.

## Scope

- Server bundle only. It is the primary deployment target and the only bundle
  with a Ring/compojure handler.
- The queue, registry and frontend view are bundle-agnostic and can be wired
  into electron and browser later; without an HTTP transport there, requests
  can be filed and seen but not fulfilled. Wiring them into those bundles is
  **not** part of this work.
- No e2e coverage: the e2e suite drives the browser bundle.

## Architecture

Plugin name: `agent-requests`, namespaces
`de.explorama.{backend,frontend,shared}.agent-requests`.

Two registries and one queue:

- **Request types** — declared at backend init by the owning plugin:

  ```clojure
  {:id :data-transformer/mapping
   :description "..."            ; prose the agent reads
   :output-schema <malli schema> ; validated on submit
   :output-example <edn>         ; a valid answer, for the agent to imitate
   :on-fulfilled (fn [request result] ...)}
  ```

- **Requests** — runtime instances:

  ```clojure
  {:id #uuid "..."
   :type :data-transformer/mapping
   :input {...}
   :status :open
   :created-at <ms>
   :expires-at <ms>
   :claimed-by nil
   :lease-expires-at nil
   :rejections 0
   :result nil
   :user <user-id>}
  ```

  held in an atom with a sweeper for leases and TTLs.

### Files

| Path | Contents |
|---|---|
| `plugins/shared/de/explorama/shared/agent_requests/ws_api.cljc` | route keywords for the frontend view |
| `plugins/shared/de/explorama/shared/agent_requests/schema.cljc` | malli schemas for a type declaration and a request record |
| `plugins/backend/de/explorama/backend/agent_requests/registry.cljc` | `register-type!`, lookup, listing |
| `plugins/backend/de/explorama/backend/agent_requests/queue.cljc` | `create!`, `list-open`, `claim!`, `submit!`, `fail!`, `cancel!`, sweeping |
| `plugins/backend/de/explorama/backend/agent_requests/api.cljc` | ws handlers for the sidebar (list, cancel) |
| `plugins/backend/de/explorama/backend/agent_requests/backend.cljc` | `init`, called from `bundles/server/backend/de/explorama/backend/woco/app/core.clj` |
| `plugins/frontend/de/explorama/frontend/agent_requests/core.cljs` | plugin registration, events |
| `plugins/frontend/de/explorama/frontend/agent_requests/path.cljs` | db paths |
| `plugins/frontend/de/explorama/frontend/agent_requests/views/sidebar.cljs` | the open-requests view |
| `plugins/backend/de/explorama/backend/agent_requests/auth.cljc` | pluggable auth gate; denies until a bundle installs an authenticator |
| `bundles/server/backend/de/explorama/backend/agent_requests/http.clj` | compojure routes, mounted in `handler.clj` |
| `bundles/server/backend/de/explorama/backend/agent_requests/proxy_auth.clj` | server authenticator reading the proxy-asserted principal |

`agent-requests` knows nothing about CSV or mappings; `expdb` knows nothing
about HTTP.

### Flow (mapping use case)

1. The import dialog gains a "Generate mapping with agent" button in the
   mapping step; it dispatches a new expdb ws route `request-mapping`.
2. The expdb backend already holds the raw file content in the `files` atom in
   `plugins/backend/de/explorama/backend/expdb/temp_import/api.cljc`. It builds
   the input payload and calls `queue/create!`.
3. The agent polls `GET /api/agent-requests` (optionally long-polling), claims
   a request, produces the mapping, and posts the result.
4. The queue validates the result against the type's malli schema.
5. On success `:on-fulfilled` runs in the expdb backend, which dispatches the
   mapping to the waiting client. The dialog applies it through the same path
   the heuristic suggestion already uses.

A request carries a `:context` map supplied by its creator — for the mapping
type this holds the websocket callback closure used to answer the waiting
client, plus the file name. `:context` never leaves the process: the HTTP layer
exposes only `:id`, `:type`, `:input` and `:created-at`.

### Input payload for `:data-transformer/mapping`

```clojure
{:file-name "cases.csv"
 :raw-head ["Datum;Land;Fälle" "01.02.2021;Germany;12" ...]  ; first N raw lines
 :meta-data {:file-format :csv :csv {:separator ";" :quote "\""}}}
```

`N` is configurable, default 20 lines. The output format is the
data-transformer mapping descriptor, declared through the schema described
below.

## HTTP API

Base path `/api/agent-requests`. Wire format is EDN only
(`application/edn`) in both directions: the mapping descriptor is EDN-shaped
(`{:value [:date-schema "dd.MM.YYYY" [:field "Datum"]]}`) and a JSON round trip
would lose the keywords.

Every endpoint requires an authenticated principal (see Auth below).

| Method | Path | Purpose |
|---|---|---|
| GET | `/types` | declared request types: id, description, output schema, example |
| GET | `/` | open requests; `?type=<id>` filters, `?wait=<sec>` long-polls (0 = immediate, capped at 30) |
| POST | `/:id/claim` | 200 with `{:lease-expires-at <ms>}`, 409 if held by someone else |
| POST | `/:id/result` | body is the result; 200 on accept, 422 with the malli explanation on mismatch |
| POST | `/:id/fail` | `{:reason "..."}`; the agent gives up and the reason surfaces in the dialog |

Long-polling registers a callback on the queue and answers through http-kit's
async channel, so a waiting agent costs a socket rather than a worker thread.

### Auth

Authentication is a **pluggable gate**. The plugin layer defines the contract
and denies every request until a bundle installs an authenticator, so a bundle
that forgets to install one is closed rather than open. Each bundle then
supplies the mechanism that fits it.

The server bundle runs behind a reverse proxy (Casdoor fronting the
deployment) that authenticates the caller and authorizes the route before the
request arrives. Its authenticator therefore reads the principal the proxy
asserts in `X-Auth-Request-User` — no identity-provider round trip, no token
verification, and no dependency on the `rights-roles` tree. A request without
that header is refused with 401 even so, so bypassing the proxy does not reach
the API. `EXPLORAMA_AGENT_REQUESTS_PRINCIPALS` optionally restricts which
principals may use this API; empty means any principal the proxy let through,
and a principal outside a non-empty list gets 403.

Only part of the authorization therefore lives in this code: the proxy owns
authentication, the application owns the request-scoped checks.

The electron bundle will later install a different authenticator, built on a
credential it generates at startup. That is out of scope here — only the gate
is built now.

## Lifecycle

States: `:open → :claimed → :fulfilled | :failed`, plus `:expired` and
`:cancelled`.

- **Claim lease**, default 60 s: a claim hides the request from other agents.
  If no result arrives before the lease expires the request returns to
  `:open`, so a crashed agent does not strand work.
- **Request TTL**, default 15 min from creation: the request becomes
  `:expired`, `:on-fulfilled` never runs, and the waiting dialog reports a
  timeout.
- **Cancel**: closing or cancelling in the import dialog cancels the request,
  which leaves the open list immediately.
- Both durations and the raw-head line count are configuration values.

## Frontend

**Sidebar tool** — registered in the bottom bar in the same way data-atlas
registers its tool. Lists the current user's requests with type label, status
badge, age, holder for claimed requests, and a cancel action; shows an empty
state when nothing is open. The sidebar fetches the list over the websocket
when it opens and refreshes it every few seconds while it stays open; the
refresh stops when it closes. Broadcasting instead would push one user's
requests to every connected client, and per-tube filtering exists only in the
server bundle's `frontend-api`, which the bundle-agnostic plugin code cannot
rely on.

The list carries metadata only — never the input payload or the result.
Requests are scoped to the user who created them. The technical agent user
reaching the queue over HTTP sees all of them, which is the point.

**Import dialog** (`plugins/frontend/de/explorama/frontend/expdb/temp_import/core.cljs`)
— a "Generate mapping with agent" button in the mapping step. Clicking it puts
the dialog into a pending state showing a spinner, the request's age and a
cancel button. The heuristic mapping stays visible while waiting; when the
agent's mapping arrives it replaces it, and the user reviews it exactly as
today.

Failure, timeout, agent-reported failure and schema rejection all clear the
pending state and show a `hint` explaining what happened, with the heuristic
mapping still in place to work from.

## Error handling

- Schema mismatch on submit returns 422 with the malli explanation; the request
  stays claimed so the agent can correct and resubmit within its lease. After
  3 rejections it becomes `:failed` carrying the last explanation, which
  surfaces in the dialog.
- The registered output schema for the mapping type *is* the data-transformer
  schema — `de.explorama.shared.data-transformer.schema/import-schema`, which
  is already public — so there is one contract rather than two, and the queue's
  validation gate and the importer's `explain` cannot drift apart.
- Claim on an already-claimed request → 409; unknown id → 404;
  expired or cancelled → 410; missing or invalid token → 401; valid token for
  the wrong principal → 403.
- A backend restart drops the queue, which is in memory by decision. Waiting
  dialogs receive no answer and clear on their own client-side timeout with an
  explanatory hint.
- Long-poll is capped at 30 s and returns an empty list rather than hanging.

## Testing

Backend tests run in the server bundle's Clojure suite
(`clojure -Sdeps "$(cat clj.deps.edn)" -M:test`), frontend tests in its
ClojureScript suite (`clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci`).

- Queue state machine as pure unit tests: create → claim → submit, lease expiry
  returning a request to open, TTL expiry, cancel, double-claim, rejection
  counter. Time is injected rather than read from the clock, so the tests need
  no sleeps.
- Registry: a type declaration missing a schema or a handler is rejected at
  init.
- HTTP layer with `ring-mock`, already a dependency in
  `bundles/server/clj.deps.edn`: every status code listed above, plus long-poll
  returning immediately when work already exists.
- Frontend re-frame tests: pending state set on request, cleared on result,
  failure and timeout, and the arriving mapping landing in the same db path the
  heuristic result uses.
- One round-trip test driving the HTTP API: a filed mapping request is pulled,
  claimed and answered, the waiting client's callback receives the mapping, and
  that mapping is asserted to satisfy the data-transformer schema.

## Documentation

A short document with `curl` examples for each endpoint, the EDN shapes, and
the auth setup — nobody can write an agent against this API without one.
