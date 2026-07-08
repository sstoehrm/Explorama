(ns extract-old-css
  (:require [clojure.string :as str]))

;; Splits a selector list on commas, but only at paren depth 0 so that
;; commas inside functional pseudo-class argument lists (":not(...)",
;; ":is(...)", ":where(...)") do not break a selector apart.
(defn split-selectors [s]
  (map (partial apply str)
       (loop [cs (seq s) depth 0 cur [] out []]
         (if-let [c (first cs)]
           (cond
             (= c \() (recur (rest cs) (inc depth) (conj cur c) out)
             (= c \)) (recur (rest cs) (max 0 (dec depth)) (conj cur c) out)
             (and (= c \,) (zero? depth)) (recur (rest cs) depth [] (conj out cur))
             :else (recur (rest cs) depth (conj cur c) out))
           (conj out cur)))))

;; Extracts {class-name declarations} for simple single-class selectors from
;; the compiled stylesheet. Utility classes are all simple selectors; complex
;; selectors (descendants, pseudo, attribute) belong to component css and are
;; ignored. A rule's selector part may be a comma-separated list of selectors
;; (e.g. ".col-1, .col-2, .col-3 { ... }"); each comma-separated selector is
;; considered independently and kept when it is itself a simple single-class
;; selector, sharing the same (normalized) declaration block.
(defn parse [css]
  (into (sorted-map)
        (for [[_ sels body] (re-seq #"(?m)^(\.[^{}@]+?)\s*\{([^}]*)\}" css)
              sel (split-selectors sels)
              :let [sel (-> sel str/trim (str/replace #"\\" ""))]
              ;; single class only: exactly one leading dot, no combinators
              :when (and (str/starts-with? sel ".")
                         (not (re-find #"[ >~+:\[]" (subs sel 1)))
                         ;; ".foo.bar" chained selectors excluded
                         (not (str/includes? (subs sel 1) ".")))]
          [(subs sel 1)
           (-> body str/trim (str/replace #"\s+" " "))])))

(let [css (slurp "dist/css/style.css")
      m (parse css)]
  (spit "../docs/superpowers/artifacts/tailwind/old-utilities.edn" (pr-str m))
  (println (count m) "simple class selectors extracted"))
