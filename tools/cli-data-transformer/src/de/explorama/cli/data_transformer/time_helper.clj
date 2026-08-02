(ns de.explorama.cli.data-transformer.time-helper
  (:import [java.time LocalDateTime ZoneOffset]
           [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
           [java.time.temporal ChronoField]
           [java.util Locale]))

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

(defn parse [fmt s]
  (LocalDateTime/parse s ^DateTimeFormatter fmt))

(defn to-long [dt]
  (-> ^LocalDateTime dt
      (.toInstant ZoneOffset/UTC)
      (.toEpochMilli)))
