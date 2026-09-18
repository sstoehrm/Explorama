---
name: explorama-configuration
description: Operations of Explorama's configuration plugin over the agent gateway. Load the explorama skill first for connection and workflow rules.
---

# Explorama configuration operations

Every response is `{:status :ok :result ...}` or `{:status :error :error {:type ... :message ...}}`. Error types: `:ambiguous-session`, `:invalid-params`, `:no-session`, `:op-failed`, `:timeout`, `:unauthorized`, `:unknown-op`, `:workspace-busy`.

## `:configuration/entries`

List the user's stored configuration entries for :config-types, e.g. #{:i18n :layouts :theme :overlayers :topics}.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/configuration/entries" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:config-types #{:keyword}}}'
```

Input schema:

```clojure
[:map {:closed true} [:config-types [:set keyword?]]]
```

Output schema:

```clojure
:any
```

## `:configuration/entry`

One entry.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/configuration/entry" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:config-type :keyword, :config-id <value>}}'
```

Input schema:

```clojure
[:map {:closed true} [:config-type keyword?] [:config-id :any]]
```

Output schema:

```clojure
:any
```

## `:configuration/labels`

Attribute labels in the user's language.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/configuration/labels" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
:any
```

## `:configuration/languages`

Available languages.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/configuration/languages" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
:any
```

## `:configuration/set-entry`

Create or replace one entry; the store validates it per config type. Read the existing entry first and modify it. Returns the stored entry.

Runs on the backend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/configuration/set-entry" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {:config-type :keyword, :config-id <value>, :entry <value>}}'
```

Input schema:

```clojure
[:map
 {:closed true}
 [:config-type keyword?]
 [:config-id :any]
 [:entry :any]]
```

Output schema:

```clojure
:any
```

## `:configuration/theme`

The active theme.

Runs on the frontend.

Request:

```bash
curl -sS -X POST "$EXPLORAMA_URL/api/agent/ops/configuration/theme" \
  -H "Content-Type: application/edn" -H "$EXPLORAMA_AUTH_HEADER" \
  --data-binary '{:user "<user>", :params {}, :client-id "<client-id>"}'
```

Input schema:

```clojure
[:map {:closed true}]
```

Output schema:

```clojure
:any
```
