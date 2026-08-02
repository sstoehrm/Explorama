(ns de.explorama.frontend.agent-requests.refresh-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.agent-requests.refresh :as sut]))

(defn- fake-set-interval [callbacks next-id]
  (fn [f _ms]
    (let [id (swap! next-id inc)]
      (swap! callbacks assoc id f)
      id)))

(deftest start-stop-test
  (testing "starting dispatches immediately and schedules a repeating tick"
    (let [state (atom nil)
          dispatched (atom [])
          callbacks (atom {})
          next-id (atom 0)]
      (sut/start! state #(swap! dispatched conj %) [:fetch] (fake-set-interval callbacks next-id) 3000)
      (is (= [[:fetch]] @dispatched))
      (is (some? @state))
      (testing "a manual tick dispatches again while still running"
        ((get @callbacks @state))
        (is (= [[:fetch] [:fetch]] @dispatched)))))

  (testing "stopping clears the interval and blocks any pending tick from dispatching"
    (let [state (atom nil)
          dispatched (atom [])
          callbacks (atom {})
          next-id (atom 0)
          cleared (atom [])]
      (sut/start! state #(swap! dispatched conj %) [:fetch] (fake-set-interval callbacks next-id) 3000)
      (let [timer-id @state
            tick (get @callbacks timer-id)]
        (sut/stop! state #(swap! cleared conj %))
        (is (= [timer-id] @cleared))
        (is (nil? @state))
        (tick)
        (is (= [[:fetch]] @dispatched)
            "no further dispatch happens once the sidebar has torn down")))))

(deftest reentrant-start-test
  (testing "starting twice while a chain is already running does not start a second one"
    (let [state (atom nil)
          dispatched (atom [])
          callbacks (atom {})
          next-id (atom 0)
          set-interval-fn (fake-set-interval callbacks next-id)]
      (sut/start! state #(swap! dispatched conj %) [:fetch] set-interval-fn 3000)
      (sut/start! state #(swap! dispatched conj %) [:fetch] set-interval-fn 3000)
      (is (= 1 (count @callbacks)) "only one interval was ever scheduled")
      (is (= [[:fetch]] @dispatched) "the second open did not trigger a second immediate fetch"))))

(deftest running-test
  (testing "running? reflects whether a timer handle is currently held"
    (let [state (atom nil)]
      (is (false? (sut/running? state)))
      (sut/start! state (constantly nil) [:fetch] (fake-set-interval (atom {}) (atom 0)) 3000)
      (is (true? (sut/running? state)))
      (sut/stop! state (constantly nil))
      (is (false? (sut/running? state))))))
