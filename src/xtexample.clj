(ns xtexample
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.edn :as edn]
            [xtdb.datalog :as xt]
            [xtdb.sql :as xt.sql]
            [xtdb.node :as xt.node]
            [xtdb.util :as util]
            [clojure.set :as set]))

(with-open [node (xt.node/start-node {})]
  (xt/status node))


(def node (xt.node/start-node {}))

(def data (->> (read-string (slurp "entities.edn"))
               (map #(clojure.set/rename-keys % {:db/id :xt/id}))))

(count data)
;; => 12888

(defn add-data [data]
  (->> (map #(vector :put :docs %) data)
       (take 10)
       (xt/submit-tx node)))

(add-data data)

;;///////////////////////////////////////////////////////////////////////////////
;;===============================================================================
;;                               persitstent node
;;===============================================================================
;;\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\

(def dir (io/file "data"))

(def node
  (->> {:xtdb.log/local-directory-log {:root-path (io/file dir "log")}
        :xtdb.buffer-pool/buffer-pool {:cache-path (io/file dir "buffers")}
        :xtdb.object-store/file-system-object-store {:root-path (io/file dir "objects")}}
       (xt.node/start-node)))

(xt.sql/submit-tx node [[:sql "INSERT INTO users (xt$id, name) VALUES (?, ?)"
                         [[:mark "mark"]]]])

(xt.sql/q node "SELECT u.xt$id, u.name FROM users AS u")

(.close node)

(require '[xtdb.datalog :as xt]
         '[xtdb.sql :as xt.sql]
         '[xtdb.node :as xt.node])

(def node (xt.node/start-node {}))

;; (xt.sql/submit-tx node [[:sql "INSERT INTO users (xt$id, name) VALUES (?, ?)"
;;                          [[:mark "mark"]]]])

;; (xt.sql/q node "SELECT u.xt$id, u.name FROM users AS u")


(def data (-> (slurp "learn-datalog-today.edn") edn/read-string))
(def people (filter #(contains? (->> % keys (map namespace) set) "person" ) data))
(def movies (filter #(contains? (->> % keys (map namespace) set) "movie" ) data))

(defn wrap-in-puts [table docs]
  (map #(vector :put table %) docs))

(defn wrap-people-puts [docs]
  (map (fn [{:person/keys [born death] :as doc}]
         (vector :put :people doc {:for-valid-time [:in born death]})) docs))

(defn year->instant [year]
  (.toInstant (.atStartOfDay (java.time.LocalDate/parse (str year "-01-01")) (java.time.ZoneId/of "UTC"))))

(defn wrap-movie-puts [docs]
  (map (fn [{:movie/keys [year] :as doc}]
         (vector :put :movies doc {:for-valid-time [:in (year->instant year) nil]})) docs))

(xt/submit-tx node (wrap-people-puts people))
(xt/submit-tx node (wrap-movie-puts movies))

;; In Core1 there are only as of now queries and until recently one had to query
;; the history API to get the start and end valid time. With Core2 this completely
;; changes. You are now able to query across time or as of now. This is even possible on a per
;; table basis. So let's explore these features a bit.

;; The dataset is very likely stale so might not reflect reality.

;; We will be working with a small movie dataset that contains two tables. A movie table and a
;; people's table. The movie table contains information like name of the moive, release date, director, cast and genre. The release date is set as valid-time-start for the movies. The directors and cast are represented in the people's table with their name, birthdate and (if existant) death. We set the valid-time's to their respective birthdate and death.

;; An exemplary person document
{:person/name "James Cameron",
 :person/born #inst "1954-08-16T00:00:00.000-00:00",
 :xt/id -100}

;; An exemplary movie document
{:movie/title "The Terminator",
 :movie/year 1984,
 :movie/director -100,
 :movie/cast [-101 -102 -103],
 :movie/sequel -207,
 :xt/id -200}

;; By default queries in Datalog remain as-of-now.

(xt/q node '{:find [(count id)]
             :keys [nb-alive-people]
             :where [(match :people {:xt/id id})]})
;; => [{:nb-alive-people 45}]

;; What are the number of people that were alive at some point?

(xt/q node '{:find [(count id)]
             :keys [nb-people-lived]
             :where [(match :people {:xt/id id} {:for-valid-time :all-time})]})
;; => [{:nb-people-lived 50}]

;; You can also specify the valid-time range for the whole query.

(xt/q node '{:find [(count id)]
             :keys [nb-people-lived]
             :where [(match :people {:xt/id id})]
             :default-all-app-time? true})
;; => [{:nb-people-lived 50}]

;; The valid-time on document level takes precedence over the query valid-time

(xt/q node '{:find [(count id)]
             :keys [nb-people-lived]
             :where [(match :people {:xt/id id} {:for-valid-time [:at :now]})]
             :default-all-app-time? true})
;; => [{:nb-people-lived 45}]

;; Different document types can also have different valid time ranges specified.
;; This opens up the possibility to mix and match valid time ranges of different entity types.

;; What movies were released posthumously?

(xt/q node '{:find [movie actor]
             :where [(match :movies {:movie/title movie :xt/valid-from vt-from})
                     (match :people {:xt/id id :person/name actor :xt/valid-to vt-to} {:for-valid-time :all-time})
                     [(< vt-to vt-from)]]})

;; There are also virtual columns `vt/valid-time` and `vt/system-time` to refer to the valid-time and system-time periods respectively.

(xt/q node '{:find [name valid-time system-time]
             :where [(match :people {:xt/id -100
                                     :xt/valid-time valid-time
                                     :xt/system-time system-time
                                     :person/name name})]})
;; => [{:name "James Cameron",
;;      :valid-time
;;      {:start #time/zoned-date-time "1954-08-16T00:00Z[UTC]",
;;       :end #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"},
;;      :system-time
;;      {:start #time/zoned-date-time "2023-05-01T19:03:09.874172Z[UTC]",
;;       :end #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}}]

;; There are some temporal predicates especially for periods. You can find them
;; in the reference at
;; https://www.xtdb.com/reference/main/stdlib/temporal

;; People that lived between 1940 and 2000.
(xt/q node '{:find [name valid-time]
             :where [(match :people {:xt/valid-time valid-time
                                     :person/name name} {:for-valid-time :all-time})
                     [(contains? (period #inst "1940" #inst "2000") valid-time)]]})

;; People that lived when Arnold Schwarzenegger was alive.
(xt/q node '{:find [name valid-time]
             :where [(match :people {:xt/id arnold
                                     :xt/valid-time arnolds-time
                                     :person/name "Arnold Schwarzenegger"})

                     (match :people {:xt/id id
                                     :xt/valid-time valid-time
                                     :person/name name} {:for-valid-time :all-time})
                     [(<> id arnold)]
                     [(overlaps? valid-time arnolds-time)]]})



(comment
  (xt.sql/submit-tx persistent-node [[:put :docs {:xt/id 1 :data -1 }]])
  (xt.sql/q persistent-node "select a.data from a" {})
  ;; => [{:data 0} {:data 4}]
  (.close persistent-node)

  )
