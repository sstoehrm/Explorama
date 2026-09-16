# Import Mapping Reference

Load this reference when editing mapping EDN, transformer operations, import docs, or sample datasets.

## Current Mapping Shape

Mappings are EDN maps with `:meta-data` and `:mapping`.

```clojure
{:meta-data {:file-format :csv
             :csv {:separator ","
                   :quote "\""
                   :limit 500}}
 :mapping {:datasource {:name [:value "Netflix"]
                        :global-id [:value "source-netflix"]}
           :items [{:global-id [:field "show_id"]
                    :features [{:facts []
                                :locations []
                                :contexts []
                                :dates []
                                :texts []}]}]}}
```

Common operations seen in `docs/Import.md` and sample data:

- `[:field "column"]`: read the source column.
- `[:value "constant"]`: use a constant value.
- `[:convert ["column" ", "]]`: split or convert a delimited column.
- `[:id-generate ["column" :text] :name]`: generate stable IDs from source values.
- `[:date-schema "MMM. dd, YYYY" [:field "date_added"]]`: parse a date using a declared source expression.

## Feature Buckets

- `:facts`: measurable or descriptive attributes, usually with `:value`, `:name`, and `:type`.
- `:contexts`: categorical entities such as people, countries, ratings, topics, or types.
- `:locations`: geographic values.
- `:dates`: event dates such as `:type [:value "occured-at"]`.
- `:texts`: free text fields, often used for descriptions or full text search.

Keep bucket semantics stable. Search, filters, visualizations, and attribute characteristics depend on these distinctions.

## Useful Examples

- `docs/Import.md`: compact mapping example.
- `data/netflix/`: Netflix sample mapping and CSV helpers.
- `data/roadmap/`: roadmap sample mapping and data generation.
- `tools/cli-data-transformer/examples/`: command-line transformer examples.

## Validation Points

- Mapping syntax is valid EDN.
- Schema in `schema.cljc` accepts intended input and rejects malformed input.
- Resulting data shape matches `spec.cljc`.
- Generated IDs are deterministic.
- Date parsing handles empty, invalid, and expected values.
- EXPDB tests still cover import, delete, index, dump, attribute values, data tiles, neighborhoods, and ranges.
