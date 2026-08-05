# Consolidate remaining platform-conditional date code into unification.time

**Date:** 2026-08-03
**Status:** approved
**Depends on:** the java.time migration branch (`feature/java-time-migration`,
PR #104); this branch is stacked on it.
**Scope decision:** "shims + last cljs-time users" — the `#?` shims and the
final direct cljs-time consumers move behind the façade. The js/Date display
layer, `jwt.clj`, and `logging.cljc` are explicitly out of scope.

## Goal

After this change, `de.explorama.shared.common.unification.time` is the only
namespace in the repository that requires cljs-time or imports java.time
(JVM-only `jwt.clj`/`jwt_test.clj` excepted, out of scope). Every platform
behavior lives in one façade and is pinned by the dual-platform
characterization suite, so platform drift fails a test instead of shipping.

## Survey this design rests on

Platform-conditional date code outside the façade today:

| Site | Content |
|---|---|
| `plugins/shared/de/explorama/shared/data_format/dates.cljc` | 20 private `#?` shims — a second copy of façade behavior |
| `plugins/shared/de/explorama/shared/data_transformer/util.cljc` | `#?` formatter/parse/unparse (LocalDate flavor on `:clj`) |
| `plugins/shared_test/de/explorama/shared/data_format/date_filter_test.cljc` | `#?` fixture helpers (`now*`, `minus-*`, `unparse`) |
| `plugins/backend/de/explorama/backend/expdb/legacy/search/attribute_characteristics/date_utils.cljc` | local format constants + dead, broken `filter-date-ranges`/`filter-months` twins (no callers, broken on both platforms since before the migration) |
| `plugins/frontend/de/explorama/frontend/projects/protocol/core.cljs` | direct cljs-time (`from-long`, `formatters :date-hour-minute-second-fraction`) |
| `plugins/frontend/de/explorama/frontend/projects/views/project_card.cljs` | direct cljs-time (`from-long`, locale display formatters) |

## Design

### 1. Façade API additions (`unification/time.cljc`)

New public fns, each `#?`-split internally, each with clj-time-compatible
semantics on both platforms:

- `get-day`, `get-hour`, `get-minute`, `get-second` — field accessors named
  after the existing `get-month`/`get-year` convention (avoids shadowing
  `clojure.core/second`).
- `week-number-of-year` (`WeekFields/ISO` on `:clj`), `day-of-week` (ISO 1–7).
- `minus-days`, `minus-months`, `minus-years` — `[dt n]`.
- `first-day-of-the-month`, `last-day-of-the-month`.
- `today-at-midnight` (UTC on `:clj`, matching the migration's `now` policy).
- `within?` — the existing public var is a broken `(partial convert-and-apply …)`
  shape with zero consumers; it becomes a real 3-arity fn `[start end x]`,
  start-inclusive/end-exclusive, converting native dates like
  `before?`/`after?`/`equal?` do. The private `within?*` merges into it.
- `formatters` gains `:date-hour-minute-second-fraction`
  (`yyyy-MM-dd'T'HH:mm:ss.SSS` on `:clj`; cljs-time already has the key) so
  `projects/protocol` can go through the façade and the key is parity-tested.

No existing public fn changes signature or behavior.

### 2. `dates.cljc` — date shims gone

All 20 private shims are deleted; the body calls the façade
(`time/get-day`, `time/minus-months`, `(apply time/date-time ordinals)`,
`(time/unparse (time/formatters :date-hour-minute-second) dt)`, …). The ns
requires only `unification.time` and `filter-functions` — no cljs-time, no
java.time. The only remaining `#?` in the file is `to-int`'s pre-existing
number-parsing conditional (`Integer/parseInt` vs `js/parseInt`), which is
deliberately kept: rewriting it to a platform-neutral form would change
`:cljs`'s behavior on malformed input.

### 3. `util.cljc` — thin aliases

Keeps `string->char` (unrelated to dates, used by the CSV parsers) and
aliases the date fns from the façade:
`(def formatter time/formatter)`, `(def parse time/parse)`,
`(def unparse time/unparse)`, `(def date-format time/date-format)`.
Zero call-site churn in `mapping.cljc`/`suggestions.cljc`/the CLI.
Behavior note: `parse` on `:clj` now returns `LocalDateTime` (parse-defaulted)
instead of `LocalDate`; the only consumer (`resolve-date-schema`) immediately
unparses with a `yyyy-MM-dd` formatter, so output is byte-identical. The
façade formatter initially forced time-field (`HOUR_OF_DAY`/`MINUTE_OF_HOUR`/
`SECOND_OF_MINUTE`) defaults via `.parseDefaulting`, which rejected am/pm
schemas like `"dd/MM/yyyy hh:mm a"` (`CLOCK_HOUR_OF_AMPM` resolution conflicts
with the defaulted `HOUR_OF_DAY`) — fixed by dropping those three
`.parseDefaulting` calls and resolving time optionally in `parse` itself
(`.atTime` when the parsed field set supports `HOUR_OF_DAY`, else
`.atStartOfDay`).

### 4. `date_filter_test.cljc` — fixtures via the façade

The `#?` fixture helpers are replaced by direct façade calls
(`time/now`, `time/minus-days`, `(time/unparse (time/formatters :year-month-day) d)`).
Assertions and test data unchanged — the suite keeps guarding filter behavior.

### 5. Legacy `date_utils.cljc`

- The three local format constants are replaced by the façade's
  `date-format`/`year-month-format`/`year-format` (same strings since the
  migration).
- The dead `filter-date-ranges`/`filter-months` twins are deleted (no callers
  repo-wide; broken on both platforms since before the migration).
- The stale `;Different formats for date-fns and clj-time` comment goes with
  them.
- Live fns keep their behavior; anything they did through `t/` already goes
  through the façade.

### 6. `projects` frontend files — last direct cljs-time users

`protocol/core.cljs` and `views/project_card.cljs` swap
`cljs-time.coerce`/`cljs-time.format` requires for the façade
(`time/from-long`, `time/formatter`, `time/unparse`,
`(time/formatters :date-hour-minute-second-fraction)`). Display output is
unchanged — the façade's `:cljs` branch is the same cljs-time underneath.

### 7. End-state assertion (verified in CI-visible greps)

- `cljs-time` appears in requires of exactly one source namespace:
  `unification/time.cljc`.
- `java.time` imports outside `unification/time.cljc` exist only in
  `jwt.clj`/`jwt_test.clj` (out of scope, JVM-only).

## Testing

- `time_test.cljc` grows a deftest per promoted fn — field accessors, week
  number (incl. an ISO year-boundary date), day-of-week, the three `minus-*`
  fns, month boundaries (`first/last-day-of-the-month` incl. leap February),
  `today-at-midnight` (shape assertion: midnight, not a fixed date), the
  repaired `within?` (interior/exterior/boundary), and the
  `:date-hour-minute-second-fraction` formatter key. All assertions are
  platform-neutral and run on JVM + all four cljs targets.
- `date_filter_test.cljc` continues to pass unchanged (assertion-wise) — it is
  the behavioral guard for `dates.cljc`'s refactor.
- Suites: all five bundles (shared `.cljc` changes compile everywhere) +
  clj-kondo vs baseline. e2e not required: no production behavior changes —
  but the browser suite covers the `projects` display formatting via unit
  tests where they exist.

## Non-goals

- No js/Date display consolidation (`woco/util/date.cljs`,
  `reporting/util/date.cljs`, scattered call sites) — future work; it needs a
  deliberate local-time-vs-UTC design.
- `jwt.clj` stays on direct `Instant` arithmetic.
- `logging.cljc` keeps its goog.date usage.
- No behavior changes anywhere: this is a consolidation, and the only
  intentional semantic edit is repairing the unusable `within?` var and
  deleting dead code.
