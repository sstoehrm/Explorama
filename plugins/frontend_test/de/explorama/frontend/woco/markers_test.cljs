(ns de.explorama.frontend.woco.markers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.woco.markers :as sut]
            [de.explorama.frontend.woco.path :as path]))

(def ^:private a {:frame-id "table-1" :vertical "table"})
(def ^:private b {:frame-id "map-1" :vertical "map"})

(def ^:private db
  (-> {}
      (assoc-in (path/frame-desc a) {:title "Table"})
      (assoc-in (path/frame-desc b) {:title "Map"})))

(deftest toggle-test
  (let [db (sut/toggle db a 100)]
    (is (= {:note nil :set-at 100} (get-in db (path/marker a))))
    (is (nil? (get-in (sut/toggle db a 200) (path/marker a))) "toggling again removes it")))

(deftest note-test
  (let [db (-> db (sut/toggle a 100) (sut/set-note a "fix the sort"))]
    (is (= "fix the sort" (:note (get-in db (path/marker a)))))
    (is (= db (sut/set-note db b "ignored")) "a note on an unmarked frame is ignored")))

(deftest all-test
  (let [db (-> db (sut/toggle b 50) (sut/toggle a 100) (sut/set-note a "here"))]
    (is (= [{:frame-id b :note nil :set-at 50 :title "Map" :vertical "map"}
            {:frame-id a :note "here" :set-at 100 :title "Table" :vertical "table"}]
           (sut/all db))
        "ordered by when they were set")))

(deftest cleanup-test
  (let [db (-> db (sut/toggle a 100) (sut/toggle b 200) (assoc-in path/marker-mode? true))]
    (is (= [b] (mapv :frame-id (sut/all (sut/remove-marker db a)))))
    (is (empty? (sut/all (sut/clear-markers db))))
    (is (true? (get-in (sut/clear-markers db) path/marker-mode?))
        "clear-markers (the :woco/clear-markers op) leaves marker mode alone")
    (is (empty? (sut/all (sut/reset db))))
    (is (nil? (get-in (sut/reset db) path/marker-mode?)) "reset (clean-workspace) clears both")))
