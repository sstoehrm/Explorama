(ns de.explorama.frontend.map.operations.payload-parity-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [de.explorama.frontend.map.event-replay :as event-replay]
            [de.explorama.frontend.map.map.api :as map-api]
            [de.explorama.frontend.map.map.impl.pixi.instance :as inst]
            [de.explorama.frontend.map.operations.tasks :as tasks]
            [de.explorama.frontend.map.paths :as geop]
            [de.explorama.frontend.map.vis-state :as vis-state]))

;; build-base-payload (operations/tasks.cljs) is pure over [db frame-id]: every
;; value it reads comes from a plain (get-in db (geop/... frame-id)) call, no
;; subscriptions/derefs - so it's exercised directly with a fixture db, no
;; re-frame app-db/dispatch machinery needed.

(def frame-id {:frame-id "payload-parity-test" :vertical "map" :workspace "w1"})

(def fixture-db
  (-> {}
      (assoc-in (geop/frame-di frame-id) {:di-id "di-1"})
      (assoc-in (geop/applied-filter frame-id) {:filter :desc})
      (assoc-in (geop/base-layer frame-id) "osm")
      (assoc-in (geop/selected-marker-layouts frame-id) {"m1" {:id "m1"}})
      (assoc-in (geop/selected-feature-layers frame-id) {"f1" {:id "f1"}})
      (assoc-in (geop/selected-overlayers frame-id) #{"o1"})
      (assoc-in (geop/cluster-marker? frame-id) true)
      (assoc-in (geop/popup-desc frame-id) {:event-id ["b" "1"]})
      (assoc-in (geop/highlighted-markers frame-id) #{"m1"})
      (assoc-in (geop/view-position frame-id) {:center [10 20] :zoom 5})))

(deftest build-base-payload-key-set
  (is (= #{:di :local-filter :base-layer :cluster? :marker-layouts :feature-layers
           :popup-desc :highlighted-markers :overlayers :view-position}
         (set (keys (tasks/build-base-payload fixture-db frame-id))))))

(deftest vis-state-snapshot-parity
  (testing "vis-desc's :task-desc IS build-base-payload's output for the same
            [db frame-id] - vis_state.cljs calls tasks/build-base-payload
            directly, so the two are identical by construction, not by
            coincidental agreement"
    (is (= (tasks/build-base-payload fixture-db frame-id)
           (:task-desc (vis-state/vis-desc fixture-db frame-id)))))
  (testing "the live :copy-frame construction (operations/tasks.cljs
            copy-frame-task, live? branch with a source-frame-id) also starts
            from (build-base-payload db source-frame-id), then conj-only-adds
            three wire-transmission bookkeeping keys (:send-data-acs?
            :update-usable-layouts? :new-di?) that are disjoint from the base
            10 - so 'parity' here means: the base 10 keys/values are untouched
            by that merge, and the only key-set delta between the snapshot and
            the live payload is exactly those 3 keys"
    (let [base (tasks/build-base-payload fixture-db frame-id)
          live-copy-frame-core (assoc base
                                       :send-data-acs? true
                                       :update-usable-layouts? true
                                       :new-di? true)]
      (is (= base (select-keys live-copy-frame-core (keys base))))
      (is (= (into (set (keys base)) [:send-data-acs? :update-usable-layouts? :new-di?])
             (set (keys live-copy-frame-core)))))))

;; move-to guard shapes - driving the REAL event-replay/operation-event-sync
;; (the sync-replay path, sync-event-vector-funcs' :operation entry) with
;; event descs shaped exactly like the ones tasks.cljs's position-change-task/
;; popup-task produce, then asserting both what reaches map-api/move-to and
;; what the adapter's valid-move-to? guard decides for that shape. See
;; event_replay.cljs:174-192 for the destructuring being replicated:
;;   :position-change -> (:view-position payload) => {:keys [center zoom]}
;;   :popup           -> (:view-position (:popup-desc payload)) => {:keys [center zoom]}

(defn- captured-move-to [event-desc]
  (let [captured (atom nil)]
    (with-redefs [map-api/move-to (fn [_frame-id zoom center] (reset! captured [zoom center]))]
      (event-replay/operation-event-sync {} frame-id nil event-desc))
    @captured))

(deftest position-change-move-to-guard-shapes
  (testing "nil center + nil zoom"
    (let [args (captured-move-to {:action :position-change
                                  :payload {:view-position {:center nil :zoom nil}}})]
      (is (= [nil nil] args))
      (is (false? (apply inst/valid-move-to? args)))))
  (testing "center-without-zoom"
    (let [args (captured-move-to {:action :position-change
                                  :payload {:view-position {:center [10 20] :zoom nil}}})]
      (is (= [nil [10 20]] args))
      (is (false? (apply inst/valid-move-to? args)))))
  (testing "zoom-without-center"
    (let [args (captured-move-to {:action :position-change
                                  :payload {:view-position {:center nil :zoom 5}}})]
      (is (= [5 nil] args))
      (is (false? (apply inst/valid-move-to? args)))))
  (testing "valid pair"
    (let [args (captured-move-to {:action :position-change
                                  :payload {:view-position {:center [10 20] :zoom 5}}})]
      (is (= [5 [10 20]] args))
      (is (true? (apply inst/valid-move-to? args))))))

(deftest popup-move-to-guard-shapes
  (testing "popup-desc present with a valid nested view-position"
    (let [args (captured-move-to {:action :popup
                                  :payload {:popup-desc {:view-position {:center [1 2] :zoom 3}}}})]
      (is (= [3 [1 2]] args))
      (is (true? (apply inst/valid-move-to? args)))))
  (testing "popup-desc absent - the nested [:popup-desc :view-position]
            extraction is nil-nil, so the guard rejects it same as any other
            incomplete shape"
    (let [args (captured-move-to {:action :popup
                                  :payload {}})]
      (is (= [nil nil] args))
      (is (false? (apply inst/valid-move-to? args))))))
