---
name: explorama-indicator
description: Operations of Explorama's indicator plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama indicator operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:indicator/create-graph`

Create a graph artifact. It is recompiled on save; a failing validation is returned as invalid-params. Read an existing graph for the shape of :dataset-bindings. Returns the stored artifact.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/create-graph" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:artifact {:id "text", :name "text", :description "text", :graph-text "text", :dataset-bindings <value>}}}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:artifact
  [:map
   [:id string?]
   [:name string?]
   [:description {:optional true} string?]
   [:graph-text string?]
   [:dataset-bindings :any]]]]
```

Output schema:

```clojure
:any
```

## `:indicator/delete-graph`

Delete a graph. Returns :indicator/graphs.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/delete-graph" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:id "text"}}'
```

Input schema:

```clojure
[:map {:closed true} [:id string?]]
```

Output schema:

```clojure
:any
```

## `:indicator/graph`

One graph by id.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/graph" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:id "text"}}'
```

Input schema:

```clojure
[:map {:closed true} [:id string?]]
```

Output schema:

```clojure
:any
```

## `:indicator/graphs`

The user's aggregation graphs: id, name, description, graph-text, dataset-bindings and the compiled artifact.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/graphs" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
:any
```

## `:indicator/open`

Open the indicator management frame (there is at most one), or bring the existing one to front if it is already open. Connect a search to it with :woco/connect. Returns the frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/open" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
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

## `:indicator/publish-graph`

Compute the graph and publish its result as a data instance other verticals can open. Returns the data instance.

Runs on the backend, answers within 300000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/publish-graph" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:id "text"}}'
```

Input schema:

```clojure
[:map {:closed true} [:id string?]]
```

Output schema:

```clojure
:any
```

## `:indicator/update-graph`

Replace a graph artifact by :id. Returns the stored artifact.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/update-graph" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:artifact {:id "text", :name "text", :description "text", :graph-text "text", :dataset-bindings <value>}}}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:artifact
  [:map
   [:id string?]
   [:name string?]
   [:description {:optional true} string?]
   [:graph-text string?]
   [:dataset-bindings :any]]]]
```

Output schema:

```clojure
:any
```

## `:indicator/validate-graph`

Parse and validate :graph-text against :dataset-count bound datasets without saving. Returns {:errors [...] :warnings [...] :shapes {...}} plus :operations, the operation reference with parameters and shape transitions. A graph is one edn map {:nodes {..} :edges {..}}: nodes are :datasource (with :dataset <n>), :operation (with :op and optional :params) or exactly one :result (with :name); edges {[from to] {:direction :-> :order n :as "name"}} form a connected dag.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/indicator/validate-graph" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:graph-text "text", :dataset-count 1}}'
```

Input schema:

```clojure
[:map {:closed true} [:graph-text string?] [:dataset-count int?]]
```

Output schema:

```clojure
:any
```
