(ns de.explorama.shared.agent-requests.age)

(def ^:private second-ms 1000)
(def ^:private minute-ms (* 60 second-ms))
(def ^:private hour-ms (* 60 minute-ms))
(def ^:private day-ms (* 24 hour-ms))

(defn age-label
  "Compact, language-neutral age of a request, e.g. \"12s\", \"5m\", \"2h\"."
  [created-at now]
  (when (and (number? created-at) (number? now))
    (let [elapsed (max 0 (- now created-at))]
      (cond
        (< elapsed minute-ms) (str (quot elapsed second-ms) "s")
        (< elapsed hour-ms) (str (quot elapsed minute-ms) "m")
        (< elapsed day-ms) (str (quot elapsed hour-ms) "h")
        :else (str (quot elapsed day-ms) "d")))))
