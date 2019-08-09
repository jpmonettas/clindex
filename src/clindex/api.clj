(ns clindex.api
  (:require [clindex.scanner :as scanner]
            [clindex.indexer :as indexer]
            [datascript.core :as d]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ctnf]
            [clojure.pprint :as pprint]))


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
  "Searches the index for a var, and prints a table containing
  :name, :ns, :project :file and :line."
  [search-term]
  (let [q-result (d/q '[:find ?vn ?nsn ?pname ?vl #_?fname
                        :in $ ?st
                        :where
                        #_[?fid :file/name ?fname]
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

    (->> q-result
     (map #(zipmap [:name :ns :project :line :file] %))
     (pprint/print-table))))

(defn fn-calls
  ""
  [ns fname]
  (let [q-result (d/q '[:find ?fc-ns ?fc-name
                        :in $ ?nsn ?fn
                        :where
                        [?nid :namespace/name ?nsn]
                        [?vid :var/namespace ?nid]
                        [?fid :function/var ?vid]
                        [?vid :var/name ?fn]
                        [?fid :function/calls ?fc-id]
                        [?fc-id :function/var ?fc-vid]
                        [?fc-vid :var/name ?fc-name]
                        [?fc-vid :var/namespace ?fc-ns-id]
                        [?fc-ns-id :namespace/name ?fc-ns]]
                      @indexer/db-conn
                      ns
                      fname)]
    (->> q-result
         (map #(zipmap [:ns :fn-name] %))
         (pprint/print-table))))

(defn x-refs
  ""
  [ns fname]
  (let [q-result (d/q '[:find ?fc-ns ?fc-name
                        :in $ ?nsn ?fn
                        :where

                        [?nid :namespace/name ?nsn]
                        [?vid :var/namespace ?nid]
                        [?fid :function/var ?vid]
                        [?vid :var/name ?fn]

                        [?fc-id :function/calls ?fid]

                        [?fc-id :function/var ?fc-vid]
                        [?fc-vid :var/name ?fc-name]

                        [?fc-vid :var/namespace ?fc-ns-id]
                        [?fc-ns-id :namespace/name ?fc-ns]]
                      @indexer/db-conn
                      ns
                      fname)]
    (->> q-result
         (map #(zipmap [:ns :fn-name] %))
         (pprint/print-table))))

(comment

  (def tx-result (index-project! "/home/jmonetta/my-projects/clindex" :clj))
  (search-var "eval")
  (fn-calls 'clindex.indexer 'namespace-facts)
  (x-refs 'clindex.indexer 'namespace-facts)

  (def tx-result (index-project! "/home/jmonetta/my-projects/district0x/memefactory" :cljs))
  (search-var "start")
  )
