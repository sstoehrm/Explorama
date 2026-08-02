(ns de.explorama.shared.common.unification.time
  (:require #?@(:cljs [[cljs-time.core :as t]
                       [cljs-time.format :as f]
                       [cljs-time.coerce :as ctco]])
            #?(:clj [taoensso.timbre :refer [error]]
               :cljs [taoensso.timbre :refer-macros [error]])
            [clojure.string :as st])
  #?(:clj (:import [java.time Instant LocalDateTime YearMonth ZoneOffset]
                   [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
                   [java.time.temporal ChronoField Temporal]
                   [java.util Date Locale])))

(def date-format "yyyy-MM-dd")
(def year-month-format "yyyy-MM")
(def year-format "yyyy")

(def date-format-placeholder (st/lower-case date-format))

#?(:clj
   (defn formatter [fmt-str]
     (-> (DateTimeFormatterBuilder.)
         (.parseCaseInsensitive)
         (.appendPattern fmt-str)
         (.parseDefaulting ChronoField/MONTH_OF_YEAR 1)
         (.parseDefaulting ChronoField/DAY_OF_MONTH 1)
         (.parseDefaulting ChronoField/HOUR_OF_DAY 0)
         (.parseDefaulting ChronoField/MINUTE_OF_HOUR 0)
         (.parseDefaulting ChronoField/SECOND_OF_MINUTE 0)
         (.toFormatter Locale/ENGLISH)))
   :cljs (def formatter f/formatter))

#?(:clj (defn unparse [fmt obj]
          (.format ^DateTimeFormatter fmt obj))
   :cljs (def unparse f/unparse))

#?(:clj (defn parse [fmt s]
          (LocalDateTime/parse s ^DateTimeFormatter fmt))
   :cljs (def parse f/parse))

#?(:clj (def formatters
          {:date-hour-minute-second (formatter "yyyy-MM-dd'T'HH:mm:ss")
           :year-month-day (formatter "yyyy-MM-dd")
           :basic-date-time-no-ms (formatter "yyyyMMdd'T'HHmmss'Z'")})
   :cljs (def formatters f/formatters))

(def day-formatter (formatter date-format))
(def year-month-formatter (formatter year-month-format))
(def year-formatter (formatter year-format))

(defn choose-formatter [precision]
  (case precision
    (:day "day") day-formatter
    (:month "month") year-month-formatter
    (:year "year") year-formatter
    :else nil))

#?(:clj (defn now [] (LocalDateTime/now ZoneOffset/UTC))
   :cljs (def now t/now))

#?(:clj (defn date-time
          ([y] (LocalDateTime/of (int y) 1 1 0 0 0))
          ([y m] (LocalDateTime/of (int y) (int m) 1 0 0 0))
          ([y m d] (LocalDateTime/of (int y) (int m) (int d) 0 0 0))
          ([y m d h] (LocalDateTime/of (int y) (int m) (int d) (int h) 0 0))
          ([y m d h mi] (LocalDateTime/of (int y) (int m) (int d) (int h) (int mi) 0))
          ([y m d h mi s] (LocalDateTime/of (int y) (int m) (int d) (int h) (int mi) (int s))))
   :cljs (def date-time t/date-time))

#?(:clj (defn month [obj] (.getMonthValue ^LocalDateTime obj))
   :cljs (def month t/month))

#?(:clj (defn year [obj] (.getYear ^LocalDateTime obj))
   :cljs (def year t/year))

#?(:clj (defn to-date [obj]
          (Date/from (.toInstant ^LocalDateTime obj ZoneOffset/UTC)))
   :cljs (def to-date ctco/to-date))

#?(:clj (defn from-date [^Date d]
          (LocalDateTime/ofInstant (.toInstant d) ZoneOffset/UTC))
   :cljs (def from-date ctco/from-date))

#?(:clj (defn from-long [l]
          (LocalDateTime/ofInstant (Instant/ofEpochMilli (long l)) ZoneOffset/UTC))
   :cljs (def from-long ctco/from-long))

#?(:clj (defn number-of-days-in-the-month [obj]
          (.lengthOfMonth (YearMonth/from ^LocalDateTime obj)))
   :cljs (def number-of-days-in-the-month t/number-of-days-in-the-month))

(defn date-protocol? [obj]
  #?(:clj (instance? Temporal obj)
     :cljs (satisfies? t/DateTimeProtocol obj)))

(defn- convert-and-apply
  "Checks if obj is from date-protocol type which is needed to apply functions from clj/cljs-time"
  ([f obj]
   (cond-> obj
     (not (date-protocol? obj))
     (from-date)
     (fn? f)
     (f)))

  ([f obj1 obj2]
   (when (fn? f)
     (f (convert-and-apply nil obj1)
        (convert-and-apply nil obj2)))))

#?(:clj (defn- before?* [a b] (.isBefore ^LocalDateTime a b)))
#?(:clj (defn- after?* [a b] (.isAfter ^LocalDateTime a b)))
#?(:clj (defn- equal?* [a b] (.isEqual ^LocalDateTime a b)))
#?(:clj (defn- within?*
          ([start end x]
           (and (not (.isBefore ^LocalDateTime x start))
                (.isBefore ^LocalDateTime x end)))))

(def before? (partial convert-and-apply #?(:clj before?* :cljs t/before?)))
(def after? (partial convert-and-apply #?(:clj after?* :cljs t/after?)))
(def equal? (partial convert-and-apply #?(:clj equal?* :cljs t/equal?)))
(def within? (partial convert-and-apply #?(:clj within?* :cljs t/within?)))

#?(:clj (defn earliest
          ([dts] (reduce (fn [a b] (if (before?* b a) b a)) dts))
          ([dt1 dt2] (if (before?* dt2 dt1) dt2 dt1)))
   :cljs (def earliest t/earliest))

#?(:clj (defn latest
          ([dts] (reduce (fn [a b] (if (after?* b a) b a)) dts))
          ([dt1 dt2] (if (after?* dt2 dt1) dt2 dt1)))
   :cljs (def latest t/latest))

#?(:clj (defn to-long [obj]
          (.toEpochMilli (.toInstant ^LocalDateTime obj ZoneOffset/UTC)))
   :cljs (def to-long ctco/to-long))

(def current-ms #(to-long (now)))

(defn obj->date-str
  ([obj]
   (obj->date-str :day obj))
  ([precision obj]
   (when obj
     (try
       (let [obj (cond-> obj
                   (not (date-protocol? obj))
                   (from-date))
             formatter (choose-formatter precision)]
         (unparse formatter obj))
       (catch #?(:clj Throwable :cljs :default) e
         (error "Date-Obj is not valid" (str (type obj)) obj precision e))))))

(defn date-str->obj
  "The Precision defines how accurate the date-string is, eg. year, month, day.
   Format of the date-string is the IS0-8601 definition. Example: 2018-01-31
   Returns a Date/Moment-object."
  ([date-string]
   (date-str->obj :day date-string))
  ([precision date-string]
   (date-str->obj true precision date-string))
  ([native? precision date-string]
   (when-not (st/blank? date-string)
     (try
       (let [formatter (choose-formatter precision)]
         (cond-> (parse formatter date-string)
           native?
           (to-date)))
       (catch #?(:clj Throwable :cljs :default) e
         (error e "Date-str is not valid" date-string precision native?))))))

(defn filter-date-ranges [start-date end-date possible-dates equal?]
  (let [start-date (date-str->obj false :day start-date)
        end-date (date-str->obj false :day end-date)
        check-fn (fn [d]
                   (= equal? (#?(:clj within?* :cljs t/within?) start-date end-date (date-str->obj false :day d))))]
    (filter (fn [d]
              (= equal? (check-fn d)))
            possible-dates)))

(defn filter-months [month-val year-months]
  (reduce (fn [res ym-str]
            (if (= month-val (-> (date-str->obj false :month ym-str)
                                 (month)))
              (conj res ym-str)
              res))
          #{}
          year-months))

(defn is-same-day? [date1 date2]
  (try
    (= (obj->date-str date1)
       (obj->date-str date2)) ;!TODO There are corner cases where it might not work
    (catch #?(:clj Throwable :cljs :default) e
      (error "Dates are not comparable" date1 date2 e))))

(defn get-month [date-obj]
  (month date-obj))

(defn get-year [date-obj]
  (year date-obj))

(defn get-month-year [date-obj]
  (str (get-year date-obj)
       (get-month date-obj)))

;Get the number of days in month - Currently only needed in client for performance optimizing
(defn get-days-in-month
  ([month year]
   (number-of-days-in-the-month (date-time year month)))
  ([d]
   (number-of-days-in-the-month d)))
