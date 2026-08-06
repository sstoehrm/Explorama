# EXPDB SQLite → RocksDB Swap — Design

**Date:** 2026-08-06
**Status:** Approved
**Context:** The expdb storage layer uses SQLite as a key-value store (EDN
strings in `(key TEXT PRIMARY KEY, value TEXT)` tables, one table per
bucket) in the server bundle (JVM, `sqlite-jdbc`) and the electron bundle
(Node, `better-sqlite3`). PR #112 landed a benchmark harness with committed
SQLite baselines (`benchmarks/results/*-sqlite.edn`) so this swap can be
judged by before/after numbers. The browser bundle (IndexedDB) is not
affected.

## Decisions

- **Fresh start, no data migration.** New RocksDB databases start empty;
  old sqlite files are no longer read. Users re-import or restore via the
  existing expdb bucket upload/download mechanism.
- **Hard swap.** `common-sqlite` namespaces and the sqlite dependencies are
  removed. No config flag, no dual backend.
- **Same facade.** The six-function surface keeps its exact signatures and
  return shapes; everything above it (backend_simple, backend_indexed,
  middleware, protocols, shared tests) is untouched except for the require
  and the `db-key` constants.

## Libraries

- **Server (JVM):** `org.rocksdb/rocksdbjni {:mvn/version "10.8.3"}` —
  official binding, prebuilt natives for linux/mac/win bundled in the jar.
- **Electron (Node):** `@harperfast/rocksdb-js` `^2.7.0` — N-API binding
  (ABI-stable across Node/Electron versions, so the better-sqlite3
  version-pin dance disappears), prebuilds for all platforms via
  optionalDependencies, Apache-2.0, engines `^22.18 || >=24` (local Node
  24.15; Electron 39.8.5 ships a satisfying Node 22.x — verify at
  implementation time). All calls use the `*Sync` API variants so the
  electron storage layer stays synchronous.

## Storage layout

- One RocksDB **directory** per db-key. The `db-key` constants in
  `backend_simple`/`backend_indexed` change from
  `"de.explorama.backend.expdb.<kind>.sqlite3"` to
  `"de.explorama.backend.expdb.<kind>.rocksdb"`.
- One **column family per bucket**, named by the existing `table-name`
  sanitization helper (carried over verbatim). `db-drop-table` becomes
  drop-column-family. CFs are created on demand and discovered at open via
  `listColumnFamilies` (server) / the `columns` API (electron).
- Values remain EDN: keys and values are `pr-str`ed strings stored as
  UTF-8 bytes, `read-string`ed on the way out. Read results keep the
  `{key value}` map shape; a missing key is simply absent from the result.
- Operations on a bucket whose CF does not exist yet: reads (`dump`,
  `db-get+`) return `{}` without creating it; writes (`db-set+`,
  `set-dump`) create it; `db-drop-table` on a missing CF is a no-op
  success — mirroring sqlite's `CREATE TABLE IF NOT EXISTS` behavior.

## Components

### `common_rocksdb.clj` (bundles/server/backend/.../persistence/)

Public fns (signatures identical to today's `common-sqlite`):
`table-name`, `dump [db-key bucket]`, `set-dump [db-key bucket data]`,
`db-set+ [db-key bucket data]`, `db-get+ [db-key bucket keys]`,
`db-del+ [db-key bucket keys]`, `db-drop-table [db-key bucket]`.

New (replaces the per-op `create-db`/`db-close` pair):
- `open-db [db-key]` — get-or-open from a private registry atom
  `{db-key {:db RocksDB :cfs {cf-name ColumnFamilyHandle}}}`. Opening
  lists existing CFs and opens them all (rocksdbjni requires it);
  `ensure-cf` creates a missing CF on first use.
- `close-db! [db-key]` — closes CF handles + DB and evicts the registry
  entry. For tests and benchmarks; production never closes.

Writes batch through `WriteBatch` + `write` (one commit per `db-set+` /
`set-dump` / `db-del+` call, mirroring the sqlite transaction-per-call
semantics). `dump` iterates the CF with a `RocksIterator`. `set-dump`
clears nothing (same merge semantics as sqlite's INSERT OR REPLACE loop —
`dump`/`set-dump` round-trip behavior is unchanged).

### `common_rocksdb.cljs` (bundles/electron/backend/src/.../persistence/)

Same public surface. Registry holds `RocksDatabase` instances opened with
`db.open()`; buckets map to columns; all ops use `getSync` / `putSync` /
`removeSync` / `transactionSync`; `dump` uses `getRange` sync iteration.

### Consumers (both bundles)

`backend_simple` and `backend_indexed`: require `common-rocksdb` instead
of `common-sqlite`; `db-key` constants renamed as above. Any inlined
sqlite usage in `backend_simple` (e.g. the server's `get+ [_]` arity,
which runs its own SELECT via `create-db`/`collect-result`) is replaced by
a call to the facade's `dump`. No other changes.

### Removals

- `bundles/server/backend/.../persistence/common_sqlite.clj`
- `bundles/electron/backend/src/.../persistence/common_sqlite.cljs`
- `org.xerial/sqlite-jdbc` from `clj.deps.edn`; `org.clojure/java.jdbc`
  too if grep shows no other consumer (if it has one, it stays).
- `better-sqlite3` from `bundles/electron/backend/package.json`, and the
  `npm install better-sqlite3@12 --no-save --no-package-lock` lines from
  the Makefile's `test-backend` and `bench-backend` targets.
- CLAUDE.md's note about the better-sqlite3 test swap.

## Tests

- The shared middleware/db-api suites are the correctness harness and run
  unchanged — they exercise the swap through the protocols.
- The two per-bundle `middleware/indexed_db_test` setup helpers change:
  scratch `db-key` becomes a directory name
  (`"de.explorama.backend.expdb.indexed-test.rocksdb"`), cleanup becomes
  `close-db!` + recursive delete.
- One new focused test namespace per bundle for `common-rocksdb`:
  round-trip (`db-set+` → `db-get+` → `dump`), missing keys absent from
  results, `db-del+`, drop-and-recreate bucket, handle reuse (two calls,
  one registry entry), `close-db!` + reopen. Registered in both runner
  namespaces (`test_runner` and `test_runner_ci`) per bundle.

## Benchmark (acceptance)

- Runners only: require `common-rocksdb`, `:backend :rocksdb`, `:reset!`
  becomes `close-db!` + recursive delete of the scratch directory.
  Scenario code stays byte-identical.
- Run both bundles' benchmarks; commit the `-rocksdb.edn` results; include
  `bb benchmarks/compare.bb.clj <sqlite>.edn <rocksdb>.edn` tables for both
  bundles in the PR description.

## Error handling

Per-operation try/catch, log via timbre, return
`{:success false :message "<op> - see logs for details" :error-reason ...}`
— the same contract as today. Open failures (locked directory, corruption)
surface through the same path. RocksDB holds an exclusive lock per
directory: two processes sharing a db-key now fail loudly at open, which
matches the current single-process deployment reality.

## Risks

- `@harperfast/rocksdb-js` is young (v2.x) but actively maintained
  (release two days before this design) and N-API-based; the sync API
  variants cover the whole surface we need.
- Electron runtime Node version must satisfy `^22.18 || >=24` — verify
  Electron 39.8.5's Node at implementation; if it falls short, the
  electron app runtime (already unsupported per issue #28) is affected but
  dev/test flows on system Node 24 are not.
- rocksdbjni fattens the server uberjar by roughly 50 MB of bundled
  natives — accepted.

## Out of scope

- Data migration from existing sqlite files.
- Browser bundle (IndexedDB).
- Any change to scenario code under `benchmarks/src`.
- RocksDB tuning (compression, block cache, bloom filters) beyond library
  defaults — revisit only if the benchmark comparison disappoints.
