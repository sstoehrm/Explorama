(ns extract-old-css
  (:require [clojure.string :as str]))

;; Extracts {class-name declarations} for simple single-class selectors from
;; the compiled stylesheet. Utility classes are all simple selectors; complex
;; selectors (descendants, pseudo, attribute) belong to component css and are
;; ignored.
(defn parse [css]
  (into (sorted-map)
        (for [[_ sel body] (re-seq #"(?m)^(\.[A-Za-z0-9\\_.-]+)\s*\{([^}]*)\}" css)
              :let [sel (str/replace sel #"\\" "")]
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
