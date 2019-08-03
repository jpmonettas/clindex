(ns clindex.api
  (:require [clindex.core :as core]
            [datascript.core :as d]
            [clojure.string :as str]))


;; (defn index-project!
;;   "Recursively index the project at base-dir and all its deps.
;;   Returns the datascript tx result"
;;   [base-dir]
;;   (core/index-project! base-dir))

;; (defn index-db
;;   "Returns the index datascript db"
;;   []
;;   @core/db-conn)

;; (defn search-var
;;   "Searches the index for a var, returns a collection of maps containing
;;   :name, :ns, :project :file and :line."
;;   [search-term]
;;   (let [q-result (d/q '[:find ?vn ?nsn ?pname ?vl ?fname
;;                         :in $ ?st
;;                         :where
;;                         [?fid :file/name ?fname]
;;                         [?pid :project/name ?pname]
;;                         [?nid :namespace/file ?fid]
;;                         [?nid :namespace/project ?pid]
;;                         [?nid :namespace/name ?nsn]
;;                         [?vid :var/namespace ?nid]
;;                         [?vid :var/name ?vn]
;;                         [?vid :var/line ?vl]
;;                         [(str/starts-with? ?vn ?st)]]
;;                       @core/db-conn
;;                       search-term)]
;;        (map #(zipmap [:name :ns :project :line :file] %) q-result)))

;; (comment

;;   (def tx-result (index-project! "/home/jmonetta/my-projects/clindex"))

;;   (search-var "eval")
;;  )
