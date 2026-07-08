#!/usr/bin/env bb
;; ============================================================================
;; Tailwind migration audit gate  (Task 3)
;; ============================================================================
;; Run from styles/:  bb scripts/tailwind_audit.bb.clj
;;
;; Exits 0 iff:
;;   1. every class in the old UTILITY LAYER is classified (no :unclassified)
;;   2. every :same / :rename target's declarations are value-identical to the
;;      old class's declarations (declaration-parity, verified against a real
;;      Tailwind build)
;;   3. every :drop class has ZERO usages in the frontend markup
;;
;; The "utility layer" scope is GENERATED here from the SCSS value-key lists in
;; tailwind-mapping.edn (the exact class names the deleted-in-Task-5 partials
;; emit). Anything in old-utilities.edn outside that generated set is :component
;; (component/theme css) and is reported but not audited.
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

(defn split-selectors
  "Split a selector list on top-level commas (paren depth 0)."
  [s]
  (map (partial apply str)
       (loop [cs (seq s) depth 0 cur [] out []]
         (if-let [c (first cs)]
           (cond
             (= c \() (recur (rest cs) (inc depth) (conj cur c) out)
             (= c \)) (recur (rest cs) (max 0 (dec depth)) (conj cur c) out)
             (and (= c \,) (zero? depth)) (recur (rest cs) depth [] (conj out cur))
             :else (recur (rest cs) depth (conj cur c) out))
           (conj out cur)))))

(defn simple-class-selectors
  "Return the set of single-class selector names from a compiled stylesheet
   (same rule as the Task-1 extractor: one leading dot, no combinators/chains)."
  [css]
  (into #{}
        (for [[_ sels] (re-seq #"(?m)^(\.[^{}@]+?)\s*\{" css)
              sel (split-selectors sels)
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
;; 1. SCOPE GENERATION + CLASSIFICATION
;; ============================================================================
;; build-classification returns {old-class -> [kind target]} for every class in
;; the utility scope. kind ∈ #{:same :rename :custom :drop :unclassified}.
;; target is the tailwind class name for :same/:rename, else nil.

(defn numeric-rename
  "p-8 -> p-2 etc. Returns nil when suffix not in :size-map."
  [family suffix]
  (when-let [ts (get (:size-map mapping) suffix)]
    (str family "-" ts)))

(defn expand-spacing
  "p/m/gap/top/right/bottom/left over $sizes -> numeric rename via size-map."
  [acc]
  (reduce (fn [a fam]
            (reduce (fn [a k]
                      (assoc a (str fam "-" k) [:rename (numeric-rename fam k)]))
                    a (:sizes-keys mapping)))
          acc (:spacing-families mapping)))

(defn expand-neg-margins
  "-m/-mx/.. over $sizes. Size 0 -> positive m-0 form (TW has no -*-0)."
  [acc]
  (reduce (fn [a fam]
            (reduce (fn [a k]
                      (let [ts (get (:size-map mapping) k)
                            target (if (= ts "0")
                                     (str (subs fam 1) "-0")     ;; -mt -> mt-0
                                     (str fam "-" ts))]          ;; -mt-8 -> -mt-2
                        (assoc a (str fam "-" k) [:rename target])))
                    a (:sizes-keys mapping)))
          acc (:neg-margin-families mapping)))

(defn expand-sizing
  "w/min-w/max-w/h/min-h/max-h over $sizes-ext.
   numeric -> rename; full/auto -> same; fractions -> custom.
   Exception: max-w-auto / max-h-auto have NO Tailwind utility (TW offers
   max-w-none, not max-w-auto) -> custom."
  [acc]
  (let [no-tw-keyword #{"max-w-auto" "max-h-auto"}]
    (reduce
     (fn [a fam]
       (as-> a a
         (reduce (fn [a k] (assoc a (str fam "-" k) [:rename (numeric-rename fam k)]))
                 a (:sizes-ext-numeric mapping))
         (reduce (fn [a k]
                   (let [c (str fam "-" k)]
                     (assoc a c (if (no-tw-keyword c) [:custom nil] [:same c]))))
                 a (:sizes-ext-keyword mapping))
         (reduce (fn [a k] (assoc a (str fam "-" k) [:custom nil]))
                 a (:sizes-ext-fraction mapping))))
     acc (:sizing-families mapping))))

(defn expand-rounded
  "rounded-<radius> (all corners) -> same (radii themed), except full -> custom.
   rounded-<side|corner>-<radius> -> custom (old set FULL radius: buggy)."
  [acc]
  (let [sides ["t" "r" "b" "l" "tl" "tr" "br" "bl"]]
    (reduce
     (fn [a r]
       (as-> a a
         (assoc a (str "rounded-" r)
                (if (= r "full") [:custom nil] [:same (str "rounded-" r)]))
         (reduce (fn [a s] (assoc a (str "rounded-" s "-" r) [:custom nil]))
                 a sides)))
     acc (:radii-keys mapping))))

(defn expand-borders
  "border / outline WIDTH families -> custom (TW uses var(--tw-*-style) +
   different logical properties; not value-identical). Bare aliases too."
  [acc]
  (let [bare ["border" "border-x" "border-y" "border-t" "border-r" "border-b"
              "border-l" "outline"]
        wfam ["border" "border-x" "border-y" "border-t" "border-r" "border-b"
              "border-l" "outline" "outline-offset"]]
    (as-> acc a
      (reduce (fn [a b] (assoc a b [:custom nil])) a bare)
      (reduce (fn [a fam]
                (reduce (fn [a w] (assoc a (str fam "-" w) [:custom nil]))
                        a (:widths-keys mapping)))
              a wfam))))

(defn expand-grid
  "grid-cols/rows-<n> + col/row-span/start/end-<n> -> same;
   grid-cols/rows-<n>-fr -> custom (repeat(n,1fr), no value-identical TW form)."
  [acc]
  (reduce
   (fn [a i]
     (as-> a a
       (assoc a (str "grid-cols-" i) [:same (str "grid-cols-" i)])
       (assoc a (str "grid-rows-" i) [:same (str "grid-rows-" i)])
       (assoc a (str "grid-cols-" i "-fr") [:custom nil])
       (assoc a (str "grid-rows-" i "-fr") [:custom nil])
       (reduce (fn [a p] (assoc a (str p i) [:same (str p i)]))
               a ["col-span-" "col-start-" "col-end-"
                  "row-span-" "row-start-" "row-end-"])))
   acc (range 1 (inc (:grid-range mapping)))))

(defn expand-order
  "order-0..N -> same; order--1..--N -> rename -order-1..-order-N (TW v4
   emits order:calc(N*-1), value-identical after calc eval)."
  [acc]
  (reduce
   (fn [a i]
     (as-> a a
       (assoc a (str "order-" i) [:same (str "order-" i)])   ;; positive & 0
       (if (pos? i)
         (assoc a (str "order--" i) [:rename (str "-order-" i)])
         a)))
   acc (range 0 (inc (:order-range mapping)))))

(defn expand-opacity [acc]
  (reduce (fn [a i] (assoc a (str "opacity-" i) [:same (str "opacity-" i)]))
          acc (range 0 101 (:opacity-step mapping))))

(defn expand-colors
  "_colors.scss loops colormap.$colors:
     text-/bg-/border-/outline-<color> -> same (color var resolves to old value)
     text-decor-<color> -> rename decoration-<color>
     icon-<color>, icon-<color>-important, shadow-<color> -> custom (no TW form)"
  [acc]
  (reduce
   (fn [a c]
     (as-> a a
       (assoc a (str "text-" c) [:same (str "text-" c)])
       (assoc a (str "bg-" c) [:same (str "bg-" c)])
       (assoc a (str "border-" c) [:same (str "border-" c)])
       (assoc a (str "outline-" c) [:same (str "outline-" c)])
       (assoc a (str "text-decor-" c) [:rename (str "decoration-" c)])
       (assoc a (str "icon-" c) [:custom nil])
       (assoc a (str "icon-" c "-important") [:custom nil])
       (assoc a (str "shadow-" c) [:custom nil])))
   acc (:colors mapping)))

(defn base-classification
  "Explicit maps/sets from the mapping edn (irregular classes)."
  [acc]
  (as-> acc a
    (reduce (fn [a [o n]] (assoc a o [:rename n])) a (:rename mapping))
    (reduce (fn [a c] (assoc a c [:same c])) a (:same mapping))
    (reduce (fn [a c] (assoc a c [:custom nil])) a (:custom mapping))
    (reduce (fn [a c] (assoc a c [:drop nil])) a (:drop mapping))))

(defn build-classification []
  ;; Generation order: explicit base first, then generated families. Generated
  ;; families use assoc (override) but by construction never collide with the
  ;; explicit customs/sames except intentionally (e.g. rounded-md is generated
  ;; :same; rounded (bare) is explicit :rename). We assert consistency below.
  (-> {}
      (base-classification)
      (expand-spacing)
      (expand-neg-margins)
      (expand-sizing)
      (expand-rounded)
      (expand-borders)
      (expand-grid)
      (expand-order)
      (expand-opacity)
      (expand-colors)))

;; auto-margins -> :same (m-auto etc). Add explicitly (kept simple).
(defn add-auto-margins [acc]
  (reduce (fn [a c] (assoc a c [:same c])) acc (:auto-margins mapping)))

;; ============================================================================
;; 2. NORMALIZER (value-equivalence, never looser than value-identity)
;; ============================================================================
;; Applied identically to old and Tailwind declaration blocks. Each transform is
;; exact value-equivalence:
;;   * var(--name)      -> its @theme literal (Tailwind defines it to that value)
;;   * calc(a * b)      -> arithmetic product (deterministic, exact)
;;   * opacity: N%      -> N/100 (CSS spec: percentage opacity == decimal)
;;   * <n>rem           -> <n*16>px  (the design system's documented 16px root;
;;                         every $sizes comment maps rem->px at 16, and the util
;;                         values are exact 1/16 multiples so *16 is integer-exact)
;;   * 0px/0rem/0em/0%  -> 0 ; hex lowercased ; whitespace collapsed
;; It never collapses two genuinely different values (a wrong mapping like
;; p-8->p-4 still yields 8px vs 16px -> mismatch).

(defn parse-theme-vars
  "Parse `--name: value;` lines from the @layer theme{:root,:host{...}} block."
  [css]
  (into {}
        (for [[_ n v] (re-seq #"--([a-z0-9-]+):\s*([^;]+);" css)]
          [n (str/trim v)])))

(defn resolve-vars [s theme]
  ;; substitute simple var(--name) (no comma/fallback); iterate a few times.
  (loop [s s n 0]
    (let [s' (str/replace s #"var\(--([a-z0-9-]+)\)"
                          (fn [[whole nm]] (or (get theme nm) whole)))]
      (if (or (= s' s) (>= n 5)) s' (recur s' (inc n))))))

(defn fmt-num [x]
  (let [r (Math/round (double x))]
    (if (< (Math/abs (- (double x) r)) 1e-9)
      (str r)
      ;; strip trailing zeros
      (-> (format "%.6f" (double x))
          (str/replace #"0+$" "")
          (str/replace #"\.$" "")))))

(defn eval-calc-mul
  "calc(A * B) -> product, keeping the single unit present. Repeats."
  [s]
  (let [re #"calc\(\s*(-?[0-9.]+)(rem|px|em|%|deg)?\s*\*\s*(-?[0-9.]+)(rem|px|em|%|deg)?\s*\)"]
    (loop [s s n 0]
      (let [s' (str/replace s re
                            (fn [[_ a ua b ub]]
                              (str (fmt-num (* (Double/parseDouble a)
                                               (Double/parseDouble b)))
                                   (or (not-empty ua) (not-empty ub) ""))))]
        (if (or (= s' s) (>= n 5)) s' (recur s' (inc n)))))))

(defn rem->px [s]
  (str/replace s #"(-?[0-9.]+)rem"
               (fn [[_ n]] (str (fmt-num (* 16 (Double/parseDouble n))) "px"))))

(defn zero-norm [s]
  ;; standalone zero of any length unit -> bare 0  (keep angle deg as-is)
  (str/replace s #"(?<![0-9.])-?0(?:\.0+)?(px|rem|em|%)?(?![0-9.])" "0"))

(defn slash-norm [s]
  ;; spaces around `/` are insignificant in the shorthands we compare
  ;; (grid-column: span 1 / span 1 == span 1/span 1)
  (str/replace s #"\s*/\s*" "/"))

(defn expand-logical
  "Rewrite Tailwind v4 logical axis properties to the physical pairs the old
   css used. VALID VALUE-EQUIVALENCE ONLY UNDER LTR: `margin-inline: X` computes
   to exactly `margin-left:X; margin-right:X` when the writing mode is
   left-to-right. The Explorama codebase is LTR-only (no `direction:rtl` /
   `dir=\"rtl\"` / writing-mode anywhere outside vendored node_modules), so this
   is exact for this codebase -- same class of documented invariant as the 16px
   root. Applied to both sides (old has no logical props, so it is untouched)."
  [block]
  (-> block
      (str/replace #"(?i)(padding|margin)-inline:\s*([^;]+);?"
                   (fn [[_ p v]] (str p "-left: " v "; " p "-right: " v ";")))
      (str/replace #"(?i)(padding|margin)-block:\s*([^;]+);?"
                   (fn [[_ p v]] (str p "-top: " v "; " p "-bottom: " v ";")))))

(defn norm-decl
  "Normalize a single `prop: value` declaration to a canonical `prop:value`."
  [decl theme]
  (let [i (str/index-of decl ":")]
    (if-not i
      (str/trim (str/lower-case decl))
      (let [prop (-> (subs decl 0 i) str/trim str/lower-case)
            val0 (-> (subs decl (inc i)) str/trim str/lower-case)
            val (cond-> val0
                  (str/includes? prop "opacity")
                  (str/replace #"([0-9.]+)%"
                               (fn [[_ n]] (fmt-num (/ (Double/parseDouble n) 100.0)))))
            val (-> val
                    (resolve-vars theme)
                    eval-calc-mul
                    rem->px
                    zero-norm
                    slash-norm
                    (str/replace #"\s+" " ")
                    str/trim)]
        (str prop ":" val)))))

(defn norm-block
  "Normalize a declaration block into a canonical sorted `p:v;p:v` string.
   Sorting makes property ORDER irrelevant (all utilities set distinct
   properties, so order does not affect the computed style)."
  [block theme]
  (when block
    (->> (str/split (expand-logical block) #";")
         (map str/trim)
         (remove str/blank?)
         (map #(norm-decl % theme))
         sort
         (str/join ";"))))

;; ============================================================================
;; 3. PROBE BUILD + DECLARATION EXTRACTION
;; ============================================================================
(defn parse-tw-rules
  "Parse `  .<name> {\\n ...decls... \\n  }` blocks from a Tailwind build.
   Returns {class-name raw-body}. Unescapes `\\` in selectors."
  [css]
  (let [lines (str/split-lines css)]
    (loop [ls lines, cur nil, body [], out {}]
      (if-let [l (first ls)]
        (let [t (str/trim l)]
          (cond
            ;; start of a single-class rule at utilities indent
            (and (nil? cur) (re-matches #"\.[^{},]+\{" t))
            (recur (rest ls) (-> t (subs 0 (dec (count t))) str/trim
                                 (str/replace "\\" "") (subs 1))
                   [] out)
            (and cur (= t "}"))
            (recur (rest ls) nil [] (assoc out cur (str/join " " body)))
            cur
            (recur (rest ls) cur (conj body t) out)
            :else
            (recur (rest ls) cur body out)))
        out))))

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
               (str "(?<![\\w-])" cls "(?![\\w-])")
               (filter #(.exists (io/file %)) markup-roots))]
    (if (zero? exit)
      (count (remove str/blank? (str/split-lines out)))
      0)))

;; ============================================================================
;; 5. RUN
;; ============================================================================
(defn -main []
  (let [cls (-> (build-classification) add-auto-margins)
        classified-names (set (keys cls))
        old-names (set (keys old))
        scope (utility-scope)                     ;; authoritative: compiled partials
        ;; every scoped class must exist in old css (both come from the partials)
        scope-missing-from-old (sort (remove old-names scope))
        ;; every scoped class must be classified by the mapping
        unclassified (sort (remove classified-names scope))
        ;; classification we produced for names the partials do NOT emit
        ;; (over-coverage / generation drift) -- informational sanity check
        classified-not-in-scope (sort (remove scope classified-names))
        by-kind (group-by (fn [c] (first (cls c))) (filter classified-names scope))
        ;; component = old classes NOT in the utility scope (kept as-is, not audited)
        components (sort (remove scope old-names))

        ;; ---- declaration parity (only scoped, classified :same/:rename) ----
        targets (keep (fn [c] (let [[k t] (cls c)]
                                (when (#{:same :rename} k) t)))
                      (filter classified-names scope))
        tw-css (build-probe targets)
        tw-rules (parse-tw-rules tw-css)
        theme (parse-theme-vars tw-css)
        mismatches
        (for [c (sort (filter classified-names scope))
              :let [[k t] (cls c)]
              :when (#{:same :rename} k)
              :let [o (norm-block (get old c) theme)
                    n (norm-block (get tw-rules t) theme)]
              :when (not= o n)]
          {:old c :new t :kind k :old-decl o :new-decl n
           :old-raw (get old c) :new-raw (get tw-rules t)})

        ;; ---- :drop usage ----
        drops (filter (fn [c] (= :drop (first (cls c)))) (filter classified-names scope))
        drop-usages (into {} (for [d drops] [d (usage-count d)]))
        bad-drops (sort (map key (filter #(pos? (val %)) drop-usages)))]

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
