(ns de.explorama.frontend.woco.event-logging-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.woco.event-logging :as sut]
            [de.explorama.frontend.woco.markers :as markers]
            [de.explorama.frontend.woco.path :as path]))

(def ^:private frame-id {:frame-id "search-1" :vertical "search"})

(def ^:private db
  (-> {}
      (assoc-in (path/frame-desc frame-id) {:title "Search"})
      (markers/toggle frame-id 100)))

(deftest close-event-drops-the-marker-test
  (let [{:keys [db]} (sut/close-event db nil frame-id nil nil)]
    (is (nil? (get-in db (path/frame-desc frame-id))))
    (is (nil? (get-in db (path/marker frame-id)))
        "closing a marked frame through the replay path also removes its marker")))
