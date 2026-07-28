(ns de.explorama.frontend.agent-requests.views.sidebar-test
  (:require ["react-dom" :as react-dom]
            [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.agent-requests.refresh :as refresh]
            [de.explorama.frontend.agent-requests.views.sidebar :as sut]
            [re-frame.core :as re-frame]
            [reagent.dom.client :as rdc]))

(deftest content-mount-unmount-test
  (testing "rendering the real component wires component-did-mount/-will-unmount to refresh/start!/stop!"
    (is (false? (refresh/running?)) "starts from a clean state")
    (let [node (.createElement js/document "div")
          dispatched (atom [])]
      (.appendChild (.-body js/document) node)
      (try
        (with-redefs [re-frame/dispatch (fn [event] (swap! dispatched conj event))]
          (let [root (rdc/create-root node)]
            (try
              (react-dom/flushSync #(rdc/render root [sut/content {:frame-id "test-frame"}]))
              (is (true? (refresh/running?))
                  "mounting the real component started the refresh loop")
              (is (some #{[:de.explorama.frontend.agent-requests.core/list-requests]} @dispatched)
                  "mounting dispatched the immediate fetch")
              (react-dom/flushSync #(rdc/unmount root))
              (is (false? (refresh/running?))
                  "unmounting the real component stopped the refresh loop")
              (finally
                (refresh/stop!)))))
        (finally
          (.removeChild (.-body js/document) node))))))
