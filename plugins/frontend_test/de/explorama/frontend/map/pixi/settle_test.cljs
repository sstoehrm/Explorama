(ns de.explorama.frontend.map.pixi.settle-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.pixi.settle :as settle]))

(deftest fires-immediately-when-nothing-pending
  (let [s (-> (settle/new-state) (settle/add-listener :a))
        [s' fired] (settle/note-render s)]
    (is (= [:a] fired))
    (is (empty? (:listeners s')))))

(deftest waits-for-pending-loads
  (let [s (-> (settle/new-state) (settle/add-listener :a) settle/note-load-start)
        [s1 fired1] (settle/note-render s)
        [_ fired2] (settle/note-load-end s1)]
    (is (empty? fired1))
    (is (= [:a] fired2))))

(deftest multiple-loads-fire-once-at-zero
  (let [s (-> (settle/new-state) (settle/add-listener :a)
              settle/note-load-start settle/note-load-start)
        [s1 _] (settle/note-render s)
        [s2 fired1] (settle/note-load-end s1)
        [_ fired2] (settle/note-load-end s2)]
    (is (empty? fired1))
    (is (= [:a] fired2))))

(deftest listener-added-after-settle-needs-new-render
  (let [[s _] (settle/note-render (settle/new-state))
        s (settle/add-listener s :late)
        [_ fired] (settle/note-render s)]
    (is (= [:late] fired))))

(deftest load-end-never-goes-negative
  (let [[s fired] (settle/note-load-end (settle/new-state))]
    (is (zero? (:pending s)))
    (is (empty? fired))))
