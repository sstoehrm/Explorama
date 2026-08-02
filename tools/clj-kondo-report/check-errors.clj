#!/usr/bin/env bb
(require '[clojure.edn :as edn])

;; The server bundle is reported but not gated. Its db_api.cljs errors come
;; from the broken instances contract that .clj-kondo/config.edn silences for
;; that namespace; anything else it reports is advisory.
(def ^:private checks
  [["Browser" "browser_check.edn"]
   ["Electron" "electron_check.edn"]
   ["Plugins" "plugins_check.edn"]])

(let [counts (keep (fn [[label file]]
                     (let [n (get-in (edn/read-string (slurp file))
                                     [:summary :error]
                                     0)]
                       (when (pos? n)
                         [label n])))
                   checks)]
  (if (seq counts)
    (do
      (doseq [[label n] counts]
        (println (format "%s: %d clj-kondo error(s)" label n)))
      (println)
      (println "clj-kondo errors fail the build. Warnings stay advisory -")
      (println "see the job summary for the full report.")
      (System/exit 1))
    (println "No clj-kondo errors.")))
