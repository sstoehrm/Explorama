(ns de.explorama.backend.projects.agent-ops-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [de.explorama.backend.agent-gateway.dispatcher :as dispatcher]
            [de.explorama.backend.projects.agent-ops :as sut]
            [de.explorama.backend.projects.core :as projects]))

(use-fixtures :each (fn [f] (dispatcher/reset-registry!) (sut/register!) (f) (dispatcher/reset-registry!)))

(deftest rename-test
  (let [updates (atom [])]
    (with-redefs [projects/update-project-detail (fn [id k v] (swap! updates conj [id k v]))
                  projects/list-projects (fn [{:keys [username]}] {:created-projects {"p1" {:title "new"}} :user username})]
      (is (= {:status :ok :result {:created-projects {"p1" {:title "new"}} :user "alice"}}
             (dispatcher/invoke {:op :projects/rename :params {:project-id "p1" :title "new"} :user "alice"})))
      (is (= [["p1" :title "new"]] @updates)))))
