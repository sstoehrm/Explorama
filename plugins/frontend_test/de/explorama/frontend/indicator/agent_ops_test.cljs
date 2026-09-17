(ns de.explorama.frontend.indicator.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is]]
            [de.explorama.frontend.indicator.agent-ops :as sut]
            [de.explorama.frontend.woco.agent-ops :as woco-ops]
            [de.explorama.frontend.woco.path :as wpath]))

(def ^:private fid {:frame-id "indicator-1" :vertical "indicator"})

(def ^:private with-frame-db
  (assoc-in {} (wpath/frame-desc fid)
            {:coords [10 20] :size [800 700] :title "Indicator" :z-index 1}))

(deftest open-existing-frame-test
  (let [replied (atom nil)
        {event :dispatch} (sut/open {:db with-frame-db :params {} :ok (fn [entry] (reset! replied entry)) :fail identity})]
    (is (= [:de.explorama.frontend.woco.frame.api/bring-to-front fid] event))
    (is (= fid (:id @replied)))))

(deftest open-no-existing-frame-test
  (let [{events :dispatch-n} (sut/open {:db {} :params {} :ok identity :fail identity})]
    (is (= ::woco-ops/reply-new-frame (first (second events))))))
