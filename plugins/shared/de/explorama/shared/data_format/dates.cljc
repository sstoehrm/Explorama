(ns de.explorama.shared.data-format.dates
  (:require #?@(:cljs [[cljs-time.core :as t]
                       [cljs-time.format :as f]])
            [clojure.string :as s]
            [de.explorama.shared.data-format.filter-functions :as ff])
  #?(:clj (:import [java.time LocalDate LocalDateTime ZoneOffset]
                   [java.time.format DateTimeFormatter]
                   [java.time.temporal TemporalAdjusters WeekFields])))

(def date-keys [::type ::full-date
                ::week
                ::weekday
                ::year ::month ::day
                ::hours ::minutes ::seconds])

(defn- to-int [s]
  (try
    (cond
      (string? s)
      #?(:clj (Integer/parseInt s)
         :cljs (js/parseInt s))
      (number? s) (int s))
    (catch #? (:clj Exception
               :cljs js/Error)
           e nil)))

(defn safe-subs [^String s start & [end]]
  (when (and (string? s)
             (>= (count s) start)
             (or (nil? end)
                 (and (>= (count s) end)
                      (< start end))))
    (if end
      (subs s start end)
      (subs s start))))

#?(:clj (def ^:private iso-week-field (.weekOfWeekBasedYear WeekFields/ISO)))
#?(:clj (def ^:private dhms-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

(defn- date-time* [ordinals]
  #?(:clj (let [[y m d h mi sec] ordinals]
            (LocalDateTime/of (int y) (int (or m 1)) (int (or d 1))
                              (int (or h 0)) (int (or mi 0)) (int (or sec 0))))
     :cljs (apply t/date-time ordinals)))

(defn- today-at-midnight* []
  #?(:clj (.atStartOfDay (LocalDate/now ZoneOffset/UTC))
     :cljs (t/today-at-midnight)))

(defn- year* [dt] #?(:clj (.getYear ^LocalDateTime dt) :cljs (t/year dt)))
(defn- month* [dt] #?(:clj (.getMonthValue ^LocalDateTime dt) :cljs (t/month dt)))
(defn- day* [dt] #?(:clj (.getDayOfMonth ^LocalDateTime dt) :cljs (t/day dt)))
(defn- hour* [dt] #?(:clj (.getHour ^LocalDateTime dt) :cljs (t/hour dt)))
(defn- minute* [dt] #?(:clj (.getMinute ^LocalDateTime dt) :cljs (t/minute dt)))
(defn- second* [dt] #?(:clj (.getSecond ^LocalDateTime dt) :cljs (t/second dt)))
(defn- week-number* [dt] #?(:clj (.get ^LocalDateTime dt iso-week-field) :cljs (t/week-number-of-year dt)))
(defn- day-of-week* [dt] #?(:clj (.getValue (.getDayOfWeek ^LocalDateTime dt)) :cljs (t/day-of-week dt)))
(defn- equal?* [a b] #?(:clj (.isEqual ^LocalDateTime a b) :cljs (t/equal? a b)))
(defn- before?* [a b] #?(:clj (.isBefore ^LocalDateTime a b) :cljs (t/before? a b)))
(defn- after?* [a b] #?(:clj (.isAfter ^LocalDateTime a b) :cljs (t/after? a b)))
(defn- within?* [start end x]
  #?(:clj (and (not (.isBefore ^LocalDateTime x start))
               (.isBefore ^LocalDateTime x end))
     :cljs (t/within? start end x)))
(defn- minus-days* [dt n] #?(:clj (.minusDays ^LocalDateTime dt (long n)) :cljs (t/minus dt (t/days n))))
(defn- minus-months* [dt n] #?(:clj (.minusMonths ^LocalDateTime dt (long n)) :cljs (t/minus dt (t/months n))))
(defn- minus-years* [dt n] #?(:clj (.minusYears ^LocalDateTime dt (long n)) :cljs (t/minus dt (t/years n))))
(defn- first-day-of-the-month* [dt]
  #?(:clj (.with ^LocalDateTime dt (TemporalAdjusters/firstDayOfMonth)) :cljs (t/first-day-of-the-month dt)))
(defn- last-day-of-the-month* [dt]
  #?(:clj (.with ^LocalDateTime dt (TemporalAdjusters/lastDayOfMonth)) :cljs (t/last-day-of-the-month dt)))
(defn- unparse-dhms* [dt]
  #?(:clj (.format ^LocalDateTime dt dhms-formatter)
     :cljs (f/unparse (f/formatters :date-hour-minute-second) dt)))

(defn- year-month-day->date [year month day]
  (let [int-year (to-int year)
        int-month (to-int month)
        int-day (to-int day)]
    (date-time* [int-year int-month int-day])))

(defn transform-week
  ([^String date]
   (let [year (safe-subs date 0 4)
         month (safe-subs date 5 7)
         day (safe-subs date 8 10)]
     (transform-week year month day)))
  ([year month day]
   (when (and year month day)
     (-> (year-month-day->date year month day)
         week-number*
         str))))

(defn transform-weekday
  ([^String date]
   (let [year (safe-subs date 0 4)
         month (safe-subs date 5 7)
         day (safe-subs date 8 10)]
     (transform-weekday year month day)))
  ([year month day]
   (when (and year month day)
     (-> (year-month-day->date year month day)
         day-of-week*
         str))))

(defn parse
  "Parses a string of the format yyyy-MM-ddThh:mm:ss with each of the element being optional"
  [s]
  (let [dt (if (= s "today")
             (today-at-midnight*)
             (let [year (safe-subs s 0 4)
                   month (safe-subs s 5 7)
                   day (safe-subs s 8 10)
                   hour (safe-subs s 11 13)
                   minute (safe-subs s 14 16)
                   sec (safe-subs s 17)
                   ordinals (take-while some? [year month day hour minute sec])]
               (date-time* (map to-int ordinals))))]
    {::type ::date
     ::full-date {::val dt}
     ::year (year* dt)
     ::month (month* dt)
     ::week (week-number* dt)
     ::weekday (day-of-week* dt)
     ::day (day* dt)
     ::hours (hour* dt)
     ::minutes (minute* dt)
     ::seconds (second* dt)}))

(defn unparse
  "unparses a date object into :date-hour-minute-second format"
  [d]
  (unparse-dhms* (::full-date d)))

;; These are only used for full-date comparison
(defn equal? [instance d1 d2 & _]
  (equal?* (ff/get instance d1 ::val)
           (-> d2
               ::full-date
               ::val)))

(defn before? [instance d1 d2 & _]
  (before?* (ff/get instance d1 ::val)
            (-> d2
                ::full-date
                ::val)))

(defn after? [instance d1 d2 & _]
  (after?* (ff/get instance d1 ::val)
           (-> d2
               ::full-date
               ::val)))

(defn year-equal? [instance d1 d2 & _]
  (= (year* (ff/get instance d1 ::val))
     (year* (-> d2
                ::full-date
                ::val))))

(defn month-equal? [instance d1 d2 & _]
  (and (year-equal? instance d1 d2)
       (= (month* (ff/get instance d1 ::val))
          (month* (-> d2
                      ::full-date
                      ::val)))))

(defn week-equal? [instance d1 d2 & _]
  (= (week-number* (ff/get instance d1 ::val))
     (week-number* (-> d2
                       ::full-date
                       ::val))))

(defn weekday-equal? [instance d1 d2 & _]
  (= (day-of-week* (ff/get instance d1 ::val))
     (day-of-week* (-> d2
                       ::full-date
                       ::val))))

(defn day-equal? [instance d1 d2 & _]
  (and (month-equal? instance d1 d2)
       (= (day* (ff/get instance d1 ::val))
          (day* (-> d2
                    ::full-date
                    ::val)))))

(defn last-x-days [instance d1 d2 extra]
  (let [end (-> d2
                ::full-date
                ::val)
        start (minus-days* end extra)
        data-val (ff/get instance d1 ::val)]
    (within?* start end data-val)))

(defn last-x-weeks [instance d1 d2 extra]
  (let [end (-> d2
                ::full-date
                ::val)
        start (minus-days* end extra)
        data-val (ff/get instance d1 ::val)]
    (within?* start end data-val)))

(defn last-x-months [instance d1 d2 extra]
  (let [end (-> d2
                ::full-date
                ::val
                last-day-of-the-month*)
        start (-> end
                  (minus-months* extra)
                  first-day-of-the-month*)
        data-val (ff/get instance d1 ::val)]
    (within?* start end data-val)))

(defn last-x-years [instance d1 d2 extra]
  (let [end (-> d2
                ::full-date
                ::val)
        start (minus-years* end extra)
        data-val (year* (ff/get instance d1 ::val))
        end-y (year* end)
        start-y (year* start)]
    (<= start-y data-val end-y)))
