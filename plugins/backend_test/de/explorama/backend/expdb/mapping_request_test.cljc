(ns de.explorama.backend.expdb.mapping-request-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [de.explorama.backend.agent-requests.registry :as registry]
            [de.explorama.backend.agent-requests.store :as store]
            [de.explorama.backend.expdb.temp-import.mapping-request :as sut]))

(use-fixtures :each (fn [f]
                      (registry/reset-types!)
                      (store/reset-store!)
                      (sut/register!)
                      (f)))

(def ^:private valid-mapping
  {:meta-data {:file-format :csv
               :csv {:separator ";" :quote "\""}}
   :mapping {:datasource {:name [:value "Placeholder"]
                          :global-id [:value "source-placeholder"]}
             :items [{:global-id [:id-rand :uuid]
                      :features [{:facts []
                                  :locations []
                                  :contexts [{:name [:field "country"]
                                              :global-id [:id-generate ["country" :text] :name]
                                              :type [:value "country"]}]
                                  :dates [{:value [:date-schema "dd.MM.YYYY" [:field "Datum"]]
                                           :type [:value "occured-at"]}]
                                  :texts []}]}]}})

(deftest input-test
  (testing "the raw head is limited and the meta-data passed through"
    (let [content (str/join "\n" (range 100))
          {:keys [file-name raw-head meta-data]}
          (sut/input "cases.csv" content {:file-format :csv
                                          :csv {:separator ";" :quote "\""}})]
      (is (= "cases.csv" file-name))
      (is (= 20 (count raw-head)))
      (is (= "0" (first raw-head)))
      (is (= :csv (:file-format meta-data))))))

(deftest type-declaration-test
  (testing "the type is declared with an example that satisfies its own schema"
    (let [{:keys [output-example]} (registry/type-declaration sut/request-type)]
      (is (nil? (registry/explain-result sut/request-type output-example))))))

(deftest validation-test
  (testing "a valid mapping is accepted"
    (is (nil? (registry/explain-result sut/request-type valid-mapping))))
  (testing "a mapping missing its :mapping key is rejected"
    (is (some? (registry/explain-result sut/request-type
                                        (dissoc valid-mapping :mapping)))))
  (testing "a mapping with an unknown operation is rejected"
    (is (some? (registry/explain-result
                sut/request-type
                (assoc-in valid-mapping
                          [:mapping :items 0 :features 0 :dates 0 :value]
                          [:not-a-thing "x"]))))))

(deftest fulfilment-test
  (testing "a fulfilled request answers the waiting client"
    (let [answered (atom nil)
          {:keys [id]} (store/create! {:type sut/request-type
                                       :input (sut/input "cases.csv" "a;b" {:file-format :csv})
                                       :user "tester"
                                       :context {:client-callback #(reset! answered %)
                                                 :failed-callback (fn [_] nil)
                                                 :file-name "cases.csv"}})]
      (store/claim! id "agent-1")
      (store/submit! id valid-mapping)
      (is (= valid-mapping @answered))))
  (testing "a failed request notifies the waiting client"
    (let [failed (atom nil)
          {:keys [id]} (store/create! {:type sut/request-type
                                       :input (sut/input "cases.csv" "a;b" {:file-format :csv})
                                       :user "tester"
                                       :context {:client-callback (fn [_] nil)
                                                 :failed-callback #(reset! failed %)
                                                 :file-name "cases.csv"}})]
      (store/claim! id "agent-1")
      (store/fail! id "no idea")
      (is (= {:error "no idea"} @failed)))))
