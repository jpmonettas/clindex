(ns clindex.api
  (:require [clindex.scanner :as scanner]
            [clindex.indexer :as indexer]
            [datascript.core :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.tools.namespace.track :as ns-track]
            [clindex.schema :refer [schema]]
            [clindex.utils :as utils]
            [hawk.core :as hawk]
            [clojure.tools.namespace.file :as ns-file]
            [clojure.pprint :as pprint]))

(def db-conns (atom {}))
(def all-projects-by-platform (atom nil))
(def all-ns-by-platform (atom nil))
(def trackers-by-platform (atom nil))

(comment
  (index-project! "./test-resources/test-project" {:platforms #{:clj}})

  (require '[clojure.pprint :as pprint])
  (file-change-handler (fn [diff] (pprint/pprint diff))
                       #{:clj}
                       {}
                       {:kind :delete
                        :file (io/file "./test-resources/test-project/src/test_code.cljc")})
  (file-change-handler (fn [diff] (pprint/pprint diff))
                       #{:clj}
                       {}
                       {:kind :modify
                        :file (io/file "./test-resources/test-project/src/test_code.cljc")})
  )

(defn- get-ns-for-path [all-ns file-path]
  (->> (vals all-ns)
       (some #(when (= (:namespace/file-content-path %)
                       file-path)
                (:namespace/name %)))))

(defn- build-dep-map [all-ns]
  (reduce (fn [r [ns-name {:keys [:namespace/dependencies]}]]
            (assoc r ns-name dependencies))
   {}
   all-ns))

(defn- build-opts [platform-key]
  {:platform (case platform-key
               :clj  ns-find/clj
               :cljs ns-find/cljs)})

(defn- process-unloads [{:keys [tracker] :as m}]
  (reduce (fn [r ns-symb]
            (let [ns-id (utils/namespace-id ns-symb)]
              (-> r
                  (update-in [:tracker ::ns-track/unload] rest)
                  (update :namespaces dissoc ns-symb)
                  (update :tx-data conj [:db.fn/retractEntity ns-id]))))
          m
          (::ns-track/unload tracker)))

(defn- process-loads [{:keys [tracker namespaces all-projects] :as m} opts]
  (reduce (fn [r ns-symb]
            (let [ns-file-path (:namespace/file-content-path (or (get namespaces ns-symb)
                                                                 (meta ns-symb)))
                  ns (scanner/scan-namespace ns-file-path all-projects opts)
                  updated-namespaces (assoc namespaces ns-symb ns)
                  ns-facts (indexer/namespace-full-facts updated-namespaces ns-symb)]
              (-> r
                  (update-in [:tracker ::ns-track/load] rest)
                  (assoc :namespaces updated-namespaces)
                  (update :tx-data into ns-facts))))
          m
          (::ns-track/load tracker)))

(defn- reindex-namespaces [all-projs all-ns tracker opts]
  (-> {:tracker tracker
       :tx-data []
       :namespaces all-ns
       :all-projects all-projs}
      process-unloads
      (process-loads opts)))

(defn- file-change-handler [on-new-facts platforms ctx {:keys [kind file]}] ;; kind can be :create, :modify or :delete ;; file is java.io.File
  ;; to avoid indexing emacs backup files
  ;; TODO, fix this is a nicer way
  (when-not (.startsWith (.getName file) ".#")
    (doseq [p platforms]
      (let [plat-opts (build-opts p)
            all-platform-projs (get @all-projects-by-platform p)
            all-platform-ns (get @all-ns-by-platform p)
            platform-tracker (get @trackers-by-platform p)
            file-path (utils/normalize-path file)
            platform-tracker' (cond
                                (#{:delete} kind)
                                (let [ns-symb (get-ns-for-path all-platform-ns file-path)]
                                  (ns-track/remove platform-tracker [ns-symb]))

                                (#{:modify :add} kind)
                                (let [ns-decl (ns-file/read-file-ns-decl file ns-find/clj)
                                      ns-symb (with-meta (ns-parse/name-from-ns-decl ns-decl)
                                                {:namespace/file-content-path file-path})
                                      deps (ns-parse/deps-from-ns-decl ns-decl)]
                                  (ns-track/add platform-tracker {ns-symb deps})))
            _ (println (format "File %s changed, retracting namespaces (%s), indexing namspeces (%s)"
                               (.getName file) (apply str (::ns-track/unload platform-tracker')) (apply str (::ns-track/load platform-tracker'))))
            {:keys [tx-data namespaces tracker]} (reindex-namespaces all-platform-projs all-platform-ns platform-tracker' plat-opts)
            tx-data-diff (:tx-data (d/transact! (get @db-conns p) tx-data))]

        (swap! all-ns-by-platform assoc p namespaces)
        (swap! trackers-by-platform assoc p tracker)
        (on-new-facts tx-data-diff)))))

(defn index-project!
  ""
  [base-dir {:keys [platforms extra-schema on-new-facts]}]
  ;; index everything by platform
  (doseq [p platforms]
    (let [plat-opts (build-opts p)
          all-projs (scanner/scan-all-projects base-dir plat-opts)
          all-ns (scanner/scan-namespaces all-projs plat-opts)
          tracker (-> (ns-track/tracker)
                      (ns-track/add (build-dep-map all-ns))
                      (dissoc ::ns-track/unload ::ns-track/load)) ;; we can discard this first time since we are indexing everything
          tx-data (indexer/all-facts {:projects all-projs
                                      :namespaces all-ns})]
      (swap! db-conns assoc p (d/create-conn (merge schema extra-schema)))
      (swap! all-projects-by-platform assoc p all-projs)
      (swap! all-ns-by-platform assoc p all-ns)
      (swap! trackers-by-platform assoc p tracker)
      (utils/check-facts tx-data)
      (println (format "About to transact %d facts" (count tx-data) "for platform" p))
      (d/transact! (get @db-conns p) tx-data)))

  ;; install watcher for reindexing when on-new-facts callback provided
  (when on-new-facts
    (println "Watching " base-dir)
    (hawk/watch!
     [{:paths [base-dir]
       :handler (partial file-change-handler on-new-facts platforms)}])))

(defn db
  ""
  [platform]
  @(get @db-conns platform))
