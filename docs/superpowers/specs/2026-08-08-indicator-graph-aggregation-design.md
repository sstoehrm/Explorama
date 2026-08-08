# Graph-Based Aggregations for the Indicator Plugin

Date: 2026-08-08
Status: approved design, pre-implementation

## Goal

A new, text-based way to author aggregations inside the indicator plugin. The
user describes a directed acyclic graph in EDN (format modeled on
[simpleviz](https://github.com/sstoehrm/simpleviz), with restrictions):
datasource start nodes flow through operation nodes from
`de.explorama.shared.data-format.operations` into exactly one result node,
which becomes a new datasource. The graph can also be generated on request by
an agent via the agent_requests plugin.

Decisions made during brainstorming:

- Graph aggregations are a **separate artifact type** from template
  indicators, with their own persistence, but listed together in the
  indicator overview. Creation is a mode switch (template / graph) in one
  combined flow.
- Text-only editing — no GUI graph editor.
- Exactly **one result node** per graph.
- Edge direction uses simpleviz `:direction` semantics (`:->` / `:<-`), not
  key order.
- Input ordering for non-commutative operations via an `:order` edge
  attribute.
- A dedicated `:result` node type; the compiler generates `:heal-event` —
  users can never write it.
- Datasource nodes bind to datasets connected by drag-and-drop (today's
  `connect-to-di` flow) via an explicit `:dataset <n>` reference.
- Operations exposed: all non-internal vpl ops plus curated internals
  (`:distinct`, `:normalize`, `:filter`); validation includes shape inference
  driven by the vpl `:input->output` metadata.
- Agent results appear in a side-by-side proposal pane with Apply/Dismiss;
  they never overwrite the user's text unprompted.
- Preview is result-only, reusing the existing `data-sample` route.
- The format/validator/compiler live in a shared `data_format` namespace
  (approach A), the first real consumer of the vpl metadata.

## 1. Graph format

One EDN map. Example:

```clojure
{:nodes {:conflicts  {:type :datasource :dataset 1}
         :population {:type :datasource :dataset 2}
         :grouped-c  {:type :operation :op :group-by
                      :params {:attributes ["year" "country"]}}
         :grouped-p  {:type :operation :op :group-by
                      :params {:attributes ["year" "country"]}}
         :deaths     {:type :operation :op :sum
                      :params {:attribute "deaths"}}
         :pop        {:type :operation :op :sum
                      :params {:attribute "population"}}
         :ratio      {:type :operation :op :/}
         :out        {:type :result :name "death-rate"}}
 :edges {[:conflicts :grouped-c]  {:direction :->}
         [:population :grouped-p] {:direction :->}
         [:grouped-c :deaths]     {:direction :->}
         [:grouped-p :pop]        {:direction :->}
         [:deaths :ratio]         {:direction :-> :order 1}
         [:pop :ratio]            {:direction :-> :order 2}
         [:ratio :out]            {:direction :-> :as "death-rate"}}}
```

### Nodes

Three node types, closed maps. Ids are keywords or strings (as in simpleviz).

| Type | Required | Optional | Constraints |
|---|---|---|---|
| `:datasource` | `:dataset` (positive int, ref to connected "Dataset n") | `:name` (display only) | no incoming edges; at least one outgoing edge |
| `:operation` | `:op` (allowed operation keyword) | `:params` (map), `:name` (display only) | in-degree must match the op's vpl `:arguments` steering (0 = 2+, else exact) |
| `:result` | `:name` (string — the new datasource name) | — | exactly one per graph; the only sink |

### Edges

`{[<a> <b>] {attrs}}` as in simpleviz. Attributes:

- `:direction` — `:->` (a feeds b) or `:<-` (b feeds a). Optional, defaults
  to `:->`. `:<->` and `:-` are rejected: flow must be unambiguous.
- `:order` — positive int. Required on **all** in-edges of a node when that
  node has 2+ inputs and its op is order-sensitive; must be a permutation of
  `1..n`. Ignored-with-warning elsewhere. The order-sensitive set is defined
  in `graph.cljc`, initially `#{:- :/ :difference}`.
- `:as` — string; only on edges into the `:result` node. Names the healed
  event attribute produced by that branch. Defaults to `"indicator"` when
  the result has a single in-edge; required per-edge when it has several.

The same connection may not appear twice (either key order).

### Filters

`:filter` is exposed as a normal operation whose `:params` is
`{:filter <inline filter-DSL form>}` (the
`de.explorama.shared.data-format.filter` format). The compiler hoists the
form into `:di/filter` under a `ctn->sha256-id` id and references that id in
the operation position, as the engine expects.

### Topology rules

- Connected DAG (no cycles, no disconnected nodes).
- Exactly one sink (node without outgoing flow), and it is the `:result`
  node.
- Every `:datasource` ref `<n>` must be bound to a connected dataset;
  connected datasets that no node references produce a warning.
- Fan-out (one node feeding several consumers) is allowed. The compiler
  duplicates the subtree structurally; the operation engine's refcounting
  already deduplicates identical subtrees at execution.
- No `:boxes` in v1.

### Result compilation

The compiler wraps the result branches in a generated `:heal-event`:

- `:policy` — `:merge` when the incoming branches are grouped values,
  `:vals` for a single branch of grouped events.
- `:descs` — one `{:attribute <as-name>}` per branch, ordered by `:order`
  (or the single branch's `:as`/default).
- `:addons` — `{"datasource" <result :name>}` and
  `{"indicator-type" "aggregation-graph"}`.
- `:generate-ids {:policy :uuid}`, date workaround
  `{"date" {:month "01" :day "01"}}`.
- `:force-type :double` for branches produced by `:aggregation`- or
  `:nummerical`-category operations (matching the templates, which coerce
  the computed indicator attribute but not e.g. `:distinct` string values).

Writing `:op :heal-event` on an operation node is a validation error.

## 2. Shared namespace: `de.explorama.shared.data-format.graph`

New file `plugins/shared/de/explorama/shared/data_format/graph.cljc`, sibling
of `operations.cljc` / `vpl.cljc`. Pure functions, no state, identical on
frontend and backend.

Public API:

- `parse` — string → `{:ok <graph>}` | `{:error {:message .. :position ..}}`.
  `clojure.edn` / `cljs.reader` — never eval.
- `graph-schema` — malli schema of the format (closed maps). Also used as the
  agent request's `:output-schema`.
- `allowed-operations`, `operation-metadata` — derived from
  `operations/functions` vpl metadata: every op with a `:category` and
  without `:internal`/`:interal` (existing typo — check both), minus
  `:heal-event`, plus `#{:distinct :normalize :filter}`.
  `operation-metadata` returns a pruned map per op: key, category,
  description, `:arguments`, param specs, `:input->output`. Consumed by live
  validation, the editor's help panel, and the agent request input. Curated
  internals that lack (parts of) their `:steering` metadata get supplemental
  metadata defined in `graph.cljc` so validation and shape inference cover
  them too.
- `validate` — graph + dataset-binding count →
  `{:errors [{:code .. :node/:edge .. :message ..}] :warnings [..]}`.
  Two passes:
  1. Structural: schema conformance, endpoint existence, DAG/connectivity,
     single-result sink, datasource in-degree 0, `:dataset` refs in range,
     `:order` completeness/uniqueness, arity vs `:arguments`, unknown
     `:params` keys, forbidden `:direction` values, duplicate connections.
  2. Shape inference: topological walk; datasource nodes start as
     `:meta-list-events`; each operation maps its input shapes through the
     vpl `:input->output` table (including `:dependent` param-conditional
     entries and the global `:disable-inputs`). A shape without a transition
     is a node-anchored error. Result branches must be heal-able shapes and
     homogeneous.
- `compile-graph` — validated graph + bindings `{<n> <di-id-string>}` →
  `{:calculation-desc [..] :filters {<hash-id> <filter-form>}}`. Leaves are
  DI-id strings exactly like the template pipeline's output, so
  `generate-di`, `data-sample` and `create-and-publish-di` consume it
  unchanged. Inline `:filter` operation params (a filter-DSL form) are
  hoisted into `:filters` with `ctn->sha256-id` ids and merged into
  `:di/filter` by the DI generation.

## 3. Indicator plugin integration

### Persistence

New artifact kind in its own expdb bucket `"/indicator/aggregation-graphs/"`
via the existing `Backend` protocol pattern. Spec:

```clojure
[:map
 [:id :string] [:name :string] [:creator :string]
 [:shared-by {:optional true} :string]
 [:description {:optional true} :string]
 [:graph-text :string]        ; verbatim user text, source of truth for editing
 [:dis map?]                  ; {<di-sha-id> <data-instance>}
 [:calculation-desc vector?]] ; compiled at save time
```

New websocket routes mirroring the persistence set
(`create/update/delete/share/all` for graph aggregations), handlers following
`persistence/{api,core}.cljc` patterns (`user-validation`, creator-only
mutation, broadcast on share, cache invalidation on update). The backend
re-runs `validate` + `compile-graph` before accepting a save and rejects with
the same error data the frontend shows.

### Overview

One merged list: template indicators and graph aggregations side by side,
each card with a kind badge. The create action offers the mode choice
(template / graph). Picking graph sets the active artifact
`{:id .. :kind :graph}`; `views/core/main-panel` gains a third branch on the
artifact kind.

### Graph editor screen

Same skeleton as the template editor: name/description fields and the
drag-drop dataset area ("Dataset 1..N" chips, unchanged `connect-to-di`
flow). Body:

- **Left** — monospace textarea holding the graph EDN (evolution of the
  dormant `custom_textarea` element).
- **Right** — live validation panel (node/edge-anchored errors and warnings)
  and the allowed-operations reference from `operation-metadata`; also hosts
  the agent prompt box and proposal pane (§4).
- **Bottom** — collapsible result preview reusing `result_preview` and the
  `data-sample` route on the compiled desc; enabled only when validation is
  clean.

Frontend validation runs on a ~300 ms debounce. A parse error shows a banner
and keeps the last good validation state (simpleviz behavior). Save /
preview / visualize are disabled while errors exist. Save compiles and
persists; discard and direct visualization behave as in the template editor.

Project event logging gains one new event, `"restore-graph-desc"` v1,
mirroring `"restore-indicator-desc"` so graph aggregations replay in
projects.

## 4. Agent-generated graphs

Registered at indicator backend init:

```clojure
{:id            :indicator/aggregation-graph
 :description   ;; prose: format spec, restrictions, how to use :input
 :output-schema graph/graph-schema
 :output-example ;; small valid graph
 :on-fulfilled  ;; forwards result via (:client-callback (:context request))
 }
```

Editor flow (server bundle only — the prompt box is hidden unless
`config-platform/agent-requests-available?`):

1. User types a prompt, hits Generate. Frontend generates a correlation-id,
   sets a pending flag, arms a `:dispatch-later` timeout — the expdb
   `request-mapping` pattern.
2. New ws route builds the request `:input`:
   `{:prompt .. :datasets [{:dataset 1 :attributes <ui-options>} ..]
   :operations <pruned operation-metadata> :format-doc <cheat-sheet>}` and
   calls `store/create!` with callbacks closing over the correlation-id.
3. A fulfilled result is already schema-valid (the output schema is the graph
   schema); the frontend still runs full `validate` against current bindings
   (e.g. the agent may reference an unconnected `:dataset 3`).
4. The result renders in a **side-by-side proposal pane**: read-only
   pretty-printed graph with its own validation status, Apply / Dismiss.
   Apply replaces the textarea (inline confirm if unsaved edits exist).
   Failures (`:failed`/`:expired`/`:cancelled`) render as an error state
   with the reason.

Queue visibility and cancel come from the agent_requests sidebar.

## 5. Error handling summary

| Failure | Behavior |
|---|---|
| EDN parse error | banner, last good validation state retained |
| Validation errors | anchored error list; save/preview/generate disabled |
| Backend save re-validation fails | `failed-callback` → notification (version skew / forged client only) |
| Agent request fails/expires/cancelled | proposal pane error state with reason |
| Dataset removed while referenced | ordinary validation error (parallels deleted-DI check) |

## 6. Testing

- `plugins/shared_test/de/explorama/shared/data_format/graph_test.cljc` —
  the bulk: parse failures; schema cases; every structural rule (cycle, two
  sinks, datasource with in-edge, `:<->`/`:-` rejection, missing/ambiguous
  `:order`, unknown op, unknown param key, unbound `:dataset`, duplicate
  connection); shape-inference acceptance and rejection chains; golden
  compile tests, including one asserting a graph reproduces the exact
  `calculation-desc` of an equivalent template indicator (reusing
  `management_test`'s expected value).
- `plugins/backend_test/de/explorama/backend/indicator/` — persistence
  CRUD/share/access for the new artifact; agent type registration and
  `on-fulfilled` forwarding; compile → `generate-di` → `data-sample` round
  trip.
- `plugins/frontend_test/de/explorama/frontend/indicator/` — editor state
  transitions, proposal apply/dismiss, debounced validation wiring.
- Every new test namespace is required in **both** `test_runner.cljs` and
  `test_runner_ci.cljs` of every bundle that compiles it. All three bundle
  suites run (shared cljc code); results read from `report.xml` per-suite.

## Out of scope (v1)

- GUI graph editor or rendered graph view.
- Per-node preview (result-only in v1).
- `:boxes`, multiple result nodes, undirected/bidirectional edges.
- Exposing further internal operations (`:select`, `:take-first`,
  `:apply-layout`, ...).
