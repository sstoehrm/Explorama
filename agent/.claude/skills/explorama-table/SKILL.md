---
name: explorama-table
description: Operations of Explorama's table plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama table operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:table/open`

Open a table frame fed by :source-frame-id at :position. Returns the frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/table/open" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:source-frame-id <value>, :position [1 1]}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:source-frame-id {:optional true} :any]
 [:position {:optional true} [:tuple number? number?]]]
```

Output schema:

```clojure
[:map
 [:id :any]
 [:vertical string?]
 [:type [:maybe keyword?]]
 [:title [:maybe string?]]
 [:left [:maybe number?]]
 [:top [:maybe number?]]
 [:width [:maybe number?]]
 [:height [:maybe number?]]
 [:z-index [:maybe number?]]
 [:minimized? boolean?]
 [:maximized? boolean?]
 [:di :any]
 [:published-by :any]
 [:color-group :any]]
```

## `:table/set-state`

Restore :table-state, :di and/or :local-filter on the frame, the way a project reload does. Read :table/state first and modify what it returned. Returns the state.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/table/set-state" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :table-state <value>, :di <value>, :local-filter <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:table-state {:optional true} :any]
 [:di {:optional true} :any]
 [:local-filter {:optional true} :any]]
```

Output schema:

```clojure
[:map
 [:di :any]
 [:vertical string?]
 [:tool string?]
 [:title :any]
 [:table-state :any]
 [:local-filter :any]]
```

## `:table/state`

The frame's data instance, table state (sorting, column layout as the table encodes it) and local filter.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/table/state" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
[:map
 [:di :any]
 [:vertical string?]
 [:tool string?]
 [:title :any]
 [:table-state :any]
 [:local-filter :any]]
```
