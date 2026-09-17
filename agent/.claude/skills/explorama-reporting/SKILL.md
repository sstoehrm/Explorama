---
name: explorama-reporting
description: Operations of Explorama's reporting plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama reporting operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:reporting/dashboards`

All dashboards the user may read, with their tiles.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/reporting/dashboards" \
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

## `:reporting/delete-dashboard`

Delete a dashboard. Returns :reporting/dashboards.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/reporting/delete-dashboard" \
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

## `:reporting/delete-report`

Delete a report. Returns :reporting/reports.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/reporting/delete-report" \
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

## `:reporting/reports`

All reports the user may read.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/reporting/reports" \
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

## `:reporting/save-dashboard`

Create or replace a dashboard description (:id, :name, tiles). Read an existing one from :reporting/dashboards and modify it. Returns :reporting/dashboards.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/reporting/save-dashboard" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:desc {:id "text", :name "text"}}}'
```

Input schema:

```clojure
[:map {:closed true} [:desc [:map [:id string?] [:name string?]]]]
```

Output schema:

```clojure
:any
```

## `:reporting/save-report`

Create or replace a report description. Returns :reporting/reports.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/reporting/save-report" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:desc {:id "text", :name "text"}}}'
```

Input schema:

```clojure
[:map {:closed true} [:desc [:map [:id string?] [:name string?]]]]
```

Output schema:

```clojure
:any
```
