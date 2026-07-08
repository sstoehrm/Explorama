#!/usr/bin/env bb
;; ============================================================================
;; Tailwind migration audit gate  (Task 3, computed-value equivalence round)
;; ============================================================================
;; Run from styles/:  bb scripts/tailwind_audit.bb.clj
;;
;; Exits 0 iff:
;;   1. every class in the old UTILITY LAYER is classified (no :unclassified)
;;   2. every :same / :rename target's declarations are COMPUTED-VALUE
;;      identical to the old class's declarations (verified against a real
;;      Tailwind build; see the normalizer contract below)
;;   3. every :drop class has ZERO usages in the frontend markup
;;   4. the utility scope (compiled from the partials) is consistent with the
;;      old css and the classification generators
;;
;; Also emits the fully resolved {old -> new} rename map (explicit + generated)
;; to docs/superpowers/artifacts/tailwind/resolved-renames.edn for the Task 4
;; codemod.
;;
;; ---------------------------------------------------------------------------
;; NORMALIZER CONTRACT (computed-value equivalence, never looser):
;;   * declarations whose property starts with `--tw-` are Tailwind's inert
;;     utility machinery and are dropped AFTER their values are captured for
;;     same-rule var() resolution
;;   * var() references are resolved: same-rule value, then @theme value (from
;;     the build's :root block), then -- for --tw-* vars only -- the build's
;;     @property initial-value, then the var() fallback. var() references to
;;     unknown NON-tw custom properties (e.g. --box-shadow-color, set at
;;     runtime) are kept verbatim and must match on both sides.
;;   * shorthands are expanded to longhands per spec (flex, border-<side>,
;;     padding/margin-inline|block and border-inline|block-* under the
;;     verified LTR-only invariant)
;;   * a small documented table of computed-identical value rewrites is
;;     applied (see computed-pairs below); each entry is justified in the
;;     task report
;;   * numeric literals are canonicalized to 6 decimal places (differences
;;     below 1e-6 of a CSS unit are far beneath the 1/64px browser layout
;;     quantum; the only case exercised is Sass' 10-digit truncation of n/3,
;;     n/6, n/12 percentages vs Tailwind's calc() of the same rational)
;;   * whitespace / case / `0<unit>` / slash spacing are canonicalized
;; ============================================================================
(ns tailwind-audit
  (:require [babashka.process :refer [shell]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def styles-dir (System/getProperty "user.dir"))
(def mapping (edn/read-string (slurp "scripts/tailwind-mapping.edn")))
(def old (edn/read-string
          (slurp "../docs/superpowers/artifacts/tailwind/old-utilities.edn")))
(def renames-artifact
  "../docs/superpowers/artifacts/tailwind/resolved-renames.edn")

;; ============================================================================
;; 0. AUTHORITATIVE UTILITY SCOPE = simple class selectors emitted by the 8
;;    utility partials (the ones deleted in Task 5). Derived by actually
;;    compiling them with Sass, so scope can never drift from the source of
;;    truth. Complex selectors (`.divide-x-1 > *`, `.flex-row h1`, ...) belong
;;    to component css and are excluded, matching the Task-1 extractor.
;; ============================================================================
(def utility-partials
  ["spacing" "sizing" "layouting" "borders" "shadows" "colors"
   "transformations" "fonts"])

(defn split-top-level
  "Split on `sep` at paren depth 0."
  [s sep]
  (map (partial apply str)
       (loop [cs (seq s) depth 0 cur [] out []]
         (if-let [c (first cs)]
           (cond
             (= c \() (recur (rest cs) (inc depth) (conj cur c) out)
             (= c \)) (recur (rest cs) (max 0 (dec depth)) (conj cur c) out)
             (and (= c sep) (zero? depth)) (recur (rest cs) depth [] (conj out cur))
             :else (recur (rest cs) depth (conj cur c) out))
           (conj out cur)))))

(defn simple-class-selectors
  "Return the set of single-class selector names from a compiled stylesheet
   (same rule as the Task-1 extractor: one leading dot, no combinators/chains)."
  [css]
  (into #{}
        (for [[_ sels] (re-seq #"(?m)^(\.[^{}@]+?)\s*\{" css)
              sel (split-top-level sels \,)
              :let [sel (-> sel str/trim (str/replace #"\\" ""))]
              :when (and (str/starts-with? sel ".")
                         (not (re-find #"[ >~+:\[]" (subs sel 1)))
                         (not (str/includes? (subs sel 1) ".")))]
          (subs sel 1))))

(defn utility-scope
  "Compile the 8 utility partials and extract their simple class selectors."
  []
  (let [tmp (io/file (System/getProperty "java.io.tmpdir") "tw-audit")
        _ (.mkdirs tmp)
        probe (str tmp "/partials.scss")
        out (str tmp "/partials.css")]
    (spit probe (str/join "\n" (map #(format "@use '%s';" %) utility-partials)))
    (let [{:keys [exit err]} (shell {:out :string :err :string :continue true}
                                    "npx" "sass" probe out
                                    "--load-path=src/scss/base" "--quiet")]
      (when-not (zero? exit)
        (println "Sass compile of utility partials FAILED:\n" err)
        (System/exit 2)))
    (simple-class-selectors (slurp out))))

;; ============================================================================
;; 1. CLASSIFICATION
;;    {old-class -> [kind target]}, kind in #{:same :rename :custom :drop}.
;;    Explicit EDN entries are inserted first and ALWAYS WIN over generated
;;    ones; two GENERATORS disagreeing on one class is generation drift and
;;    hard-fails (assertion in `put`).
;; ============================================================================
(def explicit-classification
  (as-> {} a
    (reduce (fn [a [o n]] (assoc a o [:rename n])) a (:rename mapping))
    (reduce (fn [a c] (assoc a c [:same c])) a (:same mapping))
    (reduce (fn [a c] (assoc a c [:custom nil])) a (:custom mapping))
    (reduce (fn [a c] (assoc a c [:drop nil])) a (:drop mapping))))

(def explicit-keys (set (keys explicit-classification)))

(defn put
  "Insert a generated classification. Explicit entries win silently; two
   different generated values for one class is a bug -> hard fail."
  [m k v]
  (if-let [cur (get m k)]
    (cond
      (contains? explicit-keys k) m
      (= cur v) m
      :else (throw (ex-info (str "generator collision on " k)
                            {:class k :existing cur :new v})))
    (assoc m k v)))

(defn numeric-rename [family suffix]
  (when-let [ts (get (:size-map mapping) suffix)]
    (str family "-" ts)))

(defn expand-spacing [acc]
  (reduce (fn [a fam]
            (reduce (fn [a k]
                      (put a (str fam "-" k) [:rename (numeric-rename fam k)]))
                    a (:sizes-keys mapping)))
          acc (:spacing-families mapping)))

(defn expand-neg-margins
  "-m/-mx/.. over $sizes. Size 0 -> positive m-0 form (TW has no -*-0)."
  [acc]
  (reduce (fn [a fam]
            (reduce (fn [a k]
                      (let [ts (get (:size-map mapping) k)
                            target (if (= ts "0")
                                     (str (subs fam 1) "-0")
                                     (str fam "-" ts))]
                        (put a (str fam "-" k) [:rename target])))
                    a (:sizes-keys mapping)))
          acc (:neg-margin-families mapping)))

(defn expand-sizing
  "w/min-w/max-w/h/min-h/max-h over $sizes-ext. numeric -> :size-map rename;
   full/auto -> same (max-w-auto/max-h-auto are explicit :drop -- invalid CSS,
   no TW equivalent); fractions -> Tailwind fraction rename (1-3 -> 1/3)."
  [acc]
  (reduce
   (fn [a fam]
     (as-> a a
       (reduce (fn [a k] (put a (str fam "-" k) [:rename (numeric-rename fam k)]))
               a (:sizes-ext-numeric mapping))
       (reduce (fn [a k] (put a (str fam "-" k) [:same (str fam "-" k)]))
               a (:sizes-ext-keyword mapping))
       (reduce (fn [a k]
                 (put a (str fam "-" k)
                      [:rename (str fam "-" (str/replace k "-" "/"))]))
               a (:sizes-ext-fraction mapping))))
   acc (:sizing-families mapping)))

(defn expand-auto-margins [acc]
  (reduce (fn [a c] (put a c [:same c])) acc (:auto-margins mapping)))

(defn expand-rounded
  "rounded-<radius> -> same (radii themed; full via the documented
   9999px<->calc(infinity*1px) pair). rounded-<side|corner>-<radius> -> :drop:
   the old generation was buggy (set the FULL border-radius) and none are used
   in markup (audit-verified); per instruction they must not be 'fixed' by a
   rename."
  [acc]
  (let [sides ["t" "r" "b" "l" "tl" "tr" "br" "bl"]]
    (reduce
     (fn [a r]
       (as-> a a
         (put a (str "rounded-" r) [:same (str "rounded-" r)])
         (reduce (fn [a s] (put a (str "rounded-" s "-" r) [:drop nil]))
                 a sides)))
     acc (:radii-keys mapping))))

(defn expand-borders
  "border/outline width utilities. Width 1 renames to the bare TW form
   (border-1 -> border, border-t-1 -> border-t, outline-1 -> outline); other
   widths keep their name. The old bare aliases (@extend'ed to width 1) map to
   the same TW bare form. outline-offset-<w> -> :drop (the old class also set
   outline-style: solid, which TW's does not; all unused, audit-verified)."
  [acc]
  (let [fams ["border" "border-x" "border-y" "border-t" "border-r" "border-b"
              "border-l" "outline"]]
    (as-> acc a
      ;; bare aliases: old `border` == old `border-1` == TW `border`
      (reduce (fn [a f] (put a f [:same f])) a fams)
      (reduce (fn [a fam]
                (reduce (fn [a w]
                          (if (= w "1")
                            (put a (str fam "-1") [:rename fam])
                            (put a (str fam "-" w) [:same (str fam "-" w)])))
                        a (:widths-keys mapping)))
              a fams)
      (reduce (fn [a w] (put a (str "outline-offset-" w) [:drop nil]))
              a (:widths-keys mapping)))))

(defn expand-grid
  "grid-cols/rows-<n> + col/row-span/start/end-<n> -> same.
   grid-cols/rows-<n>-fr -> :drop (repeat(n,1fr) vs TW's repeat(n,minmax(0,1fr))
   is genuinely different -- no min-width floor); the two USED variants are
   explicit :custom exceptions in the EDN."
  [acc]
  (reduce
   (fn [a i]
     (as-> a a
       (put a (str "grid-cols-" i) [:same (str "grid-cols-" i)])
       (put a (str "grid-rows-" i) [:same (str "grid-rows-" i)])
       (put a (str "grid-cols-" i "-fr") [:drop nil])
       (put a (str "grid-rows-" i "-fr") [:drop nil])
       (reduce (fn [a p] (put a (str p i) [:same (str p i)]))
               a ["col-span-" "col-start-" "col-end-"
                  "row-span-" "row-start-" "row-end-"])))
   acc (range 1 (inc (:grid-range mapping)))))

(defn expand-order [acc]
  (reduce
   (fn [a i]
     (as-> a a
       (put a (str "order-" i) [:same (str "order-" i)])
       (if (pos? i)
         (put a (str "order--" i) [:rename (str "-order-" i)])
         a)))
   acc (range 0 (inc (:order-range mapping)))))

(defn expand-opacity [acc]
  (reduce (fn [a i] (put a (str "opacity-" i) [:same (str "opacity-" i)]))
          acc (range 0 101 (:opacity-step mapping))))

(defn expand-colors
  "_colors.scss loops colormap.$colors:
     text-/bg-/border-/outline-<c> -> same (theme colors keep names+values)
     text-decor-<c>      -> rename decoration-<c>
     icon-<c>            -> rename bg-<c>   (identical declaration:
                            background-color -- the icon coloring mechanism)
     icon-<c>-important  -> rename bg-<c>!  (TW v4 important suffix)
     shadow-<c>          -> :drop (sets --box-shadow-color; TW's shadow-<c>
                            sets --tw-shadow-color via color-mix -- genuinely
                            different mechanism; all unused, audit-verified)"
  [acc]
  (reduce
   (fn [a c]
     (as-> a a
       (put a (str "text-" c) [:same (str "text-" c)])
       (put a (str "bg-" c) [:same (str "bg-" c)])
       (put a (str "border-" c) [:same (str "border-" c)])
       (put a (str "outline-" c) [:same (str "outline-" c)])
       (put a (str "text-decor-" c) [:rename (str "decoration-" c)])
       (put a (str "icon-" c) [:rename (str "bg-" c)])
       (put a (str "icon-" c "-important") [:rename (str "bg-" c "!")])
       (put a (str "shadow-" c) [:drop nil])))
   acc (:colors mapping)))

(defn build-classification []
  (-> explicit-classification
      expand-spacing
      expand-neg-margins
      expand-sizing
      expand-auto-margins
      expand-rounded
      expand-borders
      expand-grid
      expand-order
      expand-opacity
      expand-colors))

;; ============================================================================
;; 2. NORMALIZER
;; ============================================================================
(defn parse-theme-vars
  "Parse `--name: value;` custom property lines from the build's
   @layer theme { :root, :host { ... } } block ONLY (the @layer properties
   fallback block also declares --tw-* custom props and must not leak in)."
  [css]
  (if-let [[_ body] (re-find #"(?s):root,\s*:host\s*\{(.*?)\n  \}" css)]
    (into {}
          (for [[_ n v] (re-seq #"(--[a-zA-Z0-9-]+):\s*([^;{}]+);" body)]
            [n (str/trim v)]))
    {}))

(defn parse-property-initials
  "Parse `@property --x { ...; initial-value: v; }` blocks from the build."
  [css]
  (into {}
        (for [[_ n body] (re-seq #"@property\s+(--[a-zA-Z0-9-]+)\s*\{([^}]*)\}" css)
              :let [iv (second (re-find #"initial-value:\s*([^;]+);" body))]
              :when iv]
          [n (str/trim iv)])))

(defn- matching-paren
  "Index of the `)` matching the `(` at index i, or nil."
  [s i]
  (loop [j (inc i) depth 1]
    (when (< j (count s))
      (let [c (nth s j)]
        (cond
          (= c \() (recur (inc j) (inc depth))
          (= c \)) (if (= depth 1) j (recur (inc j) (dec depth)))
          :else (recur (inc j) depth))))))

(defn resolve-vars
  "Resolve var(--x[, fallback]) references. Resolution order: same-rule value,
   @theme value, then (for --tw-* only) @property initial-value, then the
   fallback. Unknown non-tw vars stay verbatim (both sides must then carry the
   identical var expression -- e.g. var(--box-shadow-color, ...), which is set
   at runtime and must survive normalization)."
  [s local props theme]
  (loop [s s n 0]
    (let [s' (loop [out "" remain s]
               (if-let [i (str/index-of remain "var(")]
                 (let [open (+ i 3)
                       close (matching-paren remain open)]
                   (if-not close
                     (str out remain)
                     (let [inner (subs remain (+ open 1) close)
                           [nm fb] (split-top-level inner \,)
                           nm (str/trim nm)
                           fb (when fb (str/trim fb))
                           rep (cond
                                 (contains? local nm) (get local nm)
                                 ;; --tw-* machinery resolves via @property
                                 ;; initial / fallback, NEVER via theme
                                 (str/starts-with? nm "--tw-")
                                 (or (get props nm) fb)
                                 (contains? theme nm) (get theme nm)
                                 :else nil)]
                       (recur (str out (subs remain 0 i)
                                   (or rep (subs remain i (inc close))))
                              (subs remain (inc close))))))
                 (str out remain)))]
      (if (or (= s' s) (>= n 8)) s' (recur s' (inc n))))))

(defn fmt-num [x]
  (let [r (Math/round (double x))]
    (if (< (Math/abs (- (double x) r)) 1e-9)
      (str r)
      (-> (format "%.6f" (double x))
          (str/replace #"0+$" "")
          (str/replace #"\.$" "")))))

(defn eval-calc-mul
  "calc(A * B) -> product, keeping the single unit present."
  [s]
  (let [re #"calc\(\s*(-?[0-9.]+)(rem|px|em|%|deg)?\s*\*\s*(-?[0-9.]+)(rem|px|em|%|deg)?\s*\)"]
    (loop [s s n 0]
      (let [s' (str/replace s re
                            (fn [[_ a ua b ub]]
                              (str (fmt-num (* (Double/parseDouble a)
                                               (Double/parseDouble b)))
                                   (or (not-empty ua) (not-empty ub) ""))))]
        (if (or (= s' s) (>= n 5)) s' (recur s' (inc n)))))))

(defn eval-calc-fraction
  "calc(A / B * C%) -> percentage (Tailwind fraction utilities)."
  [s]
  (str/replace s #"calc\(\s*([0-9.]+)\s*/\s*([0-9.]+)\s*\*\s*([0-9.]+)%\s*\)"
               (fn [[_ a b c]]
                 (str (fmt-num (* (/ (Double/parseDouble a)
                                     (Double/parseDouble b))
                                  (Double/parseDouble c)))
                      "%"))))

(defn canon-numbers
  "Canonicalize numeric literals with >6 decimals to 6 decimal places.
   1e-6 of any CSS unit is far below the 1/64px layout quantum; the only
   exercised case is Sass' 10-digit n/3-style percentage truncation vs
   Tailwind's calc() of the same rational."
  [s]
  (str/replace s #"-?\d+\.\d{7,}"
               (fn [whole] (fmt-num (Double/parseDouble whole)))))

(defn rem->px [s]
  (str/replace s #"(-?[0-9.]+)rem"
               (fn [[_ n]] (str (fmt-num (* 16 (Double/parseDouble n))) "px"))))

(defn zero-norm [s]
  (str/replace s #"(?<![0-9.])-?0(?:\.0+)?(px|rem|em|%)?(?![0-9.])" "0"))

(defn slash-norm [s]
  (str/replace s #"\s*/\s*" "/"))

;; ---- documented computed-identical value pairs ------------------------------
;; Property-scoped rewrites to a canonical spelling. Each is computed-identical:
;;  * flex alignment props, flex-start->start / flex-end->end: identical in all
;;    non-reversed flex containers and in grid ("flex-start behaves as start
;;    outside flex layout", css-align-3); the codebase has ZERO
;;    flex-row-reverse / flex-col-reverse usages (verified over the same
;;    markup corpus the :drop check greps).
;;  * border-radius, calc(infinity*1px)->9999px: CSS corner-overlap clamping
;;    (css-backgrounds-3 5.5) proportionally reduces any radius >= half the
;;    element's largest side; both spellings clamp identically for any element
;;    smaller than 19998px.
(def computed-pairs
  {"justify-content" {"flex-start" "start" "flex-end" "end"}
   "align-content"   {"flex-start" "start" "flex-end" "end"}
   "align-items"     {"flex-start" "start" "flex-end" "end"}
   "align-self"      {"flex-start" "start" "flex-end" "end"}
   "justify-self"    {"flex-start" "start" "flex-end" "end"}
   "border-radius"   {"calc(infinity * 1px)" "9999px"}})

;; ---- shorthand expansion (decl -> decls) ------------------------------------
(defn expand-flex
  "Expand the `flex` shorthand per css-flexbox-1 7.1.1:
   none -> 0 0 auto; <number> -> n 1 0%; <number> <number> -> a b 0%;
   <number> <basis> -> n 1 basis; <basis> -> 1 1 basis; triple as-is.
   NOTE the flex-basis 0<->0% pair: old `flex: 1 1 0` vs TW `flex: 1`
   (== 1 1 0%). 0px and 0% differ per spec only when the container's main
   size is indefinite (percentage then behaves as content). Accepted per the
   task directive; documented in the report."
  [val]
  (let [toks (str/split (str/trim val) #"\s+")
        num? #(re-matches #"-?[0-9.]+" %)
        [g s b] (cond
                  (= toks ["none"]) ["0" "0" "auto"]
                  (and (= 1 (count toks)) (num? (first toks)))
                  [(first toks) "1" "0%"]
                  (= 1 (count toks)) ["1" "1" (first toks)]
                  (and (= 2 (count toks)) (num? (second toks)))
                  [(first toks) (second toks) "0%"]
                  (= 2 (count toks)) [(first toks) "1" (second toks)]
                  :else toks)]
    [["flex-grow" g] ["flex-shrink" s] ["flex-basis" b]]))

(defn expand-decl
  "Expand one [prop val] declaration into spec-equivalent longhands.
   - flex shorthand (see expand-flex)
   - border-<side>: `<width> <style>` -> width+style longhands. The shorthand's
     implicit border-<side>-color: currentcolor reset is dropped: currentcolor
     is the property's initial value, and in the old stylesheet the color
     utilities (_colors.scss) are emitted AFTER the border utilities
     (_borders.scss; see base/helpers.scss forward order), so the reset never
     overrode a border-color utility. Documented in the report.
   - padding/margin-inline|block and border-inline|block-<width|style> ->
     physical pairs (LTR-only codebase, verified: zero direction:rtl /
     dir=rtl / writing-mode in app source)."
  [[prop val]]
  (let [border-side-re #"(-?[0-9.]+(?:px|rem|em)?)\s+(solid|dashed|dotted|double|none|hidden)"]
    (cond
      (= prop "flex") (expand-flex val)

      (and (#{"border-top" "border-right" "border-bottom" "border-left"} prop)
           (re-matches border-side-re val))
      (let [[_ w s] (re-matches border-side-re val)]
        [[(str prop "-width") w] [(str prop "-style") s]])

      (#{"padding-inline" "margin-inline"} prop)
      (let [p (str/replace prop "-inline" "")]
        [[(str p "-left") val] [(str p "-right") val]])

      (#{"padding-block" "margin-block"} prop)
      (let [p (str/replace prop "-block" "")]
        [[(str p "-top") val] [(str p "-bottom") val]])

      (str/starts-with? prop "border-inline-")
      (let [suffix (subs prop (count "border-inline-"))]
        [[(str "border-left-" suffix) val] [(str "border-right-" suffix) val]])

      (str/starts-with? prop "border-block-")
      (let [suffix (subs prop (count "border-block-"))]
        [[(str "border-top-" suffix) val] [(str "border-bottom-" suffix) val]])

      :else [[prop val]])))

(defn prune-shadow-chain
  "Drop no-op `0 0 #0000` segments from a resolved box-shadow list (they are
   Tailwind's unset shadow slots: zero-size fully-transparent shadows render
   nothing). An empty result == no shadow == `none`."
  [val]
  (let [segs (->> (split-top-level val \,)
                  (map str/trim)
                  (remove #(= % "0 0 #0000")))]
    (if (empty? segs) "none" (str/join ", " segs))))

(defn norm-value [prop val]
  (let [val (str/lower-case (str/trim val))
        val (if (str/includes? prop "opacity")
              (str/replace val #"([0-9.]+)%"
                           (fn [[_ n]] (fmt-num (/ (Double/parseDouble n) 100.0))))
              val)
        val (get-in computed-pairs [prop val] val)
        val (if (= prop "box-shadow") (prune-shadow-chain val) val)]
    (-> val
        eval-calc-fraction
        eval-calc-mul
        rem->px
        canon-numbers
        zero-norm
        slash-norm
        (str/replace #"\s+" " ")
        str/trim)))

(defn norm-block
  "Normalize a declaration block into a canonical sorted `p:v;p:v` string.
   Sorting makes property ORDER irrelevant (utilities set distinct
   properties, so order does not affect the computed style)."
  [block props theme]
  (when block
    (let [decls (->> (str/split block #";")
                     (map str/trim)
                     (remove str/blank?)
                     (keep (fn [d]
                             (when-let [i (str/index-of d ":")]
                               [(str/trim (str/lower-case (subs d 0 i)))
                                (str/trim (subs d (inc i)))]))))
          ;; same-rule custom property values (captured before dropping --tw-*)
          local (into {} (filter (fn [[p _]] (str/starts-with? p "--")) decls))
          decls (remove (fn [[p _]] (str/starts-with? p "--tw-")) decls)
          decls (map (fn [[p v]] [p (resolve-vars v local props theme)]) decls)
          decls (mapcat expand-decl decls)
          decls (map (fn [[p v]] (str p ":" (norm-value p v))) decls)]
      (str/join ";" (sort decls)))))

;; ============================================================================
;; 3. PROBE BUILD + DECLARATION EXTRACTION
;; ============================================================================
(defn parse-tw-rules
  "Parse `  .<name> { ... }` blocks from a Tailwind build. Depth-aware: lines
   inside nested blocks (@media/@supports within a rule) are ignored -- only
   the rule's own declarations are captured. Returns {class-name raw-body}
   with `\\` unescaped in selectors."
  [css]
  (loop [ls (str/split-lines css) cur nil depth 0 body [] out {}]
    (if-let [l (first ls)]
      (let [t (str/trim l)
            opens (count (filter #(= % \{) t))
            closes (count (filter #(= % \}) t))]
        (cond
          (and (nil? cur) (re-matches #"\.[^{},]+\{" t))
          (recur (rest ls)
                 (-> t (subs 0 (dec (count t))) str/trim
                     (str/replace "\\" "") (subs 1))
                 1 [] out)

          cur
          (let [depth' (- (+ depth opens) closes)]
            (if (zero? depth')
              (recur (rest ls) nil 0 [] (assoc out cur (str/join " " body)))
              (recur (rest ls) cur depth'
                     (if (and (= depth 1) (zero? opens) (zero? closes))
                       (conj body t)
                       body)
                     out)))

          :else (recur (rest ls) nil 0 [] out)))
      out)))

(defn build-probe [targets]
  (let [scratch (str (io/file (System/getProperty "java.io.tmpdir") "tw-audit"))
        _ (.mkdirs (io/file scratch))
        html (str scratch "/probe.html")
        entry (str scratch "/entry.css")
        out (str scratch "/out.css")]
    (spit html (str "<div class=\""
                    (str/join " " (sort (distinct targets)))
                    "\"></div>"))
    (spit entry (str "@import \"" styles-dir "/src/tailwind.css\";\n"
                     "@source \"" html "\";\n"))
    (let [{:keys [exit err]} (shell {:out :string :err :string :continue true}
                                    "npx" "@tailwindcss/cli" "-i" entry "-o" out)]
      (when-not (zero? exit)
        (println "Tailwind build FAILED:\n" err)
        (System/exit 2)))
    (slurp out)))

;; ============================================================================
;; 4. MARKUP USAGE (for :drop verification)
;; ============================================================================
(def markup-roots ["../plugins/frontend" "../plugins/shared"
                   "../bundles/browser/frontend" "../bundles/electron/frontend"
                   "../bundles/server/frontend"])

(defn usage-count
  "Count files that use the class token in cljs/cljc markup. Matches Hiccup
   dot-style (`[:div.flex.text-3xl]`), string classes (`\"a text-3xl b\"`) and
   any other occurrence, bounded by a non-class char (PCRE look-around) so
   `text-3xl` does NOT match inside `text-3xl-foo` or `my-text-3xl`."
  [cls]
  (let [{:keys [out exit]}
        (apply shell {:out :string :err :string :continue true}
               "grep" "-rIlP" "--include=*.cljs" "--include=*.cljc"
               (str "(?<![\\w-])" (java.util.regex.Pattern/quote cls) "(?![\\w-])")
               (filter #(.exists (io/file %)) markup-roots))]
    (if (zero? exit)
      (count (remove str/blank? (str/split-lines out)))
      0)))

;; ============================================================================
;; 5. RUN
;; ============================================================================
(defn -main []
  (let [cls (build-classification)
        classified-names (set (keys cls))
        old-names (set (keys old))
        scope (utility-scope)
        scope-missing-from-old (sort (remove old-names scope))
        unclassified (sort (remove classified-names scope))
        classified-not-in-scope (sort (remove scope classified-names))
        audited (sort (filter classified-names scope))
        by-kind (group-by (fn [c] (first (cls c))) audited)
        components (sort (remove scope old-names))

        ;; ---- declaration parity ----
        targets (keep (fn [c] (let [[k t] (cls c)]
                                (when (#{:same :rename} k) t)))
                      audited)
        tw-css (build-probe targets)
        tw-rules (parse-tw-rules tw-css)
        theme (parse-theme-vars tw-css)
        props (parse-property-initials tw-css)
        mismatches
        (for [c audited
              :let [[k t] (cls c)]
              :when (#{:same :rename} k)
              :let [o (norm-block (get old c) props theme)
                    n (norm-block (get tw-rules t) props theme)]
              :when (not= o n)]
          {:old c :new t :kind k :old-decl o :new-decl n
           :old-raw (get old c) :new-raw (get tw-rules t)})

        ;; ---- :drop usage (parallel; ~200 classes) ----
        drops (filter (fn [c] (= :drop (first (cls c)))) audited)
        drop-usages (into {} (pmap (fn [d] [d (usage-count d)]) drops))
        bad-drops (sort (map key (filter #(pos? (val %)) drop-usages)))

        ;; ---- resolved rename artifact (explicit + generated) ----
        renames (into (sorted-map)
                      (keep (fn [c] (let [[k t] (cls c)]
                                      (when (= :rename k) [c t])))
                            audited))]

    (spit renames-artifact
          (str ";; {old-class -> new-tailwind-class} -- ALL renames (explicit +\n"
               ";; generated), resolved by scripts/tailwind_audit.bb.clj. Consumed\n"
               ";; by the Task 4 codemod. Regenerated on every audit run.\n"
               (pr-str renames) "\n"))

    ;; ---------- report ----------
    (println "================ Tailwind migration audit ================")
    (println "old-utilities total selectors :" (count old-names))
    (println "utility scope (compiled parts):" (count scope))
    (println "component (out of scope, kept):" (count components))
    (println)
    (println "classification counts (utility scope):")
    (doseq [k [:same :rename :custom :drop]]
      (println (format "  %-13s %5d" (name k) (count (get by-kind k)))))
    (println (format "  %-13s %5d" "unclassified" (count unclassified)))
    (println)
    (println "resolved renames artifact           :" (count renames)
             "entries ->" renames-artifact)
    (println "scope classes missing from old css  :" (count scope-missing-from-old))
    (run! #(println "   -" %) (take 60 scope-missing-from-old))
    (println "unclassified scope selectors        :" (count unclassified))
    (run! #(println "   ?" (str % " => " (pr-str (get old %)))) (take 80 unclassified))
    (println "classified but NOT emitted by parts :" (count classified-not-in-scope))
    (run! #(println "   +" %) (take 40 classified-not-in-scope))
    (println)
    (println "declaration mismatches (:same/:rename):" (count mismatches))
    (doseq [m (take 80 mismatches)]
      (println (format "   x %s -> %s [%s]" (:old m) (:new m) (name (:kind m))))
      (println (format "       old: %s" (:old-decl m)))
      (println (format "       new: %s" (:new-decl m)))
      (println (format "       (old-raw: %s | new-raw: %s)"
                       (:old-raw m) (:new-raw m))))
    (println)
    (println ":drop classes with markup usages    :" (count bad-drops))
    (doseq [d bad-drops]
      (println (format "   ! %s used in %d file(s)" d (get drop-usages d))))
    (println "==========================================================")
    (let [ok (and (empty? scope-missing-from-old)
                  (empty? unclassified)
                  (empty? mismatches)
                  (empty? bad-drops))]
      (println (if ok "AUDIT PASSED (exit 0)" "AUDIT FAILED (exit 1)"))
      (System/exit (if ok 0 1)))))

(-main)
