---
name: explorama-algorithms
description: Operations of Explorama's algorithms plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama algorithms operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:algorithms/open`

Open a prediction frame fed by :source-frame-id at :position. Returns the frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/algorithms/open" \
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

## `:algorithms/set-value`

Set one form value the way the UI does: :state-key is :goal, :settings, :parameter, :simple-parameter or :future-data; :path the key path inside it; :map? true when the value is a select option. Returns the state.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/algorithms/set-value" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :state-key :goal, :path [<value>], :value <value>, :map? true}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:state-key
  [:enum :goal :settings :parameter :simple-parameter :future-data]]
 [:path [:vector :any]]
 [:value :any]
 [:map? {:optional true} boolean?]]
```

Output schema:

```clojure
[:map
 [:task :any]
 [:goal :any]
 [:settings :any]
 [:parameter :any]
 [:result :any]]
```

## `:algorithms/state`

The frame's current form state (goal, settings, parameters), the last submitted task and the prediction result.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/algorithms/state" \
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
 [:task :any]
 [:goal :any]
 [:settings :any]
 [:parameter :any]
 [:result :any]]
```

## `:algorithms/submit-task`

Submit a prediction task (the shape :algorithms/state returns under :task) and answer with the state once the prediction arrived.

Runs on the frontend, answers within 300000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/algorithms/submit-task" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :task <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any] [:task map?]]
```

Output schema:

```clojure
[:map
 [:task :any]
 [:goal :any]
 [:settings :any]
 [:parameter :any]
 [:result :any]]
```
