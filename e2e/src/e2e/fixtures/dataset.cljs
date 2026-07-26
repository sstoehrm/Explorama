(ns e2e.fixtures.dataset)

;; Browser-bundle-specific facts about the sample datasources the suite
;; drives through the UI. Kept in one place so a future server-bundle run
;; of these specs only has to change values here.

(def netflix-name "Netflix")
(def netflix-event-count "323 Events")

(def import-name "e2e-import")
(def import-event-count "3 Events")

(def tool-count 7)
