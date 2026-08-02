(ns de.explorama.shared.common.unification.time-test
  (:require [clojure.test :as t :include-macros true]
            [de.explorama.shared.common.unification.time :as sut]))

(t/deftest parse-unparse-roundtrips
  (t/testing "protocol objects at day, month and year precision"
    (t/is (= "2023-06-15" (sut/obj->date-str :day (sut/date-str->obj false :day "2023-06-15"))))
    (t/is (= "2023-06" (sut/obj->date-str :month (sut/date-str->obj false :month "2023-06"))))
    (t/is (= "2023" (sut/obj->date-str :year (sut/date-str->obj false :year "2023")))))
  (t/testing "native date objects roundtrip through from-date"
    (t/is (= "2023-06-15" (sut/obj->date-str :day (sut/date-str->obj :day "2023-06-15"))))))

(t/deftest date-construction
  (t/is (= "2023-06-15" (sut/obj->date-str :day (sut/date-time 2023 6 15))))
  (t/is (= 6 (sut/get-month (sut/date-time 2023 6 15))))
  (t/is (= 2023 (sut/get-year (sut/date-time 2023 6 15)))))

(t/deftest comparisons
  (let [a (sut/date-str->obj false :day "2023-01-01")
        b (sut/date-str->obj false :day "2023-01-02")]
    (t/is (sut/before? a b))
    (t/is (sut/after? b a))
    (t/is (sut/equal? a (sut/date-str->obj false :day "2023-01-01")))
    (t/is (not (sut/before? b a)))))

(t/deftest native-comparisons
  (t/is (sut/before? (sut/date-str->obj :day "2023-01-01")
                     (sut/date-str->obj :day "2023-01-02"))))

(t/deftest days-in-month
  (t/is (= 29 (sut/get-days-in-month 2 2024)))
  (t/is (= 28 (sut/get-days-in-month 2 2023)))
  (t/is (= 31 (sut/get-days-in-month 1 2023))))

(t/deftest long-roundtrip
  (t/is (= 1700000000000 (sut/to-long (sut/from-long 1700000000000)))))

(t/deftest same-day
  (t/is (true? (sut/is-same-day? (sut/date-str->obj :day "2023-06-15")
                                 (sut/date-str->obj :day "2023-06-15"))))
  (t/is (false? (sut/is-same-day? (sut/date-str->obj :day "2023-06-15")
                                  (sut/date-str->obj :day "2023-06-16")))))

(t/deftest earliest-latest-over-collections
  (let [dates (mapv #(sut/date-str->obj false :day %)
                    ["2023-03-01" "2023-01-15" "2023-12-31"])]
    (t/is (= "2023-01-15" (sut/obj->date-str :day (sut/earliest dates))))
    (t/is (= "2023-12-31" (sut/obj->date-str :day (sut/latest dates))))))
