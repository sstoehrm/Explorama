---
name: explorama-projects
description: Operations of Explorama's projects plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama projects operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:projects/create`

Create a project from the current workspace with :title and :description. This is the only way the agent may persist a workspace; there is no save op. Answers 1.5 s after dispatch and may describe work still in flight; poll :woco/workspace until the interaction mode is :normal. Returns :projects/current.

Runs on the frontend, answers within 20000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/create" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:title "text", :description "text"}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:title [:string {:min 1}]]
 [:description {:optional true} string?]]
```

Output schema:

```clojure
[:map [:project :any] [:step :any] [:unsaved? boolean?]]
```

## `:projects/current`

The loaded project (nil when none), the current protocol step and whether the workspace has unsaved changes.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/current" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
[:map [:project :any] [:step :any] [:unsaved? boolean?]]
```

## `:projects/list`

The user's projects grouped into :created-projects, :allowed-projects, :read-only-projects and :public-read-only-projects, each id -> description with :title and :description.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/list" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
map?
```

## `:projects/load`

Load a project into the workspace, replacing what is open. Answers 1.5 s after dispatch and may describe work still in flight; poll :woco/workspace until the interaction mode is :normal. Returns :projects/current.

Runs on the frontend, answers within 20000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/load" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:project-id "text"}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:project-id string?]]
```

Output schema:

```clojure
[:map [:project :any] [:step :any] [:unsaved? boolean?]]
```

## `:projects/load-step`

Replay the loaded project up to protocol step :step. Answers 1.5 s after dispatch and may describe work still in flight; poll :woco/workspace until the interaction mode is :normal. Returns :projects/current.

Runs on the frontend, answers within 20000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/load-step" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:step 1}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:step int?]]
```

Output schema:

```clojure
[:map [:project :any] [:step :any] [:unsaved? boolean?]]
```

## `:projects/rename`

Change a project's title. Returns :projects/list.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/rename" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:project-id "text", :title "text"}}'
```

Input schema:

```clojure
[:map {:closed true} [:project-id string?] [:title [:string {:min 1}]]]
```

Output schema:

```clojure
map?
```

## `:projects/set-description`

Change a project's description. Returns :projects/list.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/projects/set-description" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:project-id "text", :description "text"}}'
```

Input schema:

```clojure
[:map {:closed true} [:project-id string?] [:description string?]]
```

Output schema:

```clojure
map?
```
