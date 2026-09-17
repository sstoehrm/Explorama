---
name: explorama-map
description: Operations of Explorama's map plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama map operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:map/open`

Open a map frame fed by :source-frame-id at :position. Returns the frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/map/open" \
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

## `:map/operation`

Run one map operation and answer with the state once the map finished it. :params is the payload the legend sends for that action; read :map/state first to see the current shape.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/map/operation" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :action :base-layer, :params <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:action
  [:enum
   :base-layer
   :marker
   :cluster-switch
   :highlight-marker
   :overlayer
   :feature-layer
   :hide-feature-layer
   :popup
   :position-change
   :filter]]
 [:params {:optional true} map?]]
```

Output schema:

```clojure
[:map
 [:di :any]
 [:vertical string?]
 [:tool string?]
 [:title :any]
 [:task-desc :any]]
```

## `:map/state`

The frame's task description: data instance, base layer, marker layouts, feature layers, overlayers, filter and view position.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/map/state" \
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
 [:task-desc :any]]
```
