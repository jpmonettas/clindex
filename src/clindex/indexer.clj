(ns clindex.indexer
  (:require [datascript.core :as d]))

(def db-conn (d/create-conn {:project/name      {:db/cardinality :db.cardinality/one}
                             :project/depends   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                             :file/name         {:db/cardinality :db.cardinality/one}
                             :namespace/name    {:db/cardinality :db.cardinality/one}
                             :namespace/project {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :namespace/file    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :var/name          {:db/cardinality :db.cardinality/one}
                             :var/line          {:db/cardinality :db.cardinality/one}
                             :var/namespace     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :var/public?       {:db/cardinality :db.cardinality/one}}))

(defn stable-id [& args]
  (Math/abs (apply hash [args])))

(defn project-id [proj-symb]
  (stable-id :project proj-symb))

(defn file-id [file]
  (stable-id :file file))

(defn namespace-id [namespace-symb]
  (stable-id :namespace namespace-symb))

(defn var-id [namespace-symb var-symb]
  (stable-id :var namespace-symb var-symb))


(defn project-facts [{:keys [:project/name :project/dependencies] :as proj}]
  (let [proj-id (project-id name)]
    (->> [[:db/add proj-id :project/name name]]
         (into (mapv (fn [dep-symb]
                       [:db/add proj-id :project/depends (project-id dep-symb)])
                     (:project/dependencies proj))))))

(defn files-facts [{:keys [:project/files] :as proj}]
  (->> files
       (mapv (fn [file]
               [:db/add (file-id (:full-path file)) :file/name (:full-path file)]))))

(defn namespace-facts [ns]
  (let [ns-id (namespace-id (:namespace/name ns))
        vars-facts (fn [vs pub?]
                     (mapcat (fn [v]
                               (let [vid (var-id (:namespace/name ns) v)
                                     vline (-> v meta :line)]
                                 (when (nil? vline)
                                   (println (format "[Warning], no line meta for %s/%s" (:namespace/name ns) v)))
                                 (cond-> [[:db/add vid :var/name v]
                                          [:db/add vid :var/public? pub?]
                                          [:db/add vid :var/namespace ns-id]]
                                   vline (into [[:db/add vid :var/line vline]]))))
                             vs))
        facts (-> [[:db/add ns-id :namespace/name (:namespace/name ns)]
                   [:db/add ns-id :namespace/project (project-id (:namespace/project ns))]
                   [:db/add ns-id :namespace/file (file-id (:namespace/file-content-path ns))]]
                  (into (vars-facts (:namespace/public-vars ns) true))
                  (into (vars-facts (:namespace/private-vars ns) false)))]
    facts))

(defn source-facts [all-ns-map]
  (let [all-ns-facts (mapcat namespace-facts (vals all-ns-map))]
    (-> []
        (into all-ns-facts))))

(defn check-facts [tx-data]
  (doseq [[_ _ _ v :as f] tx-data]
    (when (nil? v)
      (println "Error, nil valued fact " f))))

(defn index-all! [all-projs-map all-ns-map]
  (let [all-projs-facts (mapcat project-facts (vals all-projs-map))
        all-files-facts (mapcat files-facts (vals all-projs-map))
        all-source-facts (source-facts all-ns-map)
        tx-data (-> []
                    (into all-projs-facts)
                    (into all-files-facts)
                    (into all-source-facts))]
    (check-facts tx-data)
    (println (format "About to transact %d facts" (count tx-data)))
    (d/transact! db-conn tx-data)))

(comment

  (require '[clindex.scanner :as scanner])
  (require '[clojure.tools.namespace.find :as ctnf])

  (def all-projs (scanner/all-projects "/home/jmonetta/my-projects/clindex"
                                       {:platform ctnf/clj}))

  (def all-ns (scanner/all-namespaces all-projs {:platform ctnf/clj #_ctnf/cljs}))
  )
