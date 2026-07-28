(ns de.explorama.backend.agent-requests.queue)

(def ^:private gone-states #{:fulfilled :failed :expired :cancelled})

(defn create [state now {:keys [ttl-ms] :as request}]
  (let [request (assoc request
                       :status :open
                       :created-at now
                       :expires-at (+ now ttl-ms)
                       :rejections 0
                       :claimed-by nil
                       :lease-expires-at nil
                       :result nil)]
    [(assoc state (:id request) request) request]))

(defn- sweep-request [{:keys [status expires-at lease-expires-at] :as request} now]
  (cond
    (gone-states status) request
    (<= expires-at now) (assoc request :status :expired)
    (and (= :claimed status) (<= lease-expires-at now))
    (assoc request :status :open :claimed-by nil :lease-expires-at nil)
    :else request))

(defn sweep [state now]
  (reduce-kv (fn [acc id request]
               (assoc acc id (sweep-request request now)))
             {}
             state))

(defn open-requests [state now type-filter]
  (->> (vals (sweep state now))
       (filter #(= :open (:status %)))
       (filter #(or (nil? type-filter) (= type-filter (:type %))))
       (sort-by :created-at)
       vec))

(defn user-requests [state now user]
  (->> (vals (sweep state now))
       (filter #(= user (:user %)))
       (sort-by :created-at)
       reverse
       vec))

(defn- with-request [state now id f]
  (let [state (sweep state now)
        {:keys [status] :as request} (get state id)]
    (cond
      (nil? request) [state {:error :not-found}]
      (gone-states status) [state {:error :gone}]
      :else (f state request))))

(defn claim [state now id agent-id]
  (with-request state now id
    (fn [state {:keys [status lease-ms] :as request}]
      (if (= :claimed status)
        [state {:error :conflict}]
        (let [request (assoc request
                             :status :claimed
                             :claimed-by agent-id
                             :lease-expires-at (+ now lease-ms))]
          [(assoc state id request) {:ok request}])))))

(defn submit [state now id result explain-fn]
  (with-request state now id
    (fn [state {:keys [status rejections max-rejections] :as request}]
      (if (not= :claimed status)
        [state {:error :not-claimed}]
        (if-let [explanation (explain-fn result)]
          (let [rejections (inc rejections)
                request (cond-> (assoc request :rejections rejections)
                          (<= max-rejections rejections)
                          (assoc :status :failed :reason explanation))]
            [(assoc state id request) {:error :invalid :explanation explanation}])
          (let [request (assoc request :status :fulfilled :result result)]
            [(assoc state id request) {:ok request}]))))))

(defn fail [state now id reason]
  (with-request state now id
    (fn [state request]
      (let [request (assoc request :status :failed :reason reason)]
        [(assoc state id request) {:ok request}]))))

(defn cancel [state now id]
  (with-request state now id
    (fn [state request]
      (let [request (assoc request :status :cancelled)]
        [(assoc state id request) {:ok request}]))))
