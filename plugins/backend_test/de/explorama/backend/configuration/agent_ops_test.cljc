(ns de.explorama.backend.configuration.agent-ops-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.configuration.agent-ops :as sut]
            [de.explorama.backend.configuration.persistence.configs.api :as configs-api]))

(use-fixtures :each (fn [f] (dispatcher/reset-registry!) (sut/register!) (f) (dispatcher/reset-registry!)))

(deftest set-entry-test
  (let [seen (atom nil)]
    (with-redefs [configs-api/update-entry (fn [{:keys [client-callback]} params]
                                             (reset! seen params)
                                             (client-callback {:lang "de"}))]
      (is (= {:status :ok :result {:lang "de"}}
             (dispatcher/invoke {:op :configuration/set-entry
                                 :params {:config-type :i18n :config-id "lang" :entry {:lang "de"}}
                                 :user "alice"})))
      (is (= [{:username "alice"} :i18n "lang" {:lang "de"}] @seen)))))

(deftest set-entry-invalid-test
  (with-redefs [configs-api/update-entry (fn [{:keys [failed-callback]} _] (failed-callback :entry-invalid))]
    (is (= :op-failed
           (get-in (dispatcher/invoke {:op :configuration/set-entry
                                       :params {:config-type :i18n :config-id "lang" :entry {}}
                                       :user "alice"})
                   [:error :type])))))
