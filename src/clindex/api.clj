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

(defn search-var-starts-with
  "Searches the index for a var, and prints a table containing
  :name, :ns, :project :file and :line."
  [search-term]
  (let [q-result (d/q '[:find ?vn ?nsn ?pname ?vl ?fname
                        :in $ ?st
                        :where
                        [(get-else $ ?fid :file/name "N/A") ?fname] ;; while we fix the file issue
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

(defn x-refs
  "Searches and prints a table with all the namespaces,lines,columns that references this ns/vname"
  [ns vname]
  (let [q-result (d/q '[:find ?pname ?vrnsn ?vn ?in-fn ?vrline ?vrcolumn ?fname
                        :in $ ?nsn ?vn
                        :where
                        [?nid :namespace/name ?nsn]
                        [?vid :var/namespace ?nid]
                        [?vid :var/name ?vn]
                        [?vrid :var-ref/var ?vid]
                        [?vrid :var-ref/namespace ?vrnid]
                        [?vrid :var-ref/line ?vrline]
                        [?vrid :var-ref/column ?vrcolumn]
                        [?vrid :var-ref/in-function ?fnid]
                        [?fnid :function/var ?fnvid]
                        [?fnvid :var/name ?in-fn]
                        [?vrnid :namespace/name ?vrnsn]
                        [?pid :project/name ?pname]
                        [?vrnid :namespace/project ?pid]
                        [?vrnid :namespace/file ?fid]
                        [(get-else $ ?fid :file/name "N/A") ?fname]] ;; while we fix the file issue
                      @indexer/db-conn
                      ns
                      vname)]
    (->> q-result
         (map #(zipmap [:project :ns :var-name :in-fn :line :column :file] %))
         (pprint/print-table))))

(comment

  (def tx-result (time (index-project! "/home/jmonetta/my-projects/clindex" :clj)))


  (search-var-starts-with "eval")
  (x-refs 'clindex.indexer 'namespace-facts)

  (def tx-result (time (index-project! "/home/jmonetta/my-projects/district0x/memefactory" :cljs)))
  (search-var-starts-with "start")
  (x-refs 'cljs-web3.eth 'block-number)
  )
