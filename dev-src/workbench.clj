(ns workbench
  (:require [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.dir :as ns-dir]
            [clojure.tools.namespace.track :as ns-track]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.java.io :as io]
            [datascript.core :as d]
            [clindex.api :as capi]
            [clojure.pprint :as pprint]))

#_(defn search-var-starts-with
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

#_(defn x-refs
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

  (require '[clindex.api :as clindex])
  (require '[datascript.core :as d])
  (require '[clojure.string :as str])
  (require '[clojure.pprint :as pprint])

  ;; first you index a project folder for some platforms
  (clindex/index-project! "./"
                          {:platforms #{:clj}})

  ;; retrieve the datascript dbs
  (def db (clindex/db :clj))

  ;; now you can query the dbs
  ;; lets query all the vars that start with "eval"
  (->> (d/q '[:find ?vname ?nname ?pname ?vline ?fname
              :in $ ?text
              :where
              [?fid :file/name ?fname]
              [?pid :project/name ?pname]
              [?nid :namespace/file ?fid]
              [?nid :namespace/project ?pid]
              [?nid :namespace/name ?nname]
              [?vid :var/namespace ?nid]
              [?vid :var/name ?vname]
              [?vid :var/line ?vline]
              [(str/starts-with? ?vname ?text)]]
            db
            "eval")
       (map #(zipmap [:name :ns :project :line :file] %))
       (pprint/print-table))



  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (def tx-result (time (capi/index-project! "/home/jmonetta/my-projects/clindex"
                                            {:platforms #{:clj}})))


  (search-var-starts-with "eval")
  (x-refs 'clindex.indexer 'namespace-facts)

  (def tx-result (time (capi/index-project! "/home/jmonetta/my-projects/district0x/memefactory"
                                            {:platforms #{:cljs}})))
  (search-var-starts-with "start")
  (x-refs 'cljs-web3.eth 'block-number)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (def db-conn (d/create-conn {}))

  (def proj (core/project "/home/jmonetta/my-projects/clindex"))
  (def proj (core/project "/home/jmonetta/my-projects/district0x/memefactory"))

  (def all-projs (core/all-projects proj))

  (dep/topo-sort
   (reduce
    (fn [dep-graph {:keys [:project/name :project/dependencies]}]
      (reduce (fn [dg pd]
                (dep/depend dg name pd))
              dep-graph
              dependencies))
           (dep/graph)
           (vals all-projs)))


  (def files
    [(io/file "/home/jmonetta/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar")
     (io/file "/home/jmonetta/my-projects/clindex/src/clindex/")])

  (map #(meta %) (ns-find/find-ns-decls files))

  (->> (ns-find/find-ns-decls files)
       (reduce
        (fn [r ns-decl]
          (assoc r (ns-parse/name-from-ns-decl ns-decl) (ns-parse/deps-from-ns-decl ns-decl) ))
        {}))

    ;; all namespaces for 'org.clojure/spec.alpha project
  (d/q '[:find ?nsn
         :in $ ?pn
         :where
         [?pid :project/name ?pn]
         [?nsid :namespace/project ?pid]
         [?nsid :namespace/name ?nsn]]
       @(capi/index-db)
       'org.clojure/spec.alpha)

  ;; all namespaces for all projects
  (d/q '[:find ?pid ?pn ?nsid ?nsn
         :where
         [?pid :project/name ?pn]
         [?nsid :namespace/project ?pid]
         [?nsid :namespace/name ?nsn]]
       @(capi/index-db))

  ;; all vars for clojure.spec.alpha namespace
  (d/q '[:find ?vn ?vl ?fname
         :in $ ?nsn
         :where
         [?fid :file/name ?fname]
         [?nid :namespace/file ?fid]
         [?nid :namespace/name ?nsn]
         [?vid :var/namespace ?nid]
         [?vid :var/name ?vn]
         [?vid :var/line ?vl]]
       @(capi/index-db)
       'clojure.spec.alpha)

  )
