# JVM-side migration from clj-time (Joda) to java.time

**Date:** 2026-08-02
**Status:** approved
**Scope decision:** JVM-only swap (option chosen over full removal and
data-transformer-only). cljs-time and every `:cljs` branch stay untouched.

## Goal

Remove the archived clj-time library from every JVM execution path — the server
backend, the CLI data-transformer, and the `:clj` reader-conditional branches of
shared `.cljc` files — using plain `java.time` interop with **no new
dependencies**. Public façade APIs keep their names and signatures; consumer
namespaces do not change.

## Non-goals

- No change to `:cljs` branches or the cljs-time dependency. The browser bundle
  compiles the whole app (including backend logic) to ClojureScript, and the
  electron backend is a Chromium worker window; java.time does not exist there.
- No wrapper library (clojure.java-time, tick). The façade internals are four
  small files; raw interop costs a little verbosity once and buys zero
  transitive deps.
- No timezone features. All dates in the system are naive local dates; the only
  zone decision is keeping `now` at UTC (parity with clj-time).

A later full removal of cljs-time (js-joda / cljc.java-time) remains possible;
this migration is forward-compatible with it because the pattern-semantics
change below will already be done.

## Survey results this design rests on

Source files using clj-time/cljs-time (compiled output excluded):

| File | Platform | Role |
|---|---|---|
| `plugins/shared/de/explorama/shared/common/unification/time.cljc` | both | central façade, 27 consuming namespaces |
| `plugins/shared/de/explorama/shared/data_format/dates.cljc` | both | date parsing/comparison for filters, 19 consumers |
| `plugins/shared/de/explorama/shared/data_transformer/util.cljc` | both | formatter/parse/unparse with user-supplied patterns |
| `plugins/frontend/de/explorama/frontend/projects/{protocol/core,views/project_card}.cljs` | cljs only | out of scope |
| `plugins/backend/de/explorama/backend/abac/jwt.clj` | JVM | token expiry comparison |
| `bundles/server/backend/de/explorama/backend/abac/jwt_test.clj` | JVM | fixtures + `with-redefs` of now |
| `plugins/shared_test/de/explorama/shared/data_format/date_filter_test.cljc` | both | dual-platform test, fixture arithmetic in `:clj` branch |
| `tools/cli-data-transformer/src/.../sandbox.clj` | JVM | exposes clj-time namespaces to mapping files |
| `data-mappings/traffic/divvy.clj` | via sandbox | only mapping using clj-time fns |

Verified facts:

- Consumers stay behind the façades; the only direct date interop found in
  consumers is cljs `js/Date` usage, unaffected here.
- cljs-time maps `"yyyy"` and `"YYYY"` to the identical year token (checked in
  `cljs_time/internal/{parse,unparse}.cljs` of 0.5.2), so shared format
  constants can move to lowercase without a `:cljs` behavior change.
- clj-time's `(formatters :date-hour-minute-second)` is ISO
  `yyyy-MM-dd'T'HH:mm:ss`. Only `:date-hour-minute-second` and
  `:year-month-day` are consumed from the formatter table.
- In Joda, `Y` is year-of-era (weekyear is `x`); in java.time, `Y` **is**
  week-based-year. Empirically: 2023-01-01 formats as `2023` with Joda `YYYY`
  and would hit weekyear semantics under java.time.

## Design

### 1. `unification/time.cljc` (`:clj` branch)

- Internal representation: `java.time.LocalDateTime`. Every `parse` resolves to
  it regardless of precision: formatters are built with parse-defaulting
  (month 1, day 1, time 00:00), so `yyyy` and `yyyy-MM` parses yield a full
  `LocalDateTime` and comparisons never mix temporal types.
- `now`: UTC explicitly (`LocalDateTime/now ZoneOffset/UTC`). clj-time's `now`
  is UTC; a naive `now` would silently shift semantics on non-UTC machines.
- `before?/after?/equal?` → `isBefore/isAfter/isEqual`; `earliest/latest` via
  `compareTo`.
- `within?` implemented to match the shared test's assertions; the unchanged
  cljs side is the reference behavior.
- `to-date/from-date/to-long/from-long`: bridge `java.util.Date`/epoch millis
  through `Instant` at UTC.
- `number-of-days-in-the-month` → `YearMonth#lengthOfMonth`.
- `date-protocol?` → `(instance? java.time.temporal.Temporal obj)`.
- `formatters` shrinks to a map of the two consumed keys
  (`:date-hour-minute-second` → `yyyy-MM-dd'T'HH:mm:ss`, `:year-month-day` →
  `yyyy-MM-dd`) instead of emulating Joda's table.
- All `DateTimeFormatter`s built with `Locale/ENGLISH` pinned.

### 2. `data_format/dates.cljc` (`:clj` branch)

- `t/date-time` with 1–6 ordinals → `LocalDateTime/of` with explicit defaults
  (month/day default 1, time fields 0).
- Week number via `WeekFields/ISO` (`weekOfWeekBasedYear`), matching Joda's
  `weekOfWeekyear`. Day-of-week stays ISO 1–7 (both libraries agree).
- Period arithmetic → `minusDays/minusMonths/minusYears`;
  `first/last-day-of-the-month` via `TemporalAdjusters`;
  `today-at-midnight` → `LocalDate/now UTC` + `atStartOfDay`.

### 3. `data_transformer/util.cljc` (`:clj` branch)

- `formatter` builds a `DateTimeFormatter` with `Locale/ENGLISH`. Pinning the
  locale fixes a latent bug: `MMM` parsing is locale-sensitive and currently
  depends on the host machine's locale.
- `parse` yields `LocalDate`; `unparse` formats it. The formatter uses the same
  parse-defaulting (month 1, day 1), so a year- or month-only mapping pattern
  still resolves to a `LocalDate`. Mapping date patterns are date-only today
  (`resolve-date-schema` carries a TODO for time handling; that TODO stands,
  unchanged).

### 4. Pattern contract change (user-visible)

JVM pattern semantics become TR35 (java.time):

- Format constants `"YYYY-MM-dd"` / `"YYYY-MM"` / `"YYYY"` → lowercase `y` in
  `unification/time.cljc` and `data_transformer/util.cljc`. Byte-identical
  output on both platforms (cljs-time treats them the same;
  `date-format-placeholder`, the lower-cased constant, is unchanged).
- Repo-wide sweep of format-string literals for uppercase `Y`;
  `data/netflix/netflix.clj` changes `"MMM. dd, YYYY"` → `"MMM. dd, yyyy"`.
  The traffic mappings already use lowercase.
- `data-mappings/traffic/README.md` gains one line documenting that
  `:date-schema` patterns use java.time (`DateTimeFormatter`) syntax when run
  through the CLI.

### 5. `jwt.clj` and its test

- `exp` claim → `Instant/ofEpochMilli`; validity check via `isBefore` /
  `isAfter` on `Instant`s.
- The current-time lookup stays a redef-able var so `jwt_test.clj` keeps its
  `with-redefs` pattern; test fixtures become `Instant`s.

### 6. CLI sandbox and divvy mapping

- `sandbox.clj` stops exposing `clj-time.format` / `clj-time.coerce`. It
  exposes a small purpose-built helper namespace instead (functions:
  `formatter`, `parse`, `to-long`) backed by java.time.
- `data-mappings/traffic/divvy.clj` switches to those helpers in the same
  change.
- This is a deliberate breaking change to the sandbox contract; any private
  mapping using clj-time needs the equivalent one-line switch.

### 7. Dependency cleanup

- Drop `clj-time` from `bundles/server/clj.deps.edn` and
  `tools/cli-data-transformer/deps.edn` (certainly unused after this).
- Attempt removal from the four cljs-side deps files
  (`bundles/browser/deps.edn`, `bundles/electron/{backend,frontend}/deps.edn`,
  `bundles/server/cljs.deps.edn`): cljs-time is standalone and the cljs
  compiler reads only `:cljs` branches, so removal is expected to work. If any
  build or figwheel tooling loads the `.cljc` on the JVM and breaks, keep the
  dep there with a comment explaining why. Full builds decide.

### 8. Error handling

No strategy change. The façades keep their catch-and-log-nil behavior
(`obj->date-str`, `date-str->obj`); the data-transformer keeps failing
per-feature on bad values. `DateTimeParseException` replaces Joda's
`IllegalArgumentException` inside the façades — no code outside them catches
those exception types specifically (consumers catch `Throwable`).

## Testing & verification

- `date_filter_test.cljc` is the parity harness: it compiles into both
  platforms with identical assertions, so JVM-vs-JS divergence (week numbers,
  comparisons, `within?` boundaries) fails tests instead of shipping. Its
  `:clj` fixture arithmetic moves to java.time.
- All five suites (shared `.cljc` changed → per CLAUDE.md every bundle runs):
  browser, electron backend, electron frontend, server backend, server
  frontend. Read results from `report.xml` per-suite lines, mtime-checked.
- The e2e suite as well: date filtering is core behavior and e2e drives the
  production browser artifact.
- CLI: `check` + `gen` for the four traffic mappings against real source data
  and for `data/roadmap`; verify the netflix pattern fix by parsing sample
  rows with the new formatter.
- clj-kondo against the baseline (0 errors / ~1087 warnings in `plugins/`).
