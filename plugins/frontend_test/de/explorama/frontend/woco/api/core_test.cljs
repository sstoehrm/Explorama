(ns de.explorama.frontend.woco.api.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [de.explorama.frontend.common.frontend-interface :as fi]
            [de.explorama.frontend.woco.api.core]))

(deftest interaction-mode-current-db-get-test
  (testing "the FI wrapper returns the interaction mode keyword, not the whole db"
    (is (= :read-only
           (fi/call-api [:interaction-mode :current-db-get?]
                        {:woco {:interaction-mode :read-only}} nil)))
    (is (= :normal
           (fi/call-api [:interaction-mode :current-db-get?] {} nil))))
  (testing "a busy refusal's :interaction-mode is a keyword, never a map"
    (is (keyword? (fi/call-api [:interaction-mode :current-db-get?]
                               {:woco {:interaction-mode :read-only}} nil)))))
