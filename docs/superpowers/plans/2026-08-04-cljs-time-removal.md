# cljs-time Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the archived `com.andrewmcveigh/cljs-time` dependency from every bundle by reimplementing the `:cljs` side of the `unification.time` facade on Google Closure Library primitives.

**Architecture:** This is stage 3 of the time-library migration. Stage 1 (`feature/java-time-migration`, PR #104) rebuilt the facade's `:clj` branch on `java.time` and switched all format strings to TR35 semantics. Stage 2 (`feature/unification-time-consolidation`, stacked on stage 1) routed every remaining direct cljs-time consumer through the facade, so cljs-time is now required by exactly one source namespace: `plugins/shared/de/explorama/shared/common/unification/time.cljc`. This stage rewrites that namespace's `:cljs` branches on `goog.date.UtcDateTime`/`goog.date.Interval` (date objects, arithmetic) and `goog.i18n.DateTimeFormat`/`goog.i18n.DateTimeParse` (TR35 pattern formatting/parsing), then deletes the dependency from all four deps files. The dual-platform characterization suite `time_test.cljc` written in stage 1 is the behavioral contract.

**Tech Stack:** ClojureScript, Google Closure Library (ships with ClojureScript — `goog.date`, `goog.i18n`), java.time on `:clj` (unchanged).

**Branch:** create `feature/cljs-time-removal` from `feature/unification-time-consolidation`. Both parent branches are unmerged; if either lands or gets rebased first, rebase this stack accordingly before opening a PR.

## Global Constraints

- **No new dependencies** — no mvn artifacts, no npm packages. The replacement uses only the Closure Library that ClojureScript already bundles.
- **Facade API frozen** — every public var in `unification/time.cljc` keeps its name, arities, and semantics. `time_test.cljc`'s existing assertions are the contract and must pass unmodified (Task 2 only *widens* two tests from `:clj`-only to both platforms; it changes no assertion).
- **All dates are naive UTC** — `now` is UTC, parsed dates are UTC midnights; matches the stage-1 policy.
- **Pattern strings are TR35** (`yyyy-MM-dd` etc.) — already true after stage 1's pattern sweep; do not reintroduce Joda-style `YYYY`.
- **End state:** `grep -rn "cljs-time"` over `plugins/`, `bundles/**/*.edn`, `bundles/**/{frontend,backend,shared}` source finds nothing (compiled output under `resources/public/js/out`, `dist/`, `target/` excluded — those regenerate).
- **Repo style:** codebase is comment-free — no comments referencing cljs-time, goog quirks, or this migration. Match existing file formatting; do not reformat untouched code (repo is not zprint-formatted).
- **Verification honesty (CLAUDE.md):** `npm run test-ci` exits 0 even on failure — always read `report.xml` (check its mtime first) with the per-suite grep; report actual numbers.

## File Structure

- Modify: `plugins/shared/de/explorama/shared/common/unification/time.cljc` — the only source file whose code changes.
- Modify: `plugins/shared_test/de/explorama/shared/common/unification/time_test.cljc` — two reader conditionals removed (Task 2).
- Modify: `bundles/browser/deps.edn`, `bundles/server/cljs.deps.edn`, `bundles/electron/backend/deps.edn`, `bundles/electron/frontend/deps.edn` — one dependency line deleted from each (Task 3).
- No new files, no new test namespaces (nothing to register in `test_runner{_ci}.cljs` — `time_test` is already required in both runners of every bundle since stage 1).

## Reference: the `:cljs` surface being replaced

At the stage-2 tip, the facade's `:cljs` branches alias exactly these cljs-time vars: `t/now`, `t/date-time`, `t/month`, `t/year`, `t/day`, `t/hour`, `t/minute`, `t/second`, `t/week-number-of-year`, `t/day-of-week`, `t/minus` + `t/days|months|years`, `t/first-day-of-the-month`, `t/last-day-of-the-month`, `t/today-at-midnight`, `t/number-of-days-in-the-month`, `t/before?`, `t/after?`, `t/equal?`, `t/within?`, `t/earliest`, `t/latest`, `t/DateTimeProtocol`, `ctco/to-date`, `ctco/from-date`, `ctco/from-long`, `ctco/to-long`, `f/formatter`, `f/parse`, `f/unparse`, `f/formatters`.

cljs-time returns `goog.date.UtcDateTime` objects from all of these — the replacement constructs the *same type* directly, so no consumer ever sees a different object type. Nothing in the repo requires `cljs-time.extend` (verified), so goog date objects have no `IEquiv` today and lose nothing when cljs-time goes away.

Patterns that must keep working through `formatter`/`parse`/`unparse` on `:cljs`:
- The facade's own: `"yyyy-MM-dd"`, `"yyyy-MM"`, `"yyyy"`, and the four `formatters` table entries (`yyyy-MM-dd'T'HH:mm:ss`, `yyyy-MM-dd'T'HH:mm:ss.SSS`, `yyyy-MM-dd`, `yyyyMMdd'T'HHmmss'Z'`).
- `projects` display: `"dd MMM yyyy"` / `"MMM dd yyyy"` (English month abbreviations — `goog.i18n.DateTimeSymbols` defaults to `en`, matching cljs-time's hardcoded English names).
- Data-transformer user schemas (the browser bundle runs the transformer in cljs): numeric TR35 patterns plus `MMM` and `hh:mm a` styles, e.g. `"dd/MM/yyyy hh:mm a"`, `"MMM. dd, yyyy"`.

---

### Task 1: Rewrite the facade's `:cljs` branches on goog.date / goog.i18n

**Files:**
- Modify: `plugins/shared/de/explorama/shared/common/unification/time.cljc`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: the same public facade API as before (names/arities identical). Task 2 relies on `parse` being lenient about numeric field widths and handling `hh:mm a`; Task 3 relies on this file no longer requiring any `cljs-time.*` namespace.

- [ ] **Step 1: Confirm green baseline on the branch**

```bash
git checkout feature/unification-time-consolidation
git checkout -b feature/cljs-time-removal
git add docs/superpowers/plans/2026-08-04-cljs-time-removal.md
git commit -m "docs(plans): cljs-time removal plan"
cd bundles/browser && npm install && npm run test-ci
grep -o '<testsuite name="[^"]*" tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' report.xml | grep -v 'failures="0" errors="0"'
```

Expected: empty grep output (all suites green) and a fresh `report.xml` mtime. If the baseline is red, stop — fix the branch first, don't build on a broken base.

- [ ] **Step 2: Rewrite `time.cljc`**

The `:clj` bodies of every form stay byte-identical. Apply exactly these changes:

**(a) ns form** — drop the three cljs-time requires, add goog requires/imports:

```clojure
(ns de.explorama.shared.common.unification.time
  (:require #?(:clj [taoensso.timbre :refer [error]]
               :cljs [taoensso.timbre :refer-macros [error]])
            #?@(:cljs [[goog.date :as gdate]])
            [clojure.string :as st])
  #?(:clj (:import [java.time Instant LocalDate LocalDateTime LocalTime YearMonth ZoneOffset]
                   [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
                   [java.time.temporal ChronoField Temporal TemporalAdjusters WeekFields]
                   [java.util Date Locale])
     :cljs (:import [goog.date DateTime Interval UtcDateTime]
                    [goog.i18n DateTimeFormat DateTimeParse])))
```

**(b) formatter** — `:cljs` returns a record bundling pattern + format/parse engines (the `:clj` branch is unchanged; shown elided here only for brevity — do not touch it):

```clojure
#?(:cljs
   (defrecord TimeFormatter [pattern format-obj parse-obj]))

#?(:clj
   (defn formatter [fmt-str]
     (-> (DateTimeFormatterBuilder.)
         (.parseCaseInsensitive)
         (.parseLenient)
         (.appendPattern fmt-str)
         (.parseDefaulting ChronoField/MONTH_OF_YEAR 1)
         (.parseDefaulting ChronoField/DAY_OF_MONTH 1)
         (.toFormatter Locale/ENGLISH)))
   :cljs
   (defn formatter [fmt-str]
     (->TimeFormatter fmt-str
                      (DateTimeFormat. fmt-str)
                      (DateTimeParse. fmt-str))))
```

**(c) unparse / parse:**

```clojure
#?(:clj (defn unparse [fmt obj]
          (.format ^DateTimeFormatter fmt obj))
   :cljs (defn unparse [fmt obj]
           (.format (:format-obj fmt) obj)))

#?(:clj (defn parse [fmt s]
          (let [ta (.parse ^DateTimeFormatter fmt ^String s)
                date (LocalDate/from ta)]
            (if (.isSupported ta ChronoField/HOUR_OF_DAY)
              (.atTime date (LocalTime/from ta))
              (.atStartOfDay date))))
   :cljs (defn parse [fmt s]
           (let [scratch (DateTime. 1970 0 1 0 0 0 0)
                 consumed (.parse (:parse-obj fmt) s scratch)]
             (when-not (pos? consumed)
               (throw (ex-info "Unparseable date" {:value s :pattern (:pattern fmt)})))
             (UtcDateTime. (.getFullYear scratch) (.getMonth scratch) (.getDate scratch)
                           (.getHours scratch) (.getMinutes scratch) (.getSeconds scratch)
                           (.getMilliseconds scratch)))))
```

Rationale the implementer needs (not as code comments): `DateTimeParse.parse` mutates its target date and returns the count of consumed characters (0 on failure — throwing there mirrors both cljs-time and `DateTimeParseException`, and the facade's existing `try/catch`es turn it into a logged `nil`). Parsing into a plain `goog.date.DateTime` scratch and transplanting the fields into a fresh `UtcDateTime` keeps the result independent of the host machine's timezone, and the `1970-01-01T00:00:00` scratch supplies the same month-1/day-1/midnight defaults the `:clj` formatter's `.parseDefaulting` gives (`"yyyy"`/`"yyyy-MM"` precisions).

**(d) formatters table** — collapses to one platform-neutral def (replaces the current `#?(:clj (def formatters {...}) :cljs (def formatters f/formatters))`):

```clojure
(def formatters
  {:date-hour-minute-second (formatter "yyyy-MM-dd'T'HH:mm:ss")
   :date-hour-minute-second-fraction (formatter "yyyy-MM-dd'T'HH:mm:ss.SSS")
   :year-month-day (formatter "yyyy-MM-dd")
   :basic-date-time-no-ms (formatter "yyyyMMdd'T'HHmmss'Z'")})
```

**(e) constructors and accessors** — replace each `:cljs` alias with a direct implementation (goog months are 0-based, hence the `dec`/`inc`; `getIsoWeekday` is Monday=0, ISO day-of-week is Monday=1):

```clojure
#?(:clj (defn now [] (LocalDateTime/now ZoneOffset/UTC))
   :cljs (defn now [] (UtcDateTime.)))

#?(:clj (defn date-time
          ([y] (LocalDateTime/of (int y) 1 1 0 0 0))
          ([y m] (LocalDateTime/of (int y) (int m) 1 0 0 0))
          ([y m d] (LocalDateTime/of (int y) (int m) (int d) 0 0 0))
          ([y m d h] (LocalDateTime/of (int y) (int m) (int d) (int h) 0 0))
          ([y m d h mi] (LocalDateTime/of (int y) (int m) (int d) (int h) (int mi) 0))
          ([y m d h mi s] (LocalDateTime/of (int y) (int m) (int d) (int h) (int mi) (int s))))
   :cljs (defn date-time
           ([y] (UtcDateTime. y 0 1 0 0 0 0))
           ([y m] (UtcDateTime. y (dec m) 1 0 0 0 0))
           ([y m d] (UtcDateTime. y (dec m) d 0 0 0 0))
           ([y m d h] (UtcDateTime. y (dec m) d h 0 0 0))
           ([y m d h mi] (UtcDateTime. y (dec m) d h mi 0 0))
           ([y m d h mi s] (UtcDateTime. y (dec m) d h mi s 0))))

#?(:clj (defn month [obj] (.getMonthValue ^LocalDateTime obj))
   :cljs (defn month [obj] (inc (.getMonth obj))))

#?(:clj (defn year [obj] (.getYear ^LocalDateTime obj))
   :cljs (defn year [obj] (.getFullYear obj)))

#?(:clj (defn get-day [obj] (.getDayOfMonth ^LocalDateTime obj))
   :cljs (defn get-day [obj] (.getDate obj)))

#?(:clj (defn get-hour [obj] (.getHour ^LocalDateTime obj))
   :cljs (defn get-hour [obj] (.getHours obj)))

#?(:clj (defn get-minute [obj] (.getMinute ^LocalDateTime obj))
   :cljs (defn get-minute [obj] (.getMinutes obj)))

#?(:clj (defn get-second [obj] (.getSecond ^LocalDateTime obj))
   :cljs (defn get-second [obj] (.getSeconds obj)))

#?(:clj (defn week-number-of-year [obj] (.get ^LocalDateTime obj iso-week-field))
   :cljs (defn week-number-of-year [obj] (.getWeekNumber obj)))

#?(:clj (defn day-of-week [obj] (.getValue (.getDayOfWeek ^LocalDateTime obj)))
   :cljs (defn day-of-week [obj] (inc (.getIsoWeekday obj))))
```

(`iso-week-field` stays `:clj`-only, unchanged.)

**(f) arithmetic and boundaries** — `goog.date`'s `add` mutates and already clamps month-end overflow (2023-03-31 minus 1 month → 2023-02-28, as the suite pins), so clone first:

```clojure
#?(:clj (defn minus-days [dt n] (.minusDays ^LocalDateTime dt (long n)))
   :cljs (defn minus-days [dt n] (doto (.clone dt) (.add (Interval. 0 0 (- n))))))

#?(:clj (defn minus-months [dt n] (.minusMonths ^LocalDateTime dt (long n)))
   :cljs (defn minus-months [dt n] (doto (.clone dt) (.add (Interval. 0 (- n) 0)))))

#?(:clj (defn minus-years [dt n] (.minusYears ^LocalDateTime dt (long n)))
   :cljs (defn minus-years [dt n] (doto (.clone dt) (.add (Interval. (- n) 0 0)))))

#?(:clj (defn first-day-of-the-month [dt] (.with ^LocalDateTime dt (TemporalAdjusters/firstDayOfMonth)))
   :cljs (defn first-day-of-the-month [dt] (doto (.clone dt) (.setDate 1))))

#?(:clj (defn last-day-of-the-month [dt] (.with ^LocalDateTime dt (TemporalAdjusters/lastDayOfMonth)))
   :cljs (defn last-day-of-the-month [dt]
           (let [c (.clone dt)]
             (.setDate c (gdate/getNumberOfDaysInMonth (.getFullYear c) (.getMonth c)))
             c)))

#?(:clj (defn today-at-midnight [] (.atStartOfDay (LocalDate/now ZoneOffset/UTC)))
   :cljs (defn today-at-midnight []
           (let [n (UtcDateTime.)]
             (UtcDateTime. (.getFullYear n) (.getMonth n) (.getDate n) 0 0 0 0))))

#?(:clj (defn number-of-days-in-the-month [obj]
          (.lengthOfMonth (YearMonth/from ^LocalDateTime obj)))
   :cljs (defn number-of-days-in-the-month [obj]
           (gdate/getNumberOfDaysInMonth (.getFullYear obj) (.getMonth obj))))
```

**(g) coercions** — `UtcDateTime`'s constructor accepts a `Date`-like first argument and preserves the instant; `.getTime` on both `js/Date` and goog dates yields epoch millis:

```clojure
#?(:clj (defn to-date [obj]
          (Date/from (.toInstant ^LocalDateTime obj ZoneOffset/UTC)))
   :cljs (defn to-date [obj] (js/Date. (.getTime obj))))

#?(:clj (defn from-date [^Date d]
          (LocalDateTime/ofInstant (.toInstant d) ZoneOffset/UTC))
   :cljs (defn from-date [d] (UtcDateTime. d)))

#?(:clj (defn from-long [l]
          (LocalDateTime/ofInstant (Instant/ofEpochMilli (long l)) ZoneOffset/UTC))
   :cljs (defn from-long [l] (UtcDateTime. (js/Date. (long l)))))

#?(:clj (defn to-long [obj]
          (cond
            (nil? obj) nil
            (number? obj) (long obj)
            (string? obj) (.toEpochMilli (.toInstant (parse day-formatter obj) ZoneOffset/UTC))
            (instance? Date obj) (.getTime ^Date obj)
            :else (.toEpochMilli (.toInstant ^LocalDateTime obj ZoneOffset/UTC))))
   :cljs (defn to-long [obj]
           (cond
             (nil? obj) nil
             (number? obj) (long obj)
             (string? obj) (.getTime (parse day-formatter obj))
             :else (.getTime obj))))
```

**(h) predicate and comparisons** — `date-protocol?` becomes an instance check (`UtcDateTime` and `DateTime` both inherit from `goog.date.Date`; `js/Date` does not, so native-vs-protocol dispatch in `convert-and-apply`/`obj->date-str` is preserved). The `before?*`-family gains `:cljs` bodies, which lets `within?`, `earliest`, and `latest` collapse to platform-neutral definitions identical in behavior to today's (start-inclusive / end-exclusive `within?`, as the suite pins):

```clojure
(defn date-protocol? [obj]
  #?(:clj (instance? Temporal obj)
     :cljs (instance? gdate/Date obj)))
```

`convert-and-apply` keeps its body; reword its docstring so it no longer names the removed libraries:

```clojure
(defn- convert-and-apply
  "Converts obj to a platform date object when necessary before applying f"
  ...body unchanged...)
```

```clojure
#?(:clj (defn- before?* [a b] (.isBefore ^LocalDateTime a b))
   :cljs (defn- before?* [a b] (< (.getTime a) (.getTime b))))
#?(:clj (defn- after?* [a b] (.isAfter ^LocalDateTime a b))
   :cljs (defn- after?* [a b] (> (.getTime a) (.getTime b))))
#?(:clj (defn- equal?* [a b] (.isEqual ^LocalDateTime a b))
   :cljs (defn- equal?* [a b] (== (.getTime a) (.getTime b))))

(def before? (partial convert-and-apply before?*))
(def after? (partial convert-and-apply after?*))
(def equal? (partial convert-and-apply equal?*))

(defn within? [start end x]
  (let [start (convert-and-apply nil start)
        end (convert-and-apply nil end)
        x (convert-and-apply nil x)]
    (and (not (before?* x start))
         (before?* x end))))

(defn earliest
  ([dts] (reduce (fn [a b] (if (before?* b a) b a)) dts))
  ([dt1 dt2] (if (before?* dt2 dt1) dt2 dt1)))

(defn latest
  ([dts] (reduce (fn [a b] (if (after?* b a) b a)) dts))
  ([dt1 dt2] (if (after?* dt2 dt1) dt2 dt1)))
```

Everything below `to-long` in the file (`current-ms`, `obj->date-str`, `date-str->obj`, `filter-date-ranges`, `filter-months`, `is-same-day?`, `get-month`, `get-year`, `get-month-year`, `get-days-in-month`) is already platform-neutral — leave untouched.

- [ ] **Step 3: Run the characterization suite on a cljs target**

```bash
cd bundles/browser && npm run test-ci
grep -o '<testsuite name="[^"]*" tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' report.xml | grep -v 'failures="0" errors="0"'
```

Expected: empty grep, fresh mtime. Pay special attention to `unification.time-test`, `data-format.date-filter-test`, and the data-transformer/mapping namespaces — they exercise every rewritten path. If `week-and-weekday`, `date-arithmetic`, or `within-range` fail, the goog semantics differ from the pinned contract — fix the facade implementation (the tests are the spec, never edit assertions to match).

- [ ] **Step 4: Run the JVM suite to prove the `:clj` side is untouched**

```bash
cd bundles/server
clojure -Sdeps "$(cat clj.deps.edn)" -M:test
```

Expected: same counts as the branch baseline, 0 failures / 0 errors.

- [ ] **Step 5: Commit**

```bash
git add plugins/shared/de/explorama/shared/common/unification/time.cljc
git commit -m "refactor(shared): back the unification.time facade with goog.date on cljs"
```

---

### Task 2: Promote the two JVM-only characterization tests to both platforms

**Files:**
- Modify: `plugins/shared_test/de/explorama/shared/common/unification/time_test.cljc`

**Interfaces:**
- Consumes: Task 1's `parse` (lenient numeric widths, `hh:mm a` support).
- Produces: dual-platform pinning of transformer-relevant parse behavior; Task 3's sweep must keep these green everywhere.

- [ ] **Step 1: Remove the `#?(:clj ...)` wrappers from `lenient-numeric-widths` and `parse-time-carrying-schemas`**

The two deftests currently read:

```clojure
#?(:clj
   (t/deftest lenient-numeric-widths
     (t/is (= "2018-09-04"
              (sut/unparse sut/day-formatter
                           (sut/parse (sut/formatter "dd.MM.yyyy") "4.9.2018"))))))

#?(:clj
   (t/deftest parse-time-carrying-schemas
     (let [dt (sut/parse (sut/formatter "dd/MM/yyyy hh:mm a") "15/06/2023 10:20 PM")]
       (t/is (= "2023-06-15" (sut/obj->date-str :day dt)))
       (t/is (= 22 (sut/get-hour dt))))))
```

Unwrap both so they compile on all platforms (assertions unchanged):

```clojure
(t/deftest lenient-numeric-widths
  (t/is (= "2018-09-04"
           (sut/unparse sut/day-formatter
                        (sut/parse (sut/formatter "dd.MM.yyyy") "4.9.2018")))))

(t/deftest parse-time-carrying-schemas
  (let [dt (sut/parse (sut/formatter "dd/MM/yyyy hh:mm a") "15/06/2023 10:20 PM")]
    (t/is (= "2023-06-15" (sut/obj->date-str :day dt)))
    (t/is (= 22 (sut/get-hour dt)))))
```

- [ ] **Step 2: Run cljs and clj suites**

```bash
cd bundles/browser && npm run test-ci
grep -o '<testsuite name="[^"]*" tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' report.xml | grep -v 'failures="0" errors="0"'
cd ../server && clojure -Sdeps "$(cat clj.deps.edn)" -M:test
```

Expected: empty grep / 0 failures. `goog.i18n.DateTimeParse` scans numeric fields delimiter-driven (so `4.9.2018` under `dd.MM.yyyy` parses) and supports the `a` am/pm marker; both tests should pass as-is. **Fallback if a promoted test is red on cljs only:** first check whether the field-transplant in `parse` (Task 1 step 2c) lost the parsed value; if the goog parser genuinely cannot handle the input, restore that one test's `#?(:clj ...)` wrapper and record the cljs limitation in the PR description — do not weaken the assertion, and do not hand-roll a parser for it.

- [ ] **Step 3: Commit**

```bash
git add plugins/shared_test/de/explorama/shared/common/unification/time_test.cljc
git commit -m "test(shared): run the lenient-parse characterizations on cljs too"
```

---

### Task 3: Drop the dependency and sweep all suites

**Files:**
- Modify: `bundles/browser/deps.edn` (line 25 area), `bundles/server/cljs.deps.edn` (line 24 area), `bundles/electron/backend/deps.edn` (line 26 area), `bundles/electron/frontend/deps.edn` (line 23 area)

**Interfaces:**
- Consumes: Task 1's facade (no `cljs-time.*` requires remain anywhere).
- Produces: cljs-time absent from every classpath; the end-state grep assertion below.

- [ ] **Step 1: Delete the dependency line from all four deps files**

Remove this exact line from each of the four files (clj-time is already gone since stage 1):

```clojure
        com.andrewmcveigh/cljs-time {:mvn/version "0.5.2"}
```

- [ ] **Step 2: Assert the end state**

```bash
grep -rn "cljs-time" \
  /home/soeren/repos/private/Explorama/plugins \
  /home/soeren/repos/private/Explorama/tools \
  /home/soeren/repos/private/Explorama/e2e/src \
  --include='*.clj' --include='*.cljs' --include='*.cljc' --include='*.edn'
grep -rn "cljs-time" /home/soeren/repos/private/Explorama/bundles \
  --include='*.edn' --include='*.clj' --include='*.cljs' --include='*.cljc' \
  | grep -v 'resources/public/js\|/dist/\|/target/\|node_modules'
```

Expected: both greps empty. (Compiled copies under `resources/public/js/out` regenerate on the next dev build and are not tracked deliverables.)

- [ ] **Step 3: Run every bundle's suites**

```bash
cd bundles/browser && npm run test-ci
grep -o '<testsuite name="[^"]*" tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' report.xml | grep -v 'failures="0" errors="0"'

cd ../electron && make test

cd ../server
clojure -Sdeps "$(cat clj.deps.edn)" -M:test
clojure -Sdeps "$(cat cljs.deps.edn)" -M:test-ci
grep -o '<testsuite name="[^"]*" tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' report.xml | grep -v 'failures="0" errors="0"'
```

Expected: every per-suite grep empty, electron reporting its two suite totals green. Check each `report.xml` mtime against the clock — a crashed run leaves the old file. A stale figwheel JVM or headless Chromium on port 8020/9222 causes `Address already in use`; kill it and re-run.

- [ ] **Step 4: Lint against baseline**

```bash
rm -rf ~/.clj-kondo/.cache .clj-kondo/.cache
clj-kondo --lint plugins/
```

Expected: 0 errors; warnings at or below the branch baseline (main's baseline is 1087; recount on this branch before judging). Any *new* finding in `time.cljc` or `time_test.cljc` must be fixed, not suppressed.

- [ ] **Step 5: Commit**

```bash
git add bundles/browser/deps.edn bundles/server/cljs.deps.edn \
        bundles/electron/backend/deps.edn bundles/electron/frontend/deps.edn
git commit -m "build: drop the archived cljs-time dependency"
```

---

### Task 4: Production build + e2e smoke (verification only, no commit)

The engine swap must survive advanced compilation (goog.i18n symbol pruning, renaming) and the real import flow. The e2e suite is the only harness that catches production-only breakage.

**Files:** none modified.

**Interfaces:**
- Consumes: the completed branch state after Task 3.
- Produces: the go/no-go evidence for opening the PR.

- [ ] **Step 1: Build the browser bundle**

```bash
cd bundles/browser && ./build.sh
```

Expected: build completes without errors.

- [ ] **Step 2: Run the e2e suite**

```bash
cd ../../e2e && npm install && npm test
```

Expected: all Playwright specs pass. Before trusting a result, make sure no stale `http-server` on port 8099 is serving an old dist (kill it if present — known trap). Failures around data import, project display timestamps, or table date filtering point at the facade rewrite; debug with superpowers:systematic-debugging rather than patching symptoms.

- [ ] **Step 3: Report**

State the actual numbers from every suite run in Tasks 3–4 (per-suite, from `report.xml`, not the runner's exit code), then proceed to superpowers:finishing-a-development-branch for the merge/PR decision. The PR stacks on `feature/unification-time-consolidation` (itself stacked on PR #104) — note that in the PR description.

---

## Known limitations (pre-existing, out of scope)

- **Two-digit-year pivot drift:** on `:clj`, TR35 `yy` resolves to 2000–2099; cljs-time used a sliding window pivoted near `now − 30`, and `goog.i18n.DateTimeParse` uses its own sliding ambiguous-year window. The clj/cljs divergence for `yy` patterns predates this change (it dates to stage 1) and no repo behavior pins it; this plan neither fixes nor worsens it.
- **Invalid calendar dates resolve differently per platform:** `"2023-02-31"` under `yyyy-MM-dd` clamps to Feb 28 on `:clj` (java.time SMART resolution) but rolls over to Mar 3 on `:cljs` (goog's non-validating parse), matching cljs-time's old behavior. No repo input path feeds invalid dates; noted so a future parity audit doesn't rediscover it.
- **js/Date display helpers** (`woco/util/date.cljs`, `reporting/util/date.cljs`) and `logging.cljc`'s goog.date usage stay as they are — declared out of scope by the stage-2 spec.
- `jwt.clj` remains on direct `java.time.Instant` arithmetic (JVM-only, out of scope since stage 1).
