(ns metabase.driver.h2
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.core :as hsql]
            [java-time :as t]
            [metabase.db.jdbc-protocols :as mdb.jdbc-protocols]
            [metabase.db.spec :as mdb.spec]
            [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            [metabase.driver.h2.actions :as h2.actions]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.plugins.classloader :as classloader]
            [metabase.query-processor.error-type :as qp.error-type]
            [metabase.query-processor.store :as qp.store]
            [metabase.util :as u]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.util.i18n :refer [deferred-tru tru]]
            [metabase.util.ssh :as ssh])
  (:import [java.sql Clob ResultSet ResultSetMetaData]
           java.time.OffsetTime
           org.h2.command.Parser
           org.h2.engine.Session
           org.h2.engine.SessionRemote))

;; method impls live in this namespace
(comment h2.actions/keep-me)

(driver/register! :h2, :parent :sql-jdbc)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             metabase.driver impls                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(doseq [[feature supported?] {:full-join               false
                              :regex                   false
                              :percentile-aggregations false
                              :actions                 true
                              :actions/custom          true}]
  (defmethod driver/database-supports? [:h2 feature]
    [_driver _feature _database]
    supported?))

(defmethod driver/connection-properties :h2
  [_]
  (->>
   [{:name         "db"
     :display-name (tru "Connection String")
     :helper-text (deferred-tru "The local path relative to where Metabase is running from. Your string should not include the .mv.db extension.")
     :placeholder  (str "file:/" (deferred-tru "Users/camsaul/bird_sightings/toucans"))
     :required     true}
    driver.common/cloud-ip-address-info
    driver.common/advanced-options-start
    driver.common/default-advanced-options]
   (map u/one-or-many)
   (apply concat)))

(defmethod driver/db-start-of-week :h2
  [_]
  :monday)

;; TODO - it would be better not to put all the options in the connection string in the first place?
(defn- connection-string->file+options
  "Explode a `connection-string` like `file:my-db;OPTION=100;OPTION_2=TRUE` to a pair of file and an options map.

    (connection-string->file+options \"file:my-crazy-db;OPTION=100;OPTION_X=TRUE\")
      -> [\"file:my-crazy-db\" {\"OPTION\" \"100\", \"OPTION_X\" \"TRUE\"}]"
  [^String connection-string]
  {:pre [(string? connection-string)]}
  (let [[file & options] (str/split connection-string #";+")
        options          (into {} (for [option options]
                                    (str/split option #"=")))]
    [file options]))

(defn- db-details->user [{:keys [db], :as details}]
  {:pre [(string? db)]}
  (or (some (partial get details) ["USER" :USER])
      (let [[_ {:strs [USER]}] (connection-string->file+options db)]
        USER)))

(defn- check-native-query-not-using-default-user [{query-type :type, :as query}]
  (u/prog1 query
    ;; For :native queries check to make sure the DB in question has a (non-default) NAME property specified in the
    ;; connection string. We don't allow SQL execution on H2 databases for the default admin account for security
    ;; reasons
    (when (= (keyword query-type) :native)
      (let [{:keys [details]} (qp.store/database)
            user              (db-details->user details)]
        (when (or (str/blank? user)
                  (= user "sa"))        ; "sa" is the default USER
          (throw
           (ex-info (tru "Running SQL queries against H2 databases using the default (admin) database user is forbidden.")
             {:type qp.error-type/db})))))))

(defn- make-h2-parser [h2-db-id]
  (with-open [conn (.getConnection (sql-jdbc.execute/datasource-with-diagnostic-info! :h2 h2-db-id))]
    (let [inner-field (doto
                          (.getDeclaredField (class conn) "inner")
                        (.setAccessible true))
          h2-jdbc-conn (.get inner-field conn)
          session-field (doto
                            (.getDeclaredField (class h2-jdbc-conn) "session")
                          (.setAccessible true))
          session (.get session-field h2-jdbc-conn)]
      (cond
        (instance? Session session)
        (Parser. session)

        ;; a SessionRemote cannot be used to make a parser
        (instance? SessionRemote session)
        ::client-side-session

        :else
        (throw (ex-info "Unknown session type" {:session session}))))))

(defn- parse
  ([h2-db-id s]
   (let [h2-parser (make-h2-parser h2-db-id)]
     (when-not (= ::client-side-session h2-parser)
       (let [parse-method (doto (.getDeclaredMethod (class h2-parser)
                                                    "parse"
                                                    (into-array Class [java.lang.String]))
                            (.setAccessible true))
             parse-index-field (doto (.getDeclaredField (class h2-parser) "parseIndex")
                                 (.setAccessible true))]
         ;; parser moves parseIndex, so get-offset will be the index in the string that was parsed "up to"
         (parse s
                (fn parser [s]
                  (try (.invoke parse-method h2-parser (object-array [s]))
                       ;; need to chew through error scenarios because of a query like:
                       ;;
                       ;; vulnerability; abc;
                       ;;
                       ;; which would cause this parser to break w/o the error handling here, but this way we
                       ;; still return the org.h2.command.ddl.* classes.
                       (catch Throwable _ ::parse-fail)))
                (fn get-offset [] (.get parse-index-field h2-parser)))))))
  ([s parser get-offset] (vec (concat
                               [(parser s)];; this call to parser parses up to the end of the first sql statement
                               (let [more (apply str (drop (get-offset) s))] ;; more is the unparsed part of s
                                 (when-not (str/blank? more)
                                   (parse more parser get-offset)))))))

(defn- check-disallow-ddl-commands [{:keys [database] :as query}]
  (when query
    (let [operations (parse database (-> query :native :query))
          op-classes (map class operations)]
      (when (some #(re-find #"org.h2.command.ddl." (str %)) op-classes)
        (throw (ex-info "DDL commands are not allowed to be used with h2." {:classes op-classes}))))))

(defmethod driver/execute-reducible-query :h2
  [driver query chans respond]
  (check-native-query-not-using-default-user query)
  (check-disallow-ddl-commands query)
  ((get-method driver/execute-reducible-query :sql-jdbc) driver query chans respond))

(defmethod sql.qp/add-interval-honeysql-form :h2
  [driver hsql-form amount unit]
  (cond
    (= unit :quarter)
    (recur driver hsql-form (hx/* amount 3) :month)

    ;; H2 only supports long ints in the `dateadd` amount field; since we want to support fractional seconds (at least
    ;; for application DB purposes) convert to `:millisecond`
    (and (= unit :second)
         (not (zero? (rem amount 1))))
    (recur driver hsql-form (* amount 1000.0) :millisecond)

    :else
    (hsql/call :dateadd (hx/literal unit) (hx/cast :long amount) hsql-form)))

(defmethod driver/humanize-connection-error-message :h2
  [_ message]
  (condp re-matches message
    #"^A file path that is implicitly relative to the current working directory is not allowed in the database URL .*$"
    :implicitly-relative-db-file-path

    #"^Database .* not found .*$"
    :db-file-not-found

    #"^Wrong user name or password .*$"
    :username-or-password-incorrect

    message))

(def ^:private date-format-str "yyyy-MM-dd HH:mm:ss.SSS zzz")

(defmethod driver.common/current-db-time-date-formatters :h2
  [_]
  (driver.common/create-db-time-formatters date-format-str))

(defmethod driver.common/current-db-time-native-query :h2
  [_]
  (format "select formatdatetime(current_timestamp(),'%s') AS VARCHAR" date-format-str))

(defmethod driver/current-db-time :h2
  [& args]
  (apply driver.common/current-db-time args))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           metabase.driver.sql impls                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- add-to-1970 [expr unit-str]
  (hsql/call :timestampadd
    (hx/literal unit-str)
    expr
    (hsql/raw "timestamp '1970-01-01T00:00:00Z'")))

(defmethod sql.qp/unix-timestamp->honeysql [:h2 :seconds] [_ _ expr]
  (add-to-1970 expr "second"))

(defmethod sql.qp/unix-timestamp->honeysql [:h2 :milliseconds] [_ _ expr]
  (add-to-1970 expr "millisecond"))

(defmethod sql.qp/unix-timestamp->honeysql [:h2 :microseconds] [_ _ expr]
  (add-to-1970 expr "microsecond"))

(defmethod sql.qp/cast-temporal-string [:h2 :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_driver _coercion-strategy expr]
  (hsql/call :parsedatetime expr (hx/literal "yyyyMMddHHmmss")))

(defmethod sql.qp/cast-temporal-byte [:h2 :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/YYYYMMDDHHMMSSString->Temporal
                               (hsql/call :utf8tostring expr)))

;; H2 doesn't have date_trunc() we fake it by formatting a date to an appropriate string
;; and then converting back to a date.
;; Format strings are the same as those of SimpleDateFormat.
(defn- format-datetime   [format-str expr] (hsql/call :formatdatetime expr (hx/literal format-str)))
(defn- parse-datetime    [format-str expr] (hsql/call :parsedatetime expr  (hx/literal format-str)))
(defn- trunc-with-format [format-str expr] (parse-datetime format-str (format-datetime format-str expr)))

(defmethod sql.qp/date [:h2 :minute]          [_ _ expr] (trunc-with-format "yyyyMMddHHmm" expr))
(defmethod sql.qp/date [:h2 :minute-of-hour]  [_ _ expr] (hx/minute expr))
(defmethod sql.qp/date [:h2 :hour]            [_ _ expr] (trunc-with-format "yyyyMMddHH" expr))
(defmethod sql.qp/date [:h2 :hour-of-day]     [_ _ expr] (hx/hour expr))
(defmethod sql.qp/date [:h2 :day]             [_ _ expr] (hx/->date expr))
(defmethod sql.qp/date [:h2 :day-of-month]    [_ _ expr] (hsql/call :day_of_month expr))
(defmethod sql.qp/date [:h2 :day-of-year]     [_ _ expr] (hsql/call :day_of_year expr))
(defmethod sql.qp/date [:h2 :month]           [_ _ expr] (trunc-with-format "yyyyMM" expr))
(defmethod sql.qp/date [:h2 :month-of-year]   [_ _ expr] (hx/month expr))
(defmethod sql.qp/date [:h2 :quarter-of-year] [_ _ expr] (hx/quarter expr))
(defmethod sql.qp/date [:h2 :year]            [_ _ expr] (parse-datetime "yyyy" (hx/year expr)))

(defmethod sql.qp/date [:h2 :day-of-week]
  [_ _ expr]
  (sql.qp/adjust-day-of-week :h2 (hsql/call :iso_day_of_week expr)))

(defmethod sql.qp/date [:h2 :week]
  [_ _ expr]
  (sql.qp/add-interval-honeysql-form :h2 (sql.qp/date :h2 :day expr)
                                     (hx/- 1 (sql.qp/date :h2 :day-of-week expr))
                                     :day))

;; Rounding dates to quarters is a bit involved but still doable. Here's the plan:
;; *  extract the year and quarter from the date;
;; *  convert the quarter (1 - 4) to the corresponding starting month (1, 4, 7, or 10).
;;    (do this by multiplying by 3, giving us [3 6 9 12]. Then subtract 2 to get [1 4 7 10]);
;; *  concatenate the year and quarter start month together to create a yyyymm date string;
;; *  parse the string as a date. :sunglasses:
;;
;; Postgres DATE_TRUNC('quarter', x)
;; becomes  PARSEDATETIME(CONCAT(YEAR(x), ((QUARTER(x) * 3) - 2)), 'yyyyMM')
(defmethod sql.qp/date [:h2 :quarter]
  [_ _ expr]
  (parse-datetime "yyyyMM"
                  (hx/concat (hx/year expr) (hx/- (hx/* (hx/quarter expr)
                                                        3)
                                                  2))))

(defmethod sql.qp/->honeysql [:h2 :log]
  [driver [_ field]]
  (hsql/call :log10 (sql.qp/->honeysql driver field)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         metabase.driver.sql-jdbc impls                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private db-type->base-type
  {:ARRAY                               :type/*
   :BIGINT                              :type/BigInteger
   :BINARY                              :type/*
   :BIT                                 :type/Boolean
   :BLOB                                :type/*
   :BOOL                                :type/Boolean
   :BOOLEAN                             :type/Boolean
   :BYTEA                               :type/*
   :CHAR                                :type/Text
   :CHARACTER                           :type/Text
   :CLOB                                :type/Text
   :DATE                                :type/Date
   :DATETIME                            :type/DateTime
   :DEC                                 :type/Decimal
   :DECIMAL                             :type/Decimal
   :DOUBLE                              :type/Float
   :FLOAT                               :type/Float
   :FLOAT4                              :type/Float
   :FLOAT8                              :type/Float
   :GEOMETRY                            :type/*
   :IDENTITY                            :type/Integer
   :IMAGE                               :type/*
   :INT                                 :type/Integer
   :INT2                                :type/Integer
   :INT4                                :type/Integer
   :INT8                                :type/BigInteger
   :INTEGER                             :type/Integer
   :LONGBLOB                            :type/*
   :LONGTEXT                            :type/Text
   :LONGVARBINARY                       :type/*
   :LONGVARCHAR                         :type/Text
   :MEDIUMBLOB                          :type/*
   :MEDIUMINT                           :type/Integer
   :MEDIUMTEXT                          :type/Text
   :NCHAR                               :type/Text
   :NCLOB                               :type/Text
   :NTEXT                               :type/Text
   :NUMBER                              :type/Decimal
   :NUMERIC                             :type/Decimal
   :NVARCHAR                            :type/Text
   :NVARCHAR2                           :type/Text
   :OID                                 :type/*
   :OTHER                               :type/*
   :RAW                                 :type/*
   :REAL                                :type/Float
   :SIGNED                              :type/Integer
   :SMALLDATETIME                       :type/DateTime
   :SMALLINT                            :type/Integer
   :TEXT                                :type/Text
   :TIME                                :type/Time
   :TIMESTAMP                           :type/DateTime
   :TINYBLOB                            :type/*
   :TINYINT                             :type/Integer
   :TINYTEXT                            :type/Text
   :UUID                                :type/Text
   :VARBINARY                           :type/*
   :VARCHAR                             :type/Text
   :VARCHAR2                            :type/Text
   :VARCHAR_CASESENSITIVE               :type/Text
   :VARCHAR_IGNORECASE                  :type/Text
   :YEAR                                :type/Integer
   (keyword "DOUBLE PRECISION")         :type/Float
   (keyword "TIMESTAMP WITH TIME ZONE") :type/DateTimeWithLocalTZ})

(defmethod sql-jdbc.sync/database-type->base-type :h2
  [_ database-type]
  (db-type->base-type database-type))

;; These functions for exploding / imploding the options in the connection strings are here so we can override shady
;; options users might try to put in their connection string. e.g. if someone sets `ACCESS_MODE_DATA` to `rws` we can
;; replace that and make the connection read-only.

(defn- file+options->connection-string
  "Implode the results of `connection-string->file+options` back into a connection string."
  [file options]
  (apply str file (for [[k v] options]
                    (str ";" k "=" v))))

(defn- connection-string-set-safe-options
  "Add Metabase Security Settings™ to this `connection-string` (i.e. try to keep shady users from writing nasty SQL)."
  [connection-string]
  {:pre [(string? connection-string)]}
  (let [[file options] (connection-string->file+options connection-string)]
    (file+options->connection-string file (merge options {"IFEXISTS"         "TRUE"
                                                          "ACCESS_MODE_DATA" "r"}))))

(defmethod sql-jdbc.conn/connection-details->spec :h2
  [_ details]
  {:pre [(map? details)]}
  (mdb.spec/spec :h2 (update details :db connection-string-set-safe-options)))

(defmethod sql-jdbc.sync/active-tables :h2
  [& args]
  (apply sql-jdbc.sync/post-filtered-active-tables args))

(defmethod sql-jdbc.execute/connection-with-timezone :h2
  [driver database ^String _timezone-id]
  ;; h2 doesn't support setting timezones, or changing the transaction level without admin perms, so we can skip those
  ;; steps that are in the default impl
  (let [conn (.getConnection (sql-jdbc.execute/datasource-with-diagnostic-info! driver database))]
    (try
      (doto conn
        (.setReadOnly true))
      (catch Throwable e
        (.close conn)
        (throw e)))))

;; de-CLOB any CLOB values that come back
(defmethod sql-jdbc.execute/read-column-thunk :h2
  [_ ^ResultSet rs ^ResultSetMetaData rsmeta ^Integer i]
  (let [classname (some-> (.getColumnClassName rsmeta i)
                          (Class/forName true (classloader/the-classloader)))]
    (if (isa? classname Clob)
      (fn []
        (mdb.jdbc-protocols/clob->str (.getObject rs i)))
      (fn []
        (.getObject rs i)))))

(defmethod sql-jdbc.execute/set-parameter [:h2 OffsetTime]
  [driver prepared-statement i t]
  (let [local-time (t/local-time (t/with-offset-same-instant t (t/zone-offset 0)))]
    (sql-jdbc.execute/set-parameter driver prepared-statement i local-time)))

(defmethod driver/incorporate-ssh-tunnel-details :h2
  [_ db-details]
  (if (and (:tunnel-enabled db-details) (ssh/ssh-tunnel-open? db-details))
    (if (and (:db db-details) (str/starts-with? (:db db-details) "tcp://"))
      (let [details (ssh/include-ssh-tunnel! db-details)
            db      (:db details)]
        (assoc details :db (str/replace-first db (str (:orig-port details)) (str (:tunnel-entrance-port details)))))
      (do (log/error (tru "SSH tunnel can only be established for H2 connections using the TCP protocol"))
          db-details))
    db-details))
