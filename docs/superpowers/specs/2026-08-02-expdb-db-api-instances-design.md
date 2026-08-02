# expdb db-api Instances Contract Design

Date: 2026-08-02

Fixes issue #92.

## Goal

Make expdb bucket download and upload (the settings UI's backup/restore
feature) work on every bundle, by giving `db-api` an instances contract that
actually holds, and gate the server bundle's clj-kondo errors in CI once it
does.

## The defect

`plugins/backend/de/explorama/backend/expdb/persistence/db_api.cljc` treats
`(simple/instances)` and `(indexed/instances)` as maps of bucket name to
instance. Only the browser bundle satisfies that:

| Bundle | `indexed/instances` | `simple/instances` |
|---|---|---|
| browser (`backend_indexed.cljs:165`, `backend_simple.cljs:51`) | `@store` — a map | `@store` — a map |
| server (`backend_indexed.clj:190`, `backend_simple.clj:62`) | seq of config keys | vector of sqlite table names |
| electron (`backend_indexed.cljs:193`, `backend_simple.cljs:75`) | seq of config keys | vector of table-row maps |

Every `(get (instances) bucket-name)` on server and electron is therefore
`nil`, and the `dump`/`set-dump` calls receive `nil` instances. The settings
tab that drives these routes (`plugins/frontend/de/explorama/frontend/expdb/settings.cljs`,
mounted by all three bundles' `user_settings_comp.cljs`) is user-reachable
broken functionality on the server *and* on electron — the issue names only
the server because it was found by enabling that bundle's clj-kondo check.

Three further findings shape the fix:

- **The browser has the same family of bug on upload.** Its `@store` holds
  only buckets instantiated this session, so restoring a dump that contains
  an untouched bucket does `(get @store name)` → `nil`. Restore onto a fresh
  instance — the main purpose of a backup — fails everywhere, not just on
  the JVM.
- **Sqlite table names cannot serve as bucket names.** `table-name`
  (`bundles/server/backend/de/explorama/backend/expdb/persistence/common_sqlite.clj:10`)
  prefixes `indexed_` and collapses `[_/-]+` to `_`, which is lossy: table
  `indexed_a_b` could be bucket `a-b`, `a_b`, or `a/b`. Any fix that derives
  the simple-bucket list from `sqlite_master` shows mangled names and breaks
  cross-bundle restore.
- **`bundles/server/backend/de/explorama/backend/expdb/persistence/db_api.cljs`
  is dead code.** JVM Clojure never loads `.cljs` files, so this pre-refactor
  fossil (unchanged since the `init` commit) has never shadowed the shared
  `.cljc`. It is also exactly what clj-kondo flags. It is deleted, not fixed.

`instances` has no callers outside `db-api` (verified by grep across
`plugins/` and `bundles/`), so its contract is free to change.

## Decisions

### Lookups go through `buckets/new-instance`

`download-bucket`, `upload-bucket`, and `upload-expdb` obtain instances via
`(buckets/new-instance bucket-name :simple/:indexed)`
(`plugins/backend/de/explorama/backend/expdb/buckets.cljc:6`) — the accessor
the rest of the codebase already uses (`persistence/shared.cljc`). It is
deterministic create-or-get on every bundle, which also fixes the browser's
fresh-instance upload failure. Its guards are correct for this use: simple
buckets are by definition the ones absent from `explorama-bucket-config`,
indexed ones present.

### Indexed enumeration comes from config; `indexed/instances` is deleted

Indexed buckets are exactly the keys of `explorama-bucket-config` —
`load-buckets` and `download-expdb` already iterate it. The three
`indexed/instances` implementations are deleted rather than repaired; no
caller remains.

### Simple enumeration: persistent bucket registry on server and electron

The simple sqlite db gains a registry table:

```sql
CREATE TABLE IF NOT EXISTS expdb_buckets (bucket TEXT PRIMARY KEY);
```

`new-instance` does `INSERT OR IGNORE` of the *original* bucket name;
`instances` rebuilds the name→instance map from the registry; `del-bucket`
deletes the row alongside dropping the data table. Enumeration therefore
survives restarts, so a backup taken right after boot is complete — the
session-registry alternative (browser parity) was rejected because it
silently misses untouched buckets in exactly the backup scenario this
feature exists for.

No collision with data tables: every data table name starts with `indexed_`
(the `table-name` prefix applies to both dbs), `expdb_buckets` does not.

The browser keeps its `@store` enumeration. Its backup-after-fresh-reload
can still miss untouched simple buckets — same class of flaw on the
IndexedDB side, out of scope here and recorded as a follow-up on issue #92.

### The server joins the clj-kondo error gate

`tools/clj-kondo-report/check-errors.clj` currently gates browser, electron
and plugins only; its comment cites this issue for the server exemption.
Additionally, `.clj-kondo/config.edn:8-9` sets `:type-mismatch` to `:off`
for the `db-api` namespace, because the broken contract made the findings
non-actionable at the call sites. Both are reverted: the server check joins
the gate's `checks` vector, and the `:config-in-ns` silencing (plus its
explanatory comment) is removed so the linter guards the repaired contract
(acceptance criterion 3).

## Files

| File | Change |
|---|---|
| `plugins/backend/.../persistence/db_api.cljc` | lookups via `buckets/new-instance`; enumeration per above |
| `bundles/server/backend/.../persistence/db_api.cljs` | **deleted** (dead code) |
| `bundles/server/backend/.../persistence/backend_simple.clj` | registry table, register on `new-instance`, deregister on `del-bucket`, `instances` from registry |
| `bundles/server/backend/.../persistence/backend_indexed.clj` | `instances` deleted |
| `bundles/electron/backend/src/.../persistence/backend_simple.cljs` | same registry, better-sqlite3 API |
| `bundles/electron/backend/src/.../persistence/backend_indexed.cljs` | `instances` deleted |
| `bundles/browser/backend/.../persistence/backend_indexed.cljs` | `instances` deleted |
| `tools/clj-kondo-report/check-errors.clj` | server joins the error gate |
| `.clj-kondo/config.edn` | `db-api` `:type-mismatch` silencing removed |

## Testing

This namespace has zero existing coverage in any suite.

- **Server backend suite** (real sqlite, the 130/0/0 harness): round-trip —
  create simple buckets with mangling-hostile names (`a-b`, containing `/`),
  `load-buckets` lists the original names, `download-expdb` → wipe →
  `upload-expdb` → data intact; registry survives instance re-creation.
- **Electron backend suite** (better-sqlite3, 112/0/0): same round-trip.
- **Browser suite**: upload into a bucket that was never instantiated this
  session — the case `@store` used to fail.

Acceptance criterion 2 ("verified against a running server bundle") runs
through the docker compose harness (`bundles/server/docker/README.md`); if
the harness cannot run in the working environment, that check is handed to a
human explicitly rather than claimed.
