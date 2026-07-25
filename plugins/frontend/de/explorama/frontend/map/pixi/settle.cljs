(ns de.explorama.frontend.map.pixi.settle)

(defn new-state []
  {:pending 0 :render-requested? false :listeners []})

(defn add-listener [state f]
  (update state :listeners conj f))

(defn- fire [state]
  [(assoc state :listeners [] :render-requested? false)
   (:listeners state)])

(defn note-render [state]
  (let [state (assoc state :render-requested? true)]
    (if (and (zero? (:pending state)) (seq (:listeners state)))
      (fire state)
      [state []])))

(defn note-load-start [state]
  (update state :pending inc))

(defn note-load-end [state]
  (let [state (update state :pending #(max 0 (dec %)))]
    (if (and (zero? (:pending state))
             (:render-requested? state)
             (seq (:listeners state)))
      (fire state)
      [state []])))
