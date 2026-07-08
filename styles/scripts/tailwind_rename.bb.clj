#!/usr/bin/env bb
(ns tailwind-rename
  "Markup rename codemod for the Tailwind migration (Task 4).

   Rewrites old utility class names to their new Tailwind equivalents in
   ClojureScript markup, in TWO syntactic forms:

     1. String literals:          {:class \"flex p-8\"}   ->  {:class \"flex p-2\"}
     2. Hiccup element keywords:  [:div.flex.p-8 ...]     ->  [:div.flex.p-2 ...]

   Rename source (THE rename table, single source of truth):
     docs/superpowers/artifacts/tailwind/resolved-renames.edn
   the complete resolved {old-name new-name} map (1221 entries) produced by
   scripts/tailwind_audit.bb.clj. Regenerate it with `bb scripts/tailwind_audit.bb.clj`
   before running this codemod.

   Known-old-class set (for the unknown-token gate/report):
     keys of docs/superpowers/artifacts/tailwind/old-utilities.edn

   Single-pass, whole-token replacement (no sequential corruption): a combined
   regex matches EITHER a string literal OR a Hiccup keyword; str/replace
   consumes matches left-to-right, non-overlapping, so a keyword-looking
   substring inside a string literal is never touched twice.

   DRY-RUN by default. Pass --apply to write files. Pass --dir PATH (repeatable)
   to override the target trees (used by the fixture self-test).

   Reporting (stdout):
     * files that would change / were changed
     * per-form counts (string literals & token renames vs keyword literals & segment renames)
     * unknown-token list: tokens co-occurring with a rename inside a string that
       was therefore SKIPPED (conservative: only strings whose tokens are ALL known
       get rewritten)
     * manual-fixes list: keyword class-segments whose rename target is keyword-unsafe
       (contains '/' or '.', which are Hiccup class delimiters) -> left unchanged, must
       be converted to a :class string by hand (Task 5)
     * skipped-keywords: keywords we conservatively did NOT treat as markup
       (namespaced / non-HTML tag) but which contain a renameable dot-segment

   Known limitation: this is a regex codemod over source text. It cannot reliably
   tell code from comments, so a class list that literally appears inside a line
   comment (;; ...) could in principle be rewritten. In practice class lists do not
   appear in comments here; the all-tokens-known gate makes an accidental match
   near-impossible. Multi-line string/keyword literals (a quote or keyword split
   across a newline) are not matched (recoverable false negative)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Locate artifacts & trees relative to this script (repo/styles/scripts/...)
;; ---------------------------------------------------------------------------

(def ^:private script-file
  (fs/absolutize (or *file* "styles/scripts/tailwind_rename.bb.clj")))

;; scripts -> styles -> repo
(def ^:private repo-root (-> script-file fs/parent fs/parent fs/parent))

(defn- under [& parts] (str (apply fs/path repo-root parts)))

(def renames
  "THE rename table: {old-class-name new-tailwind-class-name}."
  (edn/read-string
   (slurp (under "docs" "superpowers" "artifacts" "tailwind" "resolved-renames.edn"))))

(def old-classes
  "Set of every known old class name (the unknown-token universe)."
  (set (keys (edn/read-string
              (slurp (under "docs" "superpowers" "artifacts" "tailwind" "old-utilities.edn"))))))

(def default-dirs
  [(under "plugins" "frontend")
   (under "plugins" "shared")
   (under "bundles" "browser" "frontend")
   (under "bundles" "electron" "frontend")
   (under "bundles" "server" "frontend")])

;; ---------------------------------------------------------------------------
;; Token classification
;; ---------------------------------------------------------------------------

(defn keyword-unsafe-target?
  "A rename target that CANNOT be expressed as a Hiccup keyword dot-segment,
   because '/' and '.' are both class delimiters in :tag.class.class keywords.
   (e.g. w-1-3 -> w-1/3, p-10 -> p-2.5). Bang '!' IS keyword-safe."
  [target]
  (or (str/includes? target "/")
      (str/includes? target ".")))

(defn component-like?
  "A plausible component/BEM class (e.g. welcome__page) -- passes through
   untouched but counts as 'known' so a string that mixes it with utilities
   is still eligible for rewriting."
  [t]
  (str/includes? t "__"))

(defn token-known?
  "Renameable, a known old utility, or a plausible component class."
  [t]
  (or (contains? renames t)
      (contains? old-classes t)
      (component-like? t)))

;; ---------------------------------------------------------------------------
;; HTML/SVG tag whitelist. A keyword is only treated as markup when its tag is
;; one of these -- conservative: namespaced keywords / re-frame event ids never
;; match (their first segment is not an HTML tag), so their code is never
;; corrupted. False negatives are reported, not rewritten.
;; ---------------------------------------------------------------------------

(def html-tags
  #{"a" "abbr" "address" "area" "article" "aside" "audio" "b" "bdi" "bdo"
    "blockquote" "br" "button" "canvas" "caption" "cite" "code" "col"
    "colgroup" "data" "datalist" "dd" "del" "details" "dfn" "dialog" "div"
    "dl" "dt" "em" "embed" "fieldset" "figcaption" "figure" "footer" "form"
    "h1" "h2" "h3" "h4" "h5" "h6" "header" "hgroup" "hr" "i" "iframe" "img"
    "input" "ins" "kbd" "label" "legend" "li" "main" "map" "mark" "menu"
    "meter" "nav" "object" "ol" "optgroup" "option" "output" "p" "param"
    "picture" "pre" "progress" "q" "rp" "rt" "ruby" "s" "samp" "section"
    "select" "slot" "small" "source" "span" "strong" "sub" "summary" "sup"
    "table" "tbody" "td" "template" "textarea" "tfoot" "th" "thead" "time"
    "title" "tr" "track" "u" "ul" "var" "video" "wbr"
    ;; svg
    "svg" "path" "g" "rect" "circle" "ellipse" "line" "polyline" "polygon"
    "text" "tspan" "use" "defs" "clipPath" "clippath" "linearGradient"
    "radialGradient" "stop" "mask" "pattern" "filter" "image" "symbol"
    "marker" "foreignObject"})

;; ---------------------------------------------------------------------------
;; Mutable accumulators (bb script scope)
;; ---------------------------------------------------------------------------

(def stats (atom {:files-changed #{}
                  :string-literals 0
                  :string-token-renames 0
                  :keyword-literals 0
                  :keyword-seg-renames 0}))
(def unknown-report (atom []))   ; {:file :line :string :unknown [tokens]}
(def manual-fixes (atom []))     ; {:file :line :old :target}
(def skipped-keywords (atom []))  ; {:file :line :keyword :renameable [tokens]}

(defn- mark-file! [file] (swap! stats update :files-changed conj file))

;; ---------------------------------------------------------------------------
;; String literal rewriting
;; ---------------------------------------------------------------------------

(defn- split-classes [s]
  (if (str/blank? s) [] (str/split (str/trim s) #"\s+")))

(defn handle-string
  "Rewrite class tokens inside one \"...\" string literal (quotes included).
   Conservative rule: rewrite only when >=1 token renames AND every token is
   known. If a renameable token co-occurs with an unknown token, SKIP the
   string and report the unknown token(s)."
  [file line whole inner]
  (let [toks       (split-classes inner)
        renameable (filter #(contains? renames %) toks)
        unknown    (remove token-known? toks)]
    (cond
      (empty? renameable)
      whole                              ; nothing to rename -> leave untouched

      (seq unknown)                      ; rename mixed with unknown -> skip + report
      (do (swap! unknown-report conj
                 {:file file :line line :string inner :unknown (vec (distinct unknown))})
          whole)

      :else                              ; all tokens known -> rewrite
      (let [new-toks (mapv #(or (get renames %) %) toks)
            changed  (count (filter true? (map not= toks new-toks)))]
        (swap! stats update :string-literals inc)
        (swap! stats update :string-token-renames + changed)
        (mark-file! file)
        (str \" (str/join " " new-toks) \")))))

;; ---------------------------------------------------------------------------
;; Hiccup keyword rewriting
;; ---------------------------------------------------------------------------

(defn- renameable-segments
  "Every '.'/'#'/'/'-delimited segment of a keyword body that is a renameable
   class (used to flag conservatively-skipped keywords)."
  [kw]
  (->> (str/split (subs kw 1) #"[.#/]")
       (filter #(contains? renames %))
       distinct vec))

(defn- rewrite-seg
  "Rewrite one keyword segment (leading '.' or '#').
   Returns [new-seg status] where status is true (safe rename), :manual
   (renameable but keyword-unsafe target -> reported, left as-is) or false."
  [file line seg]
  (if (str/starts-with? seg ".")
    (let [cls    (subs seg 1)
          target (get renames cls)]
      (cond
        (nil? target)                    [seg false]
        (keyword-unsafe-target? target)  (do (swap! manual-fixes conj
                                                    {:file file :line line :old cls :target target})
                                             [seg :manual])
        :else                            [(str "." target) true]))
    [seg false]))                        ; #id or other -> untouched

(defn handle-keyword
  "Rewrite a Hiccup element keyword `kw` (leading ':' included). `bracket` is
   the captured '[' + whitespace prefix. Returns the (possibly) rewritten
   bracket+keyword string."
  [file line bracket kw]
  (let [rebuilt
        (cond
          ;; namespaced / auto-namespaced keyword -> not markup
          (str/includes? kw "/")
          (do (when-let [rs (seq (renameable-segments kw))]
                (swap! skipped-keywords conj
                       {:file file :line line :keyword kw :renameable (vec rs)}))
              kw)

          :else
          (let [body (subs kw 1)
                m    (re-find #"^([A-Za-z][A-Za-z0-9]*)([.#].*)?$" body)]
            (if (or (nil? m) (nil? (nth m 2)))
              kw                          ; no tag or no dot/hash segments (e.g. :div, :keys)
              (let [tag     (nth m 1)
                    seg-str (nth m 2)
                    segs    (re-seq #"[.#][^.#]+" seg-str)]
                (if-not (contains? html-tags tag)
                  ;; looks dotted but tag is not HTML -> conservatively skip, report
                  (do (when-let [rs (seq (renameable-segments kw))]
                        (swap! skipped-keywords conj
                               {:file file :line line :keyword kw :renameable (vec rs)}))
                      kw)
                  ;; eligible markup keyword
                  (let [results  (mapv #(rewrite-seg file line %) segs)
                        new-segs (map first results)
                        n-safe   (count (filter #(true? (second %)) results))]
                    (if (pos? n-safe)
                      (do (swap! stats update :keyword-literals inc)
                          (swap! stats update :keyword-seg-renames + n-safe)
                          (mark-file! file)
                          (str ":" tag (str/join new-segs)))
                      kw)))))))]         ; only unsafe/no renames -> unchanged
    (str bracket rebuilt)))

;; ---------------------------------------------------------------------------
;; Combined single-pass line rewriter
;; ---------------------------------------------------------------------------

;; Alternative 1: a "..." string literal (class-safe charset only).
;; Alternative 2: '[' + ws (group 2) + a single-colon keyword starting with a
;; letter (group 3). '::' and ':>' never match (2nd char must be a letter).
(def combined-re
  #"(\"[A-Za-z0-9 _.:\\/-]*\")|(\[\s*)(:[A-Za-z][A-Za-z0-9_.#!/-]*)")

(defn rewrite-line [file line-no line]
  (str/replace line combined-re
               (fn [m]
                 (let [whole   (nth m 0)
                       strlit  (nth m 1)
                       bracket (nth m 2)
                       kw      (nth m 3)]
                   (cond
                     strlit (handle-string file line-no whole
                                           (subs strlit 1 (dec (count strlit))))
                     kw     (handle-keyword file line-no bracket kw)
                     :else  whole)))))

(defn rewrite-file [f apply?]
  (let [path  (str f)
        src   (slurp path)
        lines (str/split src #"\n" -1)
        out   (->> lines
                   (map-indexed (fn [i line] (rewrite-line path (inc i) line)))
                   (str/join "\n"))
        changed? (not= src out)]
    (when (and changed? apply?)
      (spit path out))
    changed?))

;; ---------------------------------------------------------------------------
;; Driver + report
;; ---------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [as args, apply? false, dirs []]
    (if (empty? as)
      {:apply? apply? :dirs (if (seq dirs) dirs default-dirs)}
      (let [a (first as)]
        (cond
          (= a "--apply")   (recur (rest as) true dirs)
          (= a "--dry-run") (recur (rest as) apply? dirs)
          (= a "--dir")     (recur (drop 2 as) apply? (conj dirs (second as)))
          :else             (recur (rest as) apply? dirs))))))

(defn- glob-files [dirs]
  (sort (mapcat (fn [d] (when (fs/exists? d) (fs/glob d "**{.cljs,.cljc}"))) dirs)))

(defn- print-report [apply? files changed]
  (let [{:keys [files-changed string-literals string-token-renames
                keyword-literals keyword-seg-renames]} @stats
        unknown-tokens (->> @unknown-report
                            (mapcat :unknown)
                            frequencies
                            (sort-by (comp - val)))
        mfs (sort-by (juxt :file :line) @manual-fixes)
        sks (sort-by (juxt :file :line) @skipped-keywords)]
    (println "============================================================")
    (println "Tailwind markup rename codemod --" (if apply? "APPLY" "DRY-RUN"))
    (println "============================================================")
    (println "files scanned                :" (count files))
    (println "files that would change      :" (count files-changed) "(rewrite-detected:" changed ")")
    (println)
    (println "-- per-form counts -----------------------------------------")
    (println "string literals rewritten    :" string-literals)
    (println "  token renames in strings   :" string-token-renames)
    (println "keyword literals rewritten   :" keyword-literals)
    (println "  segment renames in keywords:" keyword-seg-renames)
    (println)
    (println "-- unknown tokens co-occurring with renames (strings SKIPPED) --")
    (println "   (" (count @unknown-report) "string(s) skipped;" (count unknown-tokens) "distinct token(s))")
    (doseq [[tok n] unknown-tokens]
      (let [sample (first (filter #((set (:unknown %)) tok) @unknown-report))]
        (println (format "   %5d  %-40s  e.g. %s:%s"
                         n tok (:file sample) (:line sample)))))
    (println)
    (println "-- manual fixes: keyword class-segments with keyword-unsafe target --")
    (println "   (" (count mfs) "occurrence(s); target has '/' or '.', left UNCHANGED) --")
    (doseq [{:keys [file line old target]} mfs]
      (println (format "   %s:%s  .%s  ->  %s" file line old target)))
    (println)
    (println "-- skipped keywords: dotted keyword w/ renameable segment, NOT rewritten --")
    (println "   (" (count sks) "occurrence(s); namespaced or non-HTML tag) --")
    (doseq [{:keys [file line keyword renameable]} sks]
      (println (format "   %s:%s  %s   renameable-segments=%s" file line keyword renameable)))
    (println "============================================================")))

(let [{:keys [apply? dirs]} (parse-args *command-line-args*)
      files   (glob-files dirs)
      changed (count (filter #(rewrite-file % apply?) files))]
  (print-report apply? files changed))
