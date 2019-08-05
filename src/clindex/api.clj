(ns clindex.api
  (:require [clindex.scanner :as scanner]
            [clindex.indexer :as indexer]
            [datascript.core :as d]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ctnf]))


(defn index-project!
  "Recursively index the project at base-dir and all its deps.
  Returns the datascript tx result"
  [base-dir platform]
  (let [opts {:platform (case platform
                          :clj  ctnf/clj
                          :cljs ctnf/cljs)}
        all-projs (scanner/all-projects base-dir
                                        opts)
        all-ns (scanner/all-namespaces all-projs opts)]
    (indexer/index-all! all-projs all-ns)))

(defn index-db
  "Returns the index datascript db"
  []
  @indexer/db-conn)

(defn search-var
  "Searches the index for a var, returns a collection of maps containing
  :name, :ns, :project :file and :line."
  [search-term]
  (let [q-result (d/q '[:find ?vn ?nsn ?pname ?vl ?fname
                        :in $ ?st
                        :where
                        [?fid :file/name ?fname]
                        [?pid :project/name ?pname]
                        [?nid :namespace/file ?fid]
                        [?nid :namespace/project ?pid]
                        [?nid :namespace/name ?nsn]
                        [?vid :var/namespace ?nid]
                        [?vid :var/name ?vn]
                        [?vid :var/line ?vl]
                        [(str/starts-with? ?vn ?st)]]
                      @indexer/db-conn
                      search-term)]
       (map #(zipmap [:name :ns :project :line :file] %) q-result)))

(comment

  (def tx-result (index-project! "/home/jmonetta/my-projects/clindex" :clj))
  (search-var "eval")

  (def tx-result (index-project! "/home/jmonetta/my-projects/district0x/memefactory" :cljs))
  (search-var "start")
 )
