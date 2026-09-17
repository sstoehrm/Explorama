# Agent Gateway

An HTTP API through which an external agent (Claude Code, driven by project
skills) inspects and operates a user's running Explorama session, imports
data, and authors indicator graphs. Server bundle only. Figure:
`.blend/specs/2026-09-17-agent-gateway.edn`.

This is the inverse of the removed agent-requests component: Explorama no
longer files work for an agent; the agent drives Explorama.

## Decisions

| Decision | Choice |
|---|---|
| Bundle | Server bundle only. Shared declarations and frontend handlers compile in every bundle; only the server bundle mounts the HTTP entry point. No availability flag. |
| API shape | Curated operation registry with malli schemas, discoverable through the API. No raw event bridge. |
| Identity | A service principal from an allow-list, asserted by the proxy, names the target user per request. |
| Skills | Committed under a top-level `agent/` folder and rendered from the catalog; a test fails on drift. |
| Delivery | One PR stacked on #139, including the merge of PR #118 with its agent path rewired. |

## Plugin layout

`agent-gateway` is a plugin with the usual three parts:

- `plugins/shared/de/explorama/shared/agent_gateway/` — `catalog.cljc`
  (aggregation of every plugin's declarations), `errors.cljc` (error
  vocabulary), `ws_api.cljc` (the relay's two websocket events).
- `plugins/backend/de/explorama/backend/agent_gateway/` — `dispatcher.cljc`
  (validation, backend-op registry, routing to the relay), `relay.cljc`
  (session lookup, pending requests, timeouts), `backend.cljc` (init:
  registers the relay's websocket route).
- `plugins/frontend/de/explorama/frontend/agent_gateway/` — `registry.cljs`
  (frontend-op registry and the command handler), `core.cljs` (init).
- `bundles/server/backend/de/explorama/backend/agent_gateway/http.clj` — the
  compojure routes, principal check, EDN encoding.

Every plugin that exposes operations owns:

- `plugins/shared/de/explorama/shared/<plugin>/agent_ops.cljc` — declarations.
- `plugins/frontend/de/explorama/frontend/<plugin>/agent_ops.cljs` — handlers
  for frontend-side ops, registered from the plugin's init.
- `plugins/backend/de/explorama/backend/<plugin>/agent_ops.cljc` — handlers
  for backend-side ops, registered from the plugin's backend init.

## Operation catalog

A declaration is a map:

```clojure
{:op :mosaic/operation
 :doc "Run one mosaic toolbar operation on a frame."
 :side :frontend            ; or :backend
 :input [:map [:frame-id :any] [:action keyword?] [:params {:optional true} map?]]
 :output [:map [:state map?]]
 :timeout-ms 30000}         ; optional, default 30000
```

`catalog.cljc` requires every `agent_ops.cljc` and exposes `ops` (the merged
map keyed by `:op`), `declaration`, and `explain-input`. Op names are
namespaced by plugin. The catalog is the single source for validation,
discovery and skill rendering; it never needs a live session.

Rules for declarations:

- Input and output schemas are closed maps. Optional keys are marked.
- Frame ids are declared `:any` and documented as opaque values the agent
  passes back verbatim. The API never asks the agent to construct one.
- Every write op returns the same shape as the matching read op, so the
  effect is visible without a second call.

## HTTP surface

Mounted in `bundles/server/backend/de/explorama/backend/handler.clj`, EDN in
and out (`application/edn`).

| Route | Purpose |
|---|---|
| `GET /api/agent/ops` | The catalog: every declaration with schemas rendered as EDN. |
| `GET /api/agent/sessions?user=<name>` | The user's connected sessions: `client-id`, connected-at, role. |
| `POST /api/agent/ops/<plugin>/<op>` | Invoke. Body `{:user "alice" :client-id "..." :params {...}}`. `client-id` is optional when the user has exactly one session; backend ops ignore it. |

Responses are `{:status :ok :result ...}` or `{:status :error :error {:type
<kw> :message <str> ...}}`.

### Auth

The principal is the value of the header named by
`EXPLORAMA_AGENT_GATEWAY_PRINCIPAL_HEADER` (default `x-auth-request-user`),
asserted by Caddy through oauth2-proxy. The Caddyfile already strips
client-supplied copies of the header before forward-auth, and the compose
file already sets `OAUTH2_PROXY_SET_XAUTHREQUEST`. The principal must be in
`EXPLORAMA_AGENT_GATEWAY_PRINCIPALS` (an EDN set); an empty or unset
variable denies every request, so the API is inert until configured. The
backend must not be reachable except through Caddy; the docker README keeps
that warning.

The target user is not checked against the principal: any allow-listed
principal may target any user. The allow-list is the trust boundary.

## Relay

Frontend-side ops run in the target user's browser session.

**Tube identity.** The server handler passes the websocket query parameters
(`username`, `role`, `client-id`) as the tube's initial data instead of
dropping them. The frontend already sends them. Nothing else about the
websocket changes.

**Sending.** `relay/invoke!` resolves the target tube at send time by
`client-id`, or by `username` when it is unique. It stores a promise under a
fresh request id, dispatches `[ws-api/command request-id op params]` to the
tube, and derefs the promise with the op's timeout. A websocket route
`ws-api/result` delivers `[request-id response]` to the promise and removes
it. Timed-out requests are removed, and a `:tube/on-destroy` handler removes
every pending request of that tube with a `:no-session` error.

**Receiving.** The frontend command handler looks the op up in the frontend
registry, refuses when the interaction mode is not `:normal`, and calls the
handler with the params and a `reply` function. Synchronous handlers return
the result; asynchronous handlers call `reply` from the completion event the
plugin already uses for replay, then the registry sends
`[ws-api/result request-id response]` over the backend tube. Handlers run
inside a re-frame event, so they read the db they are given and dispatch
whatever the op needs.

## Errors

`errors.cljc` defines the vocabulary; the HTTP layer maps it to statuses.

| Type | Status | When |
|---|---|---|
| `:unauthorized` | 403 | principal missing or not allow-listed |
| `:unknown-op` | 404 | op not in the catalog |
| `:invalid-params` | 400 | malli explanation in `:explain` |
| `:no-session` | 409 | user has no connected session, or it went away mid-request |
| `:ambiguous-session` | 409 | several sessions and no `client-id`; candidates in `:sessions` |
| `:workspace-busy` | 409 | interaction mode not `:normal` (replay, read-only) |
| `:timeout` | 504 | op did not answer within its timeout |
| `:op-failed` | 500 | handler raised; plugin message in `:message` |

## Woco ops

Frontend side, built on `de.explorama.frontend.woco.frame.api` and the
frame-info api.

Read:

- `:woco/frames` — every frame: `id`, `vertical`, `type`, `title`, `left`,
  `top`, `width`, `height`, `z-index`, `minimized?`, `maximized?`, `di`,
  `published-by`, `color-group`.
- `:woco/connections` — edges `{:from :to :kind}` where `kind` is
  `:publishes` (data-instance lineage) or `:coupled`.
- `:woco/frame-state` — one frame's `:vis-desc` query result without the
  screenshot.
- `:woco/workspace` — interaction mode, workspace id, viewport, loaded
  project id, unsaved flag.
- `:woco/markers` — the pointer tool's markers.

Write:

- `:woco/open-vertical` `{:vertical :source-frame-id :position :opts}` —
  dispatches the vertical's `:visual-option` event with placement forced to
  the provided position.
- `:woco/connect` `{:source :target}` — the programmatic connect variant.
- `:woco/set-geometry` `{:frame-id :left :top :width :height}`,
  `:woco/set-title`, `:woco/minimize`, `:woco/maximize`, `:woco/normalize`,
  `:woco/bring-to-front`, `:woco/close`.
- `:woco/clear-markers`.

## Pointer tool

A woco header tool toggles marker mode. In marker mode a click on a frame
header toggles a marker on that frame; marked frames show a badge with an
optional one-line note editable from the badge. Markers live at
`[:woco :markers]` as `{frame-id {:note "" :set-at ms}}`. They are not
logged to the project event log, not synced, cleared by clean-workspace and
when their frame closes. `:woco/markers` returns them joined with the
frame's title and vertical.

## Vertical ops

One rule: wrap the data-only event the plugin already uses internally, never
a toolbar component. Async ops answer from the plugin's completion event.

| Plugin | Side | Read | Write |
|---|---|---|---|
| search | frontend | `attributes` (with values) for a frame, `form-state`, `data-instance` | `open`, `set-form`, `add-rows`, `run` (submit, answers on di created), `open-visualization` |
| table | frontend | `state` | `open`, `set-state`, `apply-constraints` |
| mosaic | frontend | `state`, `layouts` | `open`, `operation` (the nineteen execute-wrapper actions), `set-layouts`, `remove-layout`, `filter` |
| map | frontend | `state` | `open`, `operation` (the twelve execute-wrapper actions) |
| charts | frontend | `state` | `open`, `set-state`, `apply-constraints` |
| algorithms | frontend | `state` | `open`, `set-value` (ui-value-changed), `submit-task` |
| data atlas | frontend | none | `open` |
| reporting | backend | `dashboards`, `reports` | `create-dashboard`, `update-dashboard`, `delete-dashboard`, same three for reports |
| configuration | backend and frontend | `config`, `labels`, `theme`, `languages` (frontend registry services) | `set-language` (frontend), `set-user-setting` (backend persistence) |
| projects | backend and frontend | `current` (name, description, steps, unsaved?; frontend), `list` (backend) | `create`, `load`, `load-step` (frontend, they need the session's workspace); `rename`, `set-description` (backend, over the update-project-detail route). No save. |
| indicator | backend and frontend | `graphs`, `graph` | `open`, `connect`, `validate-graph`, `create-graph`, `update-graph`, `delete-graph`, `publish-graph` |
| expdb (import gateway) | backend | `buckets` | `upload`, `set-options`, `set-mapping`, `commit`, `cancel`, `delete` |

Known limits, stated in the skills: table and charts state are the plugins'
own positional encodings; data atlas has nothing to read back.

## Import gateway

Backend ops in expdb over `de.explorama.backend.expdb.temp-import.api`:

- `upload` `{:file-name :content :csv {:separator :quote}}` returns the
  analysis the dialog shows: columns, heuristic mapping suggestion, warnings.
- `set-options` changes csv options or the datasource name; returns the
  refreshed suggestion.
- `set-mapping` validates a full mapping descriptor against
  `de.explorama.shared.data-transformer.schema`, stages it, and returns the
  validation result plus a row preview.
- `commit` imports the staged mapping and returns the import summary.
- `cancel` and `delete` discard the staged file.
- `buckets` lists expdb buckets.

The temp-import api stages files in a process-wide atom keyed by file name.
The ops stage under `[user file-name]` so agent imports and dialog imports
do not collide; the dialog path is unchanged. `commit` writes to the shared
database; the skill instructs the agent to confirm with the user first.

## Indicator and PR #118

`origin/worktree-indicator-graph-aggregation` merges onto this branch
without textual conflicts. Kept: the shared graph format
(`de.explorama.shared.data-format.graph`), persisted graphs and their ws
routes, the editor, the overview changes, the e2e spec. Removed as part of
the merge, because their dependency no longer exists and the gateway
replaces the flow: `graph_request.cljc`, `graph_request_test.cljc`, the
`request-graph-generation` route and result events, the flag-gated
generate section and proposal pane in the graph editor, the related
translations, and the corresponding paragraphs of
`docs/superpowers/specs/2026-08-08-indicator-graph-aggregation-design.md`.

The gateway's indicator ops call the persistence and calculate namespaces
directly: `validate-graph` runs `graph/validate` against the bound datasets,
`create-graph` and `update-graph` recompile on save as the ws routes do,
`publish-graph` creates and publishes the data instance. The agent writes
the graph text itself.

## Skills and CLI folder

```
agent/
  README.md                       ; connecting: base URL, principal, target user
  .claude/skills/
    explorama/SKILL.md            ; entry skill, from a hand-written template
    explorama-woco/SKILL.md       ; rendered
    explorama-search/SKILL.md     ; rendered, one per plugin with ops
    ...
```

The entry skill covers: authenticate, list sessions and pick one, read
before write, check `:woco/workspace` and `:woco/markers` first, confirm
with the user before `expdb/commit`, and the error vocabulary. Rendered
skills list each op with its doc, an EDN request example derived from the
input schema, and the response shape.

Rendering: `bundles/server` gets a clj alias `:agent-skills` running
`de.explorama.backend.agent-gateway.skills/render!`; `bb.edn` gets a task
`agent-skills` wrapping it. A server backend test renders into memory and
compares with the committed files.

PR #2's developer skills under `skills/` are a different audience and stay
separate.

## Testing

- Shared: catalog test — unique op names, valid schemas, every op has a
  `:side`.
- Server backend: http tests with ring-mock for auth, catalog, sessions,
  validation and each error type; relay tests with a fake tube for reply,
  timeout, sweep and tube destroy; import ops round trip on a temp expdb;
  indicator graph ops round trip; skills drift test.
- Frontend: command handler tests for busy refusal, unknown op and reply;
  woco ops and markers against a seeded app-db; per-vertical handler tests
  where the plugin's state is db-backed; a test that every `:frontend` op in
  the catalog has a handler registered after init.
- E2e: one spec for the pointer tool on the browser bundle.
- Before the PR: all five suites green, clj-kondo at baseline, the PR #118
  e2e spec still green after the merge.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `EXPLORAMA_AGENT_GATEWAY_PRINCIPALS` | empty | EDN set of allowed principals; empty denies everything |
| `EXPLORAMA_AGENT_GATEWAY_PRINCIPAL_HEADER` | `x-auth-request-user` | header carrying the principal |
| `EXPLORAMA_AGENT_GATEWAY_TIMEOUT_MS` | `30000` | default op timeout |

Documented in `.env.example`, `.env.production.example`,
`docker-compose.full.yml` and the docker README.

## Out of scope

- Browser and electron entry points.
- A raw event bridge.
- Agent-initiated project save.
- Streaming or long-polling; every op is one request, one response.

## Follow-up

- TODO: fold the gateway, the op catalog, the pointer tool and the import
  gateway into `.blend/concept.edn` (skipped at spec review).
