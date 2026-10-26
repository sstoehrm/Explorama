# Plugin Patterns

Load this reference when a task touches plugin lifecycle, frames, Frontend Interface API, backend routes, or shared contracts.

## Layer Layout

- Frontend: `plugins/frontend/de/explorama/frontend/<plugin>/`
- Backend: `plugins/backend/de/explorama/backend/<plugin>/`
- Shared: `plugins/shared/de/explorama/shared/<plugin>/`
- Tests: `plugins/frontend_test`, `plugins/backend_test`, and `plugins/shared_test`

The same feature often needs coordinated changes in all three layers. Search by plugin name before editing:

```bash
rg -n "<plugin-name>|<route-key>|<event-name>" plugins bundles
```

## Frontend Interface API

The Frontend Interface API is defined in `plugins/frontend/de/explorama/frontend/woco/api/core.cljs` and consumed through `de.explorama.frontend.common.frontend-interface`.

Common suffix meanings:

- `*-raw`: direct value
- `*-fn`: callable function
- `*-sub`: subscription object
- `*-sub-vec`: subscription vector
- `*-db-get`: direct DB getter
- `*-event-vec`: event vector
- `*-event-dispatch`: dispatching helper

Prefer these APIs for cross-plugin interaction. Only read another plugin's app-db path directly when existing code in the same area already does so and no FI service exists.

## Plugin Lifecycle

Frontend plugin init usually registers itself with woco and shared services:

```clojure
(defn init []
  (register-init 0))
```

Backend plugin init usually registers route handlers:

```clojure
(defn init []
  (frontend-api/register-routes {ws-api/init-client initialize
                                 ws-api/request-attributes request-attributes}))
```

When adding a route, keep the route key in a shared namespace, dispatch it from frontend code, and register it in backend init.

## Backend Request Flow

Frontend requests commonly use `:backend-tube` effects or `backend-api/dispatch`:

```clojure
[:backend-tube [ws-api/get-acs {:client-callback [ws-api/set-acs]}]]
```

Backend handlers commonly receive a callback metadata map and a parameter vector. Preserve callback names and response event shapes unless the caller and tests are updated together.

## Frame Integration

Visualization plugins create frames with descriptors containing module, vertical, event, sizing, and optional class data. The owning frame event handles actions such as:

- `:frame/init`
- `:frame/query`
- `:frame/close`
- `:frame/connect-to`
- `:frame/connection-negotiation`

When changing frame data contracts, update frame-info API registration, frame-instance API registration, undo/replay behavior, and tests that reconstruct saved projects.

## Verification Checklist

- Shared route key updated.
- Frontend dispatch or effect updated.
- Backend handler and `frontend-api/register-routes` updated.
- Callback event shape still matches the receiving event.
- Tests added or adjusted in the matching `plugins/*_test` tree.
- Bundle test command selected with `skills/explorama-bundle-development/SKILL.md`.
