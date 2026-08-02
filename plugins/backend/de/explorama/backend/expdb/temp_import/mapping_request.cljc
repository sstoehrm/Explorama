(ns de.explorama.backend.expdb.temp-import.mapping-request
  (:require [clojure.string :as str]
            [de.explorama.backend.agent-requests.config :as config]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.shared.data-transformer.schema :as schema]))

(def request-type :data-transformer/mapping)

(def ^:private description
  (str "Produce a data-transformer mapping descriptor for the given csv file. "
       "The input holds the first lines of the raw file and the csv meta-data. "
       "Answer with a single edn map that satisfies the output schema: the "
       ":meta-data of the input, plus a :mapping describing the datasource and "
       "one item with its facts, locations, contexts, dates and texts."))

(def ^:private example
  {:meta-data {:file-format :csv
               :csv {:separator ";" :quote "\""}}
   :mapping {:datasource {:name [:value "Cases"]
                          :global-id [:value "source-cases"]}
             :items [{:global-id [:field "id"]
                      :features [{:facts [{:value [:field "cases"]
                                           :name [:value "cases"]
                                           :type [:value "integer"]}]
                                  :locations [{:point [:lat-lon [:field "lat"] [:field "lon"]]}]
                                  :contexts [{:name [:field "country"]
                                              :global-id [:id-generate ["country" :text] :name]
                                              :type [:value "country"]}]
                                  :dates [{:value [:date-schema "dd.MM.yyyy" [:field "date"]]
                                           :type [:value "occured-at"]}]
                                  :texts [[:field "notes" ""]]}]}]}})

(defn input [file-name raw-content meta-data]
  {:file-name file-name
   :raw-head (vec (take config/raw-head-lines (str/split-lines raw-content)))
   :meta-data meta-data})

(defn- on-fulfilled [{{:keys [client-callback]} :context} result]
  (when client-callback
    (client-callback result)))

(defn register! []
  (registry/register-type! {:id request-type
                            :description description
                            :output-schema schema/import-schema
                            :output-example example
                            :on-fulfilled on-fulfilled}))
