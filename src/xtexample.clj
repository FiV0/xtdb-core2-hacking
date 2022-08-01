(ns xtexample
  (:require [core2.api :as c2]
            [core2.local-node :as local-node]
            [core2.sql.pgwire :as pgwire]
            [clojure.set]
            [clojure.java.io :as io]))

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
     (into []))

;;///////////////////////////////////////////////////////////////////////////////
;;===============================================================================
;;                               persitstent node
;;===============================================================================
;;\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\

(def dir (io/file "data"))

(def persistent-node
  (->> {:core2.log/local-directory-log {:root-path (io/file dir "log")}
        :core2.buffer-pool/buffer-pool {:cache-path (io/file dir "buffers")}
        :core2.object-store/file-system-object-store {:root-path (io/file dir "objects")}}
       (local-node/start-node)))

(comment
  (def basis @(c2/submit-tx persistent-node [[:put {:_id 1 :data -1 }]]))
  (c2/sql-query persistent-node "select a.data from a" {:basis basis})
  ;; => [{:data -1}]
  (def basis2 @(c2/submit-tx persistent-node [[:put {:_id 1 :data 2 }]]))
  (c2/sql-query persistent-node "select some.data from some" {:basis basis2})
  ;; => [{:data 2}]
  (def basis3 @(c2/submit-tx persistent-node [[:put {:_id 1  :data 3}]]))
  (c2/sql-query persistent-node "select foo.data from foo" {:basis basis3})
  ;; => [{:data 0}]
  (def basis4 @(c2/submit-tx persistent-node [[:put {:_id 2 :data 4}]]))
  (c2/sql-query persistent-node "select foo.data from foo" {:basis basis3})
  ;; => [{:data 0} {:data 4}]

  (.close persistent-node)


  (def tx1 @(c2/submit-tx persistent-node [[:put {:_id 1  :data 1}]]))
  (c2/sql-query persistent-node "select foo.data from foo" {:basis {:tx tx1}})
  ;; => [{:data 1}]
  (def tx2 @(c2/submit-tx persistent-node [[:put {:_id 2 :data 2}]]))
  (c2/sql-query persistent-node "select foo.data from foo" {:basis {:tx tx1}})
  ;; => [{:data 1} {:data 2}]

 )
