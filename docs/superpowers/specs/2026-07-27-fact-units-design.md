# Fact Units Design

Date: 2026-07-27

## Goal

Facts can carry an optional unit ("°C", "cases/100k", "USD"), and the
visualizations that display fact values show that unit alongside the attribute
name.

## Placement: attribute characteristics, not data

The unit lives **only** in the attribute characteristics (ACs) and never in the
stored data. The event tuple

```clojure
[global-id dates datasource facts contexts locations notes]
```

with facts as `[name type value]` triples is unchanged. `facts->`
(`plugins/backend/de/explorama/backend/expdb/persistence/shared.cljc:23`) and
`db-event->explorama`
(`plugins/backend/de/explorama/backend/expdb/persistence/common.cljc:29`) are
untouched.

`:ranges` is the precedent. It is per-fact-attribute metadata computed in
`transform->table` alongside the events, stored in the data-tile meta, merged
across imports by `merge-data-tiles-meta`, aggregated across tiles by
`search-api/ranges`
(`plugins/backend/de/explorama/backend/expdb/legacy/search/attribute_characteristics/api.cljc:213`)
and consumed by plugins through `ac-api/attribute-ranges`. Units follow that
path exactly.

Consequences of this placement:

- No data migration. Data persisted before this change stays valid; tiles
  written earlier simply have no `:units` key.
- The index and search are unaffected. `:units` is tile metadata and never
  becomes a graph node, unlike `:acs` which `add-index!`
  (`plugins/backend/de/explorama/backend/expdb/query/index.cljc:11`) turns into
  `[node-label attribute value]` nodes.
- Visualizations read one additional AC map rather than a changed event shape.

## Unit representation

A unit is a free-form string. There is no controlled vocabulary: the codebase
does not normalise fact types either, and a closed list would reject legitimate
domain units such as `cases/100k`.

In the mapping template `:unit` is a `"value-single"` extractor, the same
schema alternative `:type` already uses, so it can be a constant or read from a
column:

```clojure
{:name  [:value "temperature"]
 :type  [:value "decimal"]
 :unit  [:value "°C"]          ; constant
 :value [:field "temp"]}

{:name  [:value "temperature"]
 :type  [:value "decimal"]
 :unit  [:field "unit_col"]    ; per row
 :value [:field "temp"]}
```

In the import format `:unit` is an optional string on each fact:

```clojure
{:name "temperature" :type "decimal" :value 21.5 :unit "°C"}
```

In the data-tile meta units are a set per attribute, merged with `set/union`
exactly as `:acs` already is:

```clojure
:units {"temperature" #{"°C"}}
```

A set only ever holds more than one element across datasources — see
"Conflicting units" below.

## Conflicting units

One import may not give one fact name two different units. `transform->table`
validates this across the whole import, not per data tile, and throws:

```clojure
(ex-info "Conflicting units for fact" {:attribute "temperature"
                                       :units #{"°C" "°F"}})
```

`transform->import`
(`plugins/backend/de/explorama/backend/expdb/persistence/shared.cljc:280`)
already catches `Throwable` and converts it to
`{:success false :message ... :data {:error-data ...}}`, which the import
dialog already surfaces. Placing the check in `transform->table` puts it at the
one chokepoint every import route passes through, including direct API imports
that never go through the CSV mapping. This mirrors the existing check for
contradictory contexts in
`plugins/shared/de/explorama/shared/data_transformer/mapping.cljc:220`.

The check does **not** read persisted state. Two separate imports — typically
two datasources — can therefore still put different units on the same attribute
name. Cross-tile aggregation preserves both rather than picking one, and the
frontend format helper decides what to render.

## Import pipeline

| File | Change |
|---|---|
| `plugins/shared/de/explorama/shared/data_transformer/schema.cljc:81` | facts map gains `[:unit {:optional true} "value-single"]` |
| `plugins/shared/de/explorama/shared/data_transformer/spec.cljc:23` | facts map gains `[:unit {:optional true} :string]` |
| `plugins/backend/de/explorama/backend/expdb/spec.cljc:21` | same addition |
| `plugins/shared/de/explorama/shared/data_transformer/generator.cljc:7` | `(fact [this name type value unit])` |
| `plugins/shared/de/explorama/shared/data_transformer/generator/edn_json.cljc:15` | `(cond-> {...} unit (assoc :unit unit))` |
| `plugins/shared/de/explorama/shared/data_transformer/generator/operations.cljc:16` | unchanged return `[name type]` |
| `plugins/shared/de/explorama/shared/data_transformer/mapping.cljc:298` | `gen-generic` attrs become `[:name :type :value :unit]` |

`resolve*` returns `nil` for an absent desc
(`plugins/shared/de/explorama/shared/data_transformer/mapping.cljc:182`), so a
fact without `:unit` needs no default and no second arity — the generator
receives `nil` and `cond->` omits the key.

`operations.cljc` reports which attributes a mapping produces; a unit does not
change that set, so its `fact` implementation keeps returning `[name type]`
while accepting the new argument.

`suggestions.cljc:246` infers no unit. Nothing in a CSV reliably indicates one.

The two spec namespaces (`data_transformer/spec.cljc` and `expdb/spec.cljc`)
are identical duplicates today, tracked by an existing TODO. This design edits
both and does not attempt to unify them.

## AC storage and aggregation

`transform->table`
(`plugins/backend/de/explorama/backend/expdb/persistence/shared.cljc:78`) gains
a `:units` map on each data tile, built where `:ranges` is built (`:152`) and
merged into the accumulated tile the same way (`:180`), using `set/union`.

A tile carries units only for the facts its own events contain, matching how
`:ranges` and `:acs` are scoped. The conflict validation is wider than a single
tile: it covers every fact in the import, so an import whose rows disagree is
rejected even when the conflicting rows land in different tiles.

`merge-data-tiles-meta`
(`plugins/backend/de/explorama/backend/expdb/persistence/common.cljc:53`)
carries `:units` through its `select-keys` (`:58`) and merges it with
`merge-with set/union`. A tile written before this change has no `:units`, and
`merge-with` tolerates that already.

A new `units` function in
`plugins/backend/de/explorama/backend/expdb/legacy/search/attribute_characteristics/api.cljc`
aggregates across tiles, structured as a near-copy of `ranges` (`:213`): it
reads tile meta through `persistence/get-meta-data` and reduces with
`set/union` instead of min/max. It returns `{attribute #{unit}}`, omitting
attributes that have no unit anywhere.

It is exposed as `attribute-units` in all three bundles:

- `bundles/browser/backend/de/explorama/backend/expdb/middleware/ac.cljs`
- `bundles/electron/backend/src/de/explorama/backend/expdb/middleware/ac.cljs`
- `bundles/server/backend/de/explorama/backend/expdb/middleware/ac.clj`

## Frontend delivery

| Step | File |
|---|---|
| Init payload gains `:attr-units` | `plugins/backend/de/explorama/backend/search/client_api.cljc:52` |
| Client destructures it and dispatches `::set-attr-units` | `plugins/frontend/de/explorama/frontend/search/backend/core.cljs:32` |
| Stored at a new `path/attribute-units` | `plugins/frontend/de/explorama/frontend/search/path.cljs:15` |
| Exposed as `[:acs :attribute-units-db-get]` and `[:acs :attribute-units-sub]` | `plugins/frontend/de/explorama/frontend/search/api/core.cljs` |

The FI names follow the `*-db-get` / `*-sub` convention documented in
CLAUDE.md.

Units ride the lifecycle `attr-types` already has. That includes a pre-existing
limitation: the payload reaches the client only through `ws-api/init-client`,
dispatched when a search frame opens and `attribute-types` is absent
(`plugins/frontend/de/explorama/frontend/search/core.cljs:161`), so both types
and units are stale after an in-session import until the client re-initialises.
Fixing that is out of scope here.

## The format helper

One helper decides how a unit joins an attribute name, placed next to
`attribute-label` in `plugins/frontend/de/explorama/frontend/common/i18n.cljs:99`
and following its two-arity shape:

```clojure
(defn attribute-label-with-unit
  ([labels units attr] ...)
  ([attr] ...))
```

Its rule, and the only place the multi-unit case is decided:

| Units for the attribute | Result |
|---|---|
| exactly one | `"Temperature (°C)"` |
| none | `"Temperature"` |
| more than one | `"Temperature"` |

The last case is deliberate. Labelling with one unit out of several would
misstate the data, so the label falls back to the bare name.

## Render sites

| Context | Seam |
|---|---|
| Charts axis titles | `plugins/frontend/de/explorama/frontend/charts/charts/utils.cljs:97` (`x-axis`, `:title :text`) and `:273` (y-scale title text) |
| Charts legend and axis pickers | `plugins/frontend/de/explorama/frontend/charts/charts/legend.cljs:150, 241, 324, 575-578` |
| Table column headers | `plugins/frontend/de/explorama/frontend/table/table/view.cljs:108` `header-cell-renderer` |
| Map popups | `plugins/frontend/de/explorama/frontend/map/map/impl/openlayers/util.cljs` `gen-popup-content` |
| Mosaic cards, zoom levels 2 and 3 | `plugins/frontend/de/explorama/frontend/mosaic/render/core.cljs:322` and the zoom-2/zoom-3 content in `plugins/frontend/de/explorama/frontend/mosaic/render/draw/common_cards.cljs` |
| Attribute dropdowns and selection lists | charts and mosaic legend selects, search rows, indicator attribute lists |

`gen-popup-content` already takes an `attribute-label-fn` parameter
(`plugins/frontend/de/explorama/frontend/map/map/impl/openlayers/feature_layers/movement.cljs:52`),
so the map change is an argument swap with no signature change.

Mosaic draws card content only at zoom levels 2 and 3
(`common_cards.cljs`, `zoom-2-content-header`, `zoom-3-content-header`,
`zoom-3-content-footer`); zoom level 1 has no field text. The draw path already
receives `attribute-labels` from `render/core.cljs:322`, and units are threaded
alongside it.

## Import dialog

A `unit-view` row in the mapping table, beside `name-view`
(`plugins/frontend/de/explorama/frontend/expdb/temp_import/core.cljs:651`) and
`type-view` (`:749`). It renders an input only for columns whose node is
`:facts` and writes `[:desc :unit]` through the existing `::update-field` event.
Other node types render an empty cell, as `type-view-field` already does.

`::change-col-type` (`:385`) sets no default unit — the field is optional.
`generate-mapping` (`:133`) and `transform-mapping` (`:84`) carry `:unit`
through to and from the mapping template.

## Error handling

| Case | Behaviour |
|---|---|
| Two units for one fact name within one import | `transform->table` throws; `transform->import` returns `{:success false :message ...}` |
| Non-string unit, e.g. a numeric column mapped as the unit | Rejected by malli before `transform->table` runs; `spec/validate` at `shared.cljc:242` returns `{:success false :result (spec/explain data)}` |
| No unit on a fact | Not an error at any layer; every consumer treats an absent unit as "no unit" |
| Tile persisted before this change | No `:units` key; merges and aggregation tolerate it, labels render bare |

## Testing

| Test | Location |
|---|---|
| Aggregation across tiles, including the multi-unit set | `plugins/backend_test/de/explorama/backend/expdb/ac_api_test.cljc`, mirroring `ranges-test:426` and reusing its import fixture |
| Conflicting units in one import return `{:success false}` | same namespace |
| A fact with a constant `:unit` and one with a `[:field ...]` `:unit` survive CSV to import format | extend `test-end-to-end`, `plugins/backend_test/de/explorama/backend/expdb/mapping_test.cljc:88` |
| Import data validates with and without `:unit` | spec tests for both spec namespaces |
| Format helper: one unit, no unit, several units | new frontend test |
| Map popup rendered with a unit-aware label fn | extend `plugins/frontend_test/de/explorama/frontend/map/impl/openlayers/util_test.cljs`, which already stubs `attribute-label-fn` at `:61` |

All four suites must pass: browser (`npm run test-ci`), electron
(`make test`), and the server bundle's backend and frontend suites.

## Build order

Three pieces:

1. **Model and ACs.** Schema, both specs, generators, `mapping.cljc`,
   persistence, the new `units` aggregation, the three bundle `ac` namespaces.
   Verified end to end through `ac_api_test` without any UI.
2. **Delivery, format helper and render sites.** Frontend only.
3. **Import dialog.** The `unit-view` row.

Piece 1 blocks 2 and 3. Pieces 2 and 3 are independent of each other.

## Out of scope

- Unit conversion or arithmetic. Units are display metadata only.
- A controlled unit vocabulary.
- Validating an incoming unit against units already persisted for the same
  attribute.
- Refreshing `attr-types` and `attr-units` on the client after an in-session
  import.
- Unifying the duplicated `data_transformer/spec.cljc` and `expdb/spec.cljc`.
