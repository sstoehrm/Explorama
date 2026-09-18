(ns de.explorama.frontend.woco.agent-ops-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.common.frontend-interface]
            [de.explorama.frontend.woco.agent-ops :as sut]
            [de.explorama.frontend.woco.path :as path]))

(def ^:private search-id {:frame-id "search-1" :vertical "search" :workspace-id "w"})
(def ^:private table-id {:frame-id "table-1" :vertical "table" :workspace-id "w"})
(def ^:private mosaic-id {:frame-id "mosaic-1" :vertical "mosaic" :workspace-id "w"})

(def ^:private db
  (-> {}
      (assoc-in (path/frame-desc search-id)
                {:coords [10 20] :size [600 400] :title "Search" :z-index 1 :type :frame/content-type
                 :publishing? true})
      (assoc-in (path/frame-desc table-id)
                {:coords [700 20] :size [500 300] :title "Table" :z-index 2 :type :frame/content-type
                 :is-minimized? true :published-by-frame search-id})
      (assoc-in (path/frame-desc mosaic-id)
                {:coords [700 400] :size [500 300] :title "Mosaic" :z-index 3 :type :frame/content-type
                 :published-by-frame search-id})
      (assoc-in (path/frame-couple-with table-id) [mosaic-id])
      (assoc-in (path/frame-couple-with mosaic-id) [table-id])
      (assoc-in (path/frame-header-color search-id) "group-1")
      (assoc-in (path/workspace-id) "w")
      (assoc-in path/navigation-position {:x 0 :y 0 :z 1})))

(deftest frames-test
  (let [frames (sut/frames db)
        table (first (filter #(= table-id (:id %)) frames))]
    (is (= 3 (count frames)))
    (is (= {:id table-id :vertical "table" :type :frame/content-type :title "Table"
            :left 700 :top 20 :width 500 :height 300 :z-index 2
            :minimized? true :maximized? false :di nil :published-by search-id :color-group "group-1"}
           table)
        "the color group falls back to the publishing frame's group")))

(deftest connections-test
  (is (= #{{:from search-id :to table-id :kind :publishes}
           {:from search-id :to mosaic-id :kind :publishes}
           {:from mosaic-id :to table-id :kind :coupled}}
         (set (sut/connections db)))
      "a coupling appears once, not once per side"))

(deftest workspace-test
  (with-redefs [de.explorama.frontend.common.frontend-interface/call-api (fn [api & _] (when (= :loaded-project-db-get api) {:project-id "p"}))]
    (is (= {:interaction-mode :normal :workspace-id "w" :viewport {:x 0 :y 0 :z 1} :project {:project-id "p"}}
           (sut/workspace db)))))

(deftest open-vertical-test
  (testing "an unknown visual vertical fails without dispatching"
    (let [failed (atom nil)]
      (with-redefs [de.explorama.frontend.common.frontend-interface/call-api (fn [& _] {})]
        (is (= {} (sut/open-vertical {:db db :params {:vertical "table"} :ok identity :fail (fn [t m] (reset! failed [t m]))})))
        (is (= :invalid-params (first @failed))))))
  (testing "a known vertical dispatches its open event with a forced position and then waits for the frame"
    (with-redefs [de.explorama.frontend.common.frontend-interface/call-api (fn [& _] {:table {:event :table/open}})]
      (let [{[open wait] :dispatch-n} (sut/open-vertical {:db db :params {:vertical "table" :source-frame-id search-id :position [1 2]} :ok identity :fail identity})]
        (is (= [:table/open search-id [1 2] true {:overwrites {:behavior {:force :provided-position}}}] open))
        (is (= ::sut/reply-new-frame (first wait)))
        (is (= "table" (nth wait 2)))
        (is (= #{search-id table-id mosaic-id} (nth wait 3)))))))

(deftest new-frame-detection-test
  (is (= table-id (sut/new-frame db "table" #{search-id mosaic-id})))
  (is (nil? (sut/new-frame db "table" #{search-id table-id mosaic-id}))))

(deftest set-geometry-test
  (let [{events :dispatch-n} (sut/set-geometry {:db db :params {:frame-id table-id :left 1 :top 2 :width 3 :height 4} :ok identity :fail identity})]
    (is (= [:de.explorama.frontend.woco.frame.api/set-frame-coords table-id 1 2] (first events)))
    (is (= [:de.explorama.frontend.woco.frame.api/set-frame-size table-id 3 4 3 4] (second events)))
    (is (= ::sut/reply-frame (first (nth events 2))))))

(deftest unknown-frame-test
  (let [failed (atom nil)]
    (is (= {} (sut/set-geometry {:db db :params {:frame-id {:frame-id "nope"}} :ok identity :fail (fn [t _] (reset! failed t))})))
    (is (= :invalid-params @failed))))

(deftest state-op-test
  (let [handler (sut/state-op (fn [db frame-id] {:frame-id frame-id :di (get-in db (path/frame-desc frame-id))}))]
    (testing "a known frame calls ok with the state-fn result"
      (let [ok-result (atom nil)]
        (is (= {} (handler {:db db :params {:frame-id table-id} :ok (fn [r] (reset! ok-result r)) :fail identity})))
        (is (= {:frame-id table-id :di (get-in db (path/frame-desc table-id))} @ok-result))))
    (testing "an unknown frame fails with :invalid-params"
      (let [failed (atom nil)]
        (is (= {} (handler {:db db :params {:frame-id {:frame-id "nope"}} :ok identity :fail (fn [t _] (reset! failed t))})))
        (is (= :invalid-params @failed))))))

(deftest reply-timeout-sweep-test
  (let [captured (atom nil)
        calls (atom [])]
    (with-redefs [js/setTimeout (fn [f _ms] (reset! captured f) 0)]
      (sut/set-geometry {:db db :params {:frame-id table-id :left 1 :top 2 :width 3 :height 4}
                          :ok identity
                          :fail (fn [t m] (swap! calls conj [t m]))}))
    (@captured)
    (is (= [[:timeout "the plugin did not answer"]] @calls))
    (@captured)
    (is (= [[:timeout "the plugin did not answer"]] @calls)
        "a reply that already finished is a no-op for the timer")))
