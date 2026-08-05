# EXPDB Storage Benchmark — Design

**Date:** 2026-08-05
**Status:** Approved
**Context:** We plan to replace SQLite with RocksDB as the expdb storage
backend in the server bundle (JVM, `sqlite-jdbc`) and the electron bundle
(Node, `better-sqlite3`). Before touching the storage layer we need a
benchmark that measures the current SQLite performance, so the swap can be
judged by before/after numbers instead of intuition.

## Goal

A repeatable benchmark harness that:

1. Measures the six-function storage layer (`dump`, `set-dump`, `db-set+`,
   `db-get+`, `db-del+`, `db-drop-table`) directly — the exact surface being
   swapped.
2. Measures one realistic end-to-end scenario (import + query + dump through
   the shared expdb middleware) to confirm storage-level wins translate
   upward.
3. Runs identically in both bundles, writes machine-readable results, and
   ships a compare tool that turns two result files into a speedup table.

Non-goals: benchmarking the browser bundle (IndexedDB is not being swapped),
concurrency/load testing, CI integration.

## Layout

New top-level `benchmarks/` directory, kept off all production classpaths:

```
benchmarks/
  src/de/explorama/benchmarks/expdb/
    harness.cljc      ; timing loop, stats, results-file writing
    data.cljc         ; seeded synthetic data generators (KV pairs + events)
    scenarios.cljc    ; KV micro-scenarios against an injected storage-fn map
    e2e.cljc          ; import/query scenario via the real expdb middleware
  results/            ; one EDN file per run (committed deliberately)
  compare.bb.clj      ; babashka: diff two result files, print speedup table
```

Per-bundle entry points that know the current storage namespace:

- **Server:** `bundles/server/bench/de/explorama/benchmarks/expdb/runner.clj`
  plus a `:bench` alias in `clj.deps.edn` with
  `:extra-paths ["bench" "../../benchmarks/src"]`.
  Run: `clojure -Sdeps "$(cat clj.deps.edn)" -M:bench`.
- **Electron:** `bundles/electron/backend/bench/de/explorama/benchmarks/expdb/runner.cljs`
  compiled to a Node script the same way the CI test runner is, wired as
  `make bench-backend`.

When the RocksDB swap lands, only the two runners change (require the new
storage namespace, pass `:backend :rocksdb`); scenario code stays
byte-identical so before/after numbers stay apples-to-apples.

## Storage-fn injection

The runner hands the harness a map of the storage functions plus a reset
hook, e.g.:

```clojure
{:set+     (fn [bucket data] ...)
 :get+     (fn [bucket keys] ...)
 :del+     (fn [bucket keys] ...)
 :dump     (fn [bucket] ...)
 :set-dump (fn [bucket data] ...)
 :drop     (fn [bucket] ...)
 :reset!   (fn [] ...)}   ; drop bucket / delete scratch db file
```

Scenarios only ever call through this map, so they are storage- and
platform-agnostic.

## KV micro-scenarios

Run against a scratch DB path (under the tmp/scratch dir, deleted
afterward):

| Scenario | Shape |
|---|---|
| `write-batch` | one `set+` of N pairs into an empty bucket, N ∈ {100, 1k, 10k} |
| `write-large` | one `set+` of 1k pairs with ~5KB values |
| `read-point` | 100 single-key `get+` calls against a preloaded 10k bucket |
| `read-batch` | one `get+` of N keys, N ∈ {100, 1k} |
| `read-large` | one `get+` of 1k keys with ~5KB values |
| `dump-all` | `dump` of the 10k bucket |
| `set-dump` | bulk restore of 10k pairs |
| `delete-batch` | `del+` of 1k keys |

Data shape:

- Values are ~200-byte EDN maps (the large-value cases use ~5KB maps).
- Keys mimic the real shape:
  `"/de.explorama.backend.expdb/dt/default/data/<hash>"`.
- All data comes from a small seeded PRNG implemented in `data.cljc`
  (portable across JVM and JS), so every run generates identical inputs.

Timing protocol: 1 warmup iteration + 5 measured iterations per scenario;
`:reset!` between iterations of write scenarios; report min/median/mean/max
milliseconds. The compare tool reads the median.

## End-to-end scenario

Mirrors the existing `indexed-db-test` setup: redefine the backend's
`db-key` to a scratch path, instantiate `backend-indexed` with the same
bucket config the tests use, reset the index/cache atoms. Then time three
phases over ~20k generated events in the data-transformer format:

1. `e2e-import` — `de.explorama.backend.expdb.persistence.shared/transform->import`
2. `e2e-query` — via the indexed backend's `data-tiles` protocol fn: fetch
   every imported data tile in batches of 50 tile keys
3. `e2e-dump` — full bucket dump

The middleware is shared `.cljc`, so the same scenario runs in both bundles.

## Results & comparison

Each run writes `benchmarks/results/<timestamp>-<bundle>-<backend>.edn`:

```clojure
{:timestamp "2026-08-05T12:00:00Z"
 :git-sha "5df3ce8"
 :bundle :server            ; or :electron
 :backend :sqlite           ; or :rocksdb
 :scenarios {:write-batch-10k {:iterations 5
                               :ms {:min 12.1 :median 13.0 :mean 13.4 :max 15.2}}
             ...}}
```

plus a human-readable table on stdout.

`bb compare.bb.clj before.edn after.edn` prints per-scenario median ms side
by side with a speedup ratio, and warns when the two files' scenario sets or
bundles differ (comparisons across bundles are not meaningful).

Result files are committed deliberately: the SQLite baseline runs produced
while landing this harness are the "before" half of the RocksDB comparison.

## Error handling

- A storage op returning `{:success false ...}` aborts the run immediately
  with the failing scenario named — failures are never silently timed.
- Scratch DB files are cleaned up on completion.

## Verification

- Generator determinism is asserted by the harness itself at startup
  (generate a sample twice with the same seed, abort if unequal) — no
  bundle test-runner wiring needed for benchmark-only code.
- Acceptance: a real run in both bundles produces plausible baseline SQLite
  result files, committed alongside the harness.
