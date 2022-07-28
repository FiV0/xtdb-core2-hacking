(ns xtexample
  (:require [core2.api :as c2]
            [core2.local-node :as local-node]
            [core2.sql.pgwire :as pgwire]
            [clojure.set]))

(def node (local-node/start-node {}))

(def server (pgwire/serve node))

(def data (->> (read-string (slurp "entities.edn"))
               (map #(clojure.set/rename-keys % {:db/id :_id}))))

;; TODO: remove this when we have basic DML
(defn add-data [data]
  (->> (map #(vector :put %) data)
       (c2/submit-tx node)))

(def tx @(add-data data))

(->> (c2/plan-datalog node
                      ' {:find [?name]
                         :where
                         [[?t :track/name "For Those About To Rock (We Salute You)"]
                          [?t :track/album ?album]
                          [?album :album/artist ?artist]
                          [?artist :artist/name ?name]]})
     #_(into []))
