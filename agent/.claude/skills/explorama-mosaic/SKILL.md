---
name: explorama-mosaic
description: Operations of Explorama's mosaic plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama mosaic operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:mosaic/filter`

Apply a local filter description to the frame. Returns the state.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/mosaic/filter" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :filter-desc <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any] [:filter-desc :any]]
```

Output schema:

```clojure
[:map
 [:vertical string?]
 [:tool string?]
 [:local-filter :any]
 [:di :any]
 [:title :any]
 [:operation-desc :any]
 [:selected-layout :any]]
```

## `:mosaic/open`

Open a mosaic frame fed by :source-frame-id at :position. Returns the frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/mosaic/open" \
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

## `:mosaic/operation`

Run one toolbar operation and answer with the state once the mosaic finished it. :params per action: :group-by/:sub-group-by {:by attribute}, :sort-by {:by attribute}, :sort-group-by {:by :method :attr}, :scatter-axis {:path :axis}, :treemap-algorithm {:algo "squared"|"binary"|"slice"}, :remove {:group-type :grp-attr-val}; the others take {}.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/mosaic/operation" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :action :group-by, :params <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:action
  [:enum
   :group-by
   :ungroup
   :sub-group-by
   :unsub-group
   :sort-by
   :sort-group-by
   :sort-sub-group-by
   :couple
   :sync-coupled
   :decouple
   :scatter
   :scatter-axis
   :treemap
   :treemap-algorithm
   :raster
   :remove]]
 [:params {:optional true} map?]]
```

Output schema:

```clojure
[:map
 [:vertical string?]
 [:tool string?]
 [:local-filter :any]
 [:di :any]
 [:title :any]
 [:operation-desc :any]
 [:selected-layout :any]]
```

## `:mosaic/remove-layout`

Deselect one layout. Layout ids come from :configuration/entries with #{:layouts}. Returns the state.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/mosaic/remove-layout" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :layout-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any] [:layout-id :any]]
```

Output schema:

```clojure
[:map
 [:vertical string?]
 [:tool string?]
 [:local-filter :any]
 [:di :any]
 [:title :any]
 [:operation-desc :any]
 [:selected-layout :any]]
```

## `:mosaic/set-layouts`

Replace the selected layouts with :layouts (layout ids). Layout ids come from :configuration/entries with #{:layouts}. Returns the state.

Runs on the frontend, answers within 60000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/mosaic/set-layouts" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :layouts [<value>]}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any] [:layouts [:vector :any]]]
```

Output schema:

```clojure
[:map
 [:vertical string?]
 [:tool string?]
 [:local-filter :any]
 [:di :any]
 [:title :any]
 [:operation-desc :any]
 [:selected-layout :any]]
```

## `:mosaic/state`

The frame's operation description (grouping, sorting, scatter, treemap, raster), selected layouts, filter and data instance.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/mosaic/state" \
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
 [:vertical string?]
 [:tool string?]
 [:local-filter :any]
 [:di :any]
 [:title :any]
 [:operation-desc :any]
 [:selected-layout :any]]
```
