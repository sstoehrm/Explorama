---
name: explorama-expdb
description: Operations of Explorama's expdb plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama expdb operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:expdb/buckets`

The database's buckets with their datasources, to verify an import.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/buckets" \
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

## `:expdb/cancel`

Discard the staged import transaction. Only acts on an import this user staged through :expdb/set-mapping; refuses (:invalid-params) if nothing is staged or another user (including the import dialog, which shares the same staging slot) staged it.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/cancel" \
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

## `:expdb/commit`

Commit the staged import into the shared database. Only acts on an import this user staged through :expdb/set-mapping; refuses (:invalid-params) if nothing is staged or another user (including the import dialog, which shares the same staging slot) staged it. Ask the user before calling this. Returns the import summary.

Runs on the backend, answers within 600000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/commit" \
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

## `:expdb/delete`

Discard a staged file and its transaction. Only acts on the file this user staged through :expdb/set-mapping; refuses (:invalid-params) if nothing is staged, a different file is staged, or another user (including the import dialog, which shares the same staging slot) staged it.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/delete" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:file-name "text"}}'
```

Input schema:

```clojure
[:map {:closed true} [:file-name [:string {:min 1}]]]
```

Output schema:

```clojure
:any
```

## `:expdb/set-mapping`

Validate :mapping (a full descriptor, usually the suggestion with edits) against the data-transformer schema and stage the import in a transaction. Returns the staging result including :mapping-errors. Nothing is persisted until :expdb/commit.

Runs on the backend, answers within 600000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/set-mapping" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:file-name "text", :mapping <value>}}'
```

Input schema:

```clojure
[:map {:closed true} [:file-name [:string {:min 1}]] [:mapping map?]]
```

Output schema:

```clojure
:any
```

## `:expdb/set-options`

Re-analyze a staged file with new csv options. Returns :suggestion and :preview.

Runs on the backend, answers within 120000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/set-options" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:file-name "text", :csv {:separator "text", :quote "text"}}}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:file-name [:string {:min 1}]]
 [:csv [:map {:closed true} [:separator string?] [:quote string?]]]]
```

Output schema:

```clojure
[:map [:suggestion :any] [:preview :any]]
```

## `:expdb/upload`

Stage a csv file's raw :content under :file-name and analyze it. Returns the heuristic mapping :suggestion (a data-transformer descriptor with :meta-data and :mapping) and a :preview of the first parsed rows. Pass :csv to override the separator and quote.

Runs on the backend, answers within 120000 ms.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/expdb/upload" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:file-name "text", :content "text", :csv {:separator "text", :quote "text"}}}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:file-name [:string {:min 1}]]
 [:content string?]
 [:csv
  {:optional true}
  [:map {:closed true} [:separator string?] [:quote string?]]]]
```

Output schema:

```clojure
[:map [:suggestion :any] [:preview :any]]
```
