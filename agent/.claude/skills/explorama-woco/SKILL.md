---
name: explorama-woco
description: Operations of Explorama's woco plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama woco operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:woco/bring-to-front`

Raise a frame above the others. Returns the frame.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/bring-to-front" \
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

## `:woco/clear-markers`

Remove every marker.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/clear-markers" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
[:vector
 [:map
  [:frame-id :any]
  [:note [:maybe string?]]
  [:set-at number?]
  [:title [:maybe string?]]
  [:vertical string?]]]
```

## `:woco/close`

Close a frame. Returns the remaining frames.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/close" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
[:vector
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
  [:color-group :any]]]
```

## `:woco/connect`

Feed :source's data instance into :target, like dragging one frame onto another. Returns the connections.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/connect" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:source <value>, :target <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:source :any] [:target :any]]
```

Output schema:

```clojure
[:vector
 [:map [:from :any] [:to :any] [:kind [:enum :publishes :coupled]]]]
```

## `:woco/connections`

Edges between frames: :publishes (the :from frame's data instance feeds the :to frame) and :coupled (the frames are coupled).

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/connections" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
[:vector
 [:map [:from :any] [:to :any] [:kind [:enum :publishes :coupled]]]]
```

## `:woco/frame-state`

The full serializable state of one frame as its plugin reports it (the vis-desc used by reporting), without the screenshot.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/frame-state" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any]]
```

Output schema:

```clojure
[:maybe map?]
```

## `:woco/frames`

Every frame in the workspace with its vertical, title, geometry, z-order, window state, the data instance it shows and the frame that published that data instance. Frame ids are opaque; pass them back verbatim.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/frames" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
[:vector
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
  [:color-group :any]]]
```

## `:woco/markers`

Frames the user marked with the pointer tool, with their notes. A marked frame is the one the user wants you to work on.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/markers" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
[:vector
 [:map
  [:frame-id :any]
  [:note [:maybe string?]]
  [:set-at number?]
  [:title [:maybe string?]]
  [:vertical string?]]]
```

## `:woco/maximize`

Maximize a frame. Returns the frame.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/maximize" \
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

## `:woco/minimize`

Minimize a frame. Returns the frame.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/minimize" \
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

## `:woco/normalize`

Restore a minimized or maximized frame. Returns the frame.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/normalize" \
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

## `:woco/open-vertical`

Open a visualization frame (table, mosaic, map, charts or algorithms) at :position, fed by :source-frame-id's data instance when given. A caller-supplied :opts :overwrites is replaced by the forced position behavior. Returns the new frame.

Runs on the frontend, answers within 10000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/open-vertical" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:vertical "table", :source-frame-id <value>, :position [1 1], :opts <value>}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:vertical [:enum "table" "mosaic" "map" "charts" "algorithms"]]
 [:source-frame-id {:optional true} :any]
 [:position {:optional true} [:tuple number? number?]]
 [:opts {:optional true} map?]]
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

## `:woco/set-geometry`

Move and/or resize a frame in workspace pixels. Returns the frame.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/set-geometry" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :left 1, :top 1, :width 1, :height 1}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:frame-id :any]
 [:left {:optional true} number?]
 [:top {:optional true} number?]
 [:width {:optional true} number?]
 [:height {:optional true} number?]]
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

## `:woco/set-title`

Set a frame's user title. Returns the frame.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/set-title" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:frame-id <value>, :title "text"}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true} [:frame-id :any] [:title string?]]
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

## `:woco/workspace`

Interaction mode (:normal, :read-only, :pending-read-only, :no-render, :render), workspace id, viewport position and the loaded project. Check the mode before mutating.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/woco/workspace" \
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
 [:interaction-mode keyword?]
 [:workspace-id :any]
 [:viewport :any]
 [:project :any]]
```
