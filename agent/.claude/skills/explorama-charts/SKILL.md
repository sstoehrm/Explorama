---
name: explorama-charts
description: Operations of Explorama's charts plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama charts operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:charts/open`

Open a charts frame fed by :source-frame-id at :position. Returns the frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/charts/open" \
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

## `:charts/set-state`

Restore :chart-desc, :di and/or :local-filter on the frame, the way a project reload does. Read :charts/state first and modify what it returned. Returns the state.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/charts/set-state" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :chart-desc <value>, :di <value>, :local-filter <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:chart-desc {:optional true} :any]
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
 [:chart-desc :any]
 [:local-filter :any]]
```

## `:charts/state`

The frame's data instance, chart description and local filter.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/charts/state" \
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
 [:chart-desc :any]
 [:local-filter :any]]
```
