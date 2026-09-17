---
name: explorama-search
description: Operations of Explorama's search plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama search operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:search/add-rows`

Add attribute rows by name with selected values, e.g. [["country" ["Germany"]] ["year" [2020]]]. Returns the form state.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/add-rows" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :rows [["text" <value>]]}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:rows [:vector [:tuple string? :any]]]]
```

Output schema:

```clojure
map?
```

## `:search/attributes`

The attribute descriptors ([name type] tuples) known to the search and their types, plus the attributes already in the frame's form.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/attributes" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
[:map [:attribute-types :any] [:frame-attributes :any]]
```

## `:search/data-instance`

The data instance the frame currently publishes, or nil before :search/run.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/data-instance" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
:any
```

## `:search/form-state`

The frame's form data: attribute descriptor -> selected values and options, as the search stores it.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/form-state" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
map?
```

## `:search/open`

Open an empty search frame at the default position. Returns the frame. Fill it with :search/set-form or :search/add-rows, then :search/run.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/open" \
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

## `:search/open-visualization`

Open a visualization fed by this search frame. Same as :woco/open-vertical with :source-frame-id set.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/open-visualization" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :vertical "table", :position [1 1]}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:vertical [:enum "table" "mosaic" "map" "charts" "algorithms"]]
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

## `:search/run`

Run the search: creates the data instance from the form and answers with it once the backend delivered it.

Runs on the frontend, answers within 120000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/run" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
:any
```

## `:search/set-form`

Replace the frame's whole form data with :formdata (the shape :search/form-state returns). Returns the form state.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/search/set-form" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :formdata <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any] [:formdata map?]]
```

Output schema:

```clojure
map?
```
