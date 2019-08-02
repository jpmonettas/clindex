(ns clindex.workbench
  (:require [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.dir :as ns-dir]
            [clojure.tools.namespace.track :as ns-track]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.java.io :as io]
            [clindex.core :as core]))


(comment

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

  )
