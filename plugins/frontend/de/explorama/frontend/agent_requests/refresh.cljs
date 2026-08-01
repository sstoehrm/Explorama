(ns de.explorama.frontend.agent-requests.refresh)

(def default-interval-ms 3000)

(defonce state (atom nil))

(defn start!
  ([dispatch-fn fetch-event]
   (start! state dispatch-fn fetch-event js/setInterval default-interval-ms))
  ([state dispatch-fn fetch-event set-interval-fn interval-ms]
   (when-not @state
     (dispatch-fn fetch-event)
     (reset! state (set-interval-fn #(when @state (dispatch-fn fetch-event)) interval-ms)))))

(defn stop!
  ([] (stop! state js/clearInterval))
  ([state clear-interval-fn]
   (when-let [timer-id @state]
     (clear-interval-fn timer-id)
     (reset! state nil))))

(defn running?
  ([] (running? state))
  ([state] (some? @state)))
