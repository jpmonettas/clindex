(ns clindex.indexer
  (:require [datascript.core :as d]
            [rewrite-clj.zip :as z]
            [clojure.string :as str]))

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

(defn expand-symbol-alias [aliases-map current-ns-symb symb]
  (if-let [ns-symb (namespace symb)]
    (if-let [full-ns-symb (get aliases-map (symbol ns-symb))]
      (symbol (name full-ns-symb) (name symb))
      symb)
    symb))

(defn resolve-symbol [all-ns-map ns-symb fsymb]
  (let [ns-requires (conj (:namespace/dependencies (get all-ns-map ns-symb))
                          'clojure.core)] ;; NOTE : not sure if this should be done here or in a more general way
    (some (fn [rns-symb]
            (let [{:keys [:namespace/public-vars :namespace/macros] :as rns} (get all-ns-map rns-symb)]
              (when (contains? (into public-vars macros) fsymb)
                (symbol (name (:namespace/name rns)) (name fsymb)))))
      ns-requires)))

(defmulti form-facts (fn [all-ns-map ctx form] (first form)))

(defmethod form-facts 'clojure.spec.alpha/def
  [all-ns-map ctx form]
  #_(println "Analyzing SPEC form " form)
  {:facts []
   :ctx ctx})

(defmethod form-facts 'defn
  [all-ns-map ctx [_ fname]]
  #_(println "Analyzing DEFN " fname)
  {:facts [[:db/add 15 :function/name fname]]
   :ctx (merge ctx {:function fname})})

(defmethod form-facts :default
  [all-ns-map ctx form]
  #_(println "Analyzing form " form "with context " ctx)
  {:facts []
   :ctx ctx})

(defn fully-qualify-form-first-symb [all-ns-map ns-symb form]
  (if (symbol? (first form))
    (let [ns (get all-ns-map ns-symb)
          ns-alias-map (:namespace/alias-map ns)
          ns-vars (-> (:namespace/public-vars ns)
                      (into (:namespace/private-vars ns))
                      (into (:namespace/macros ns)))
          fsymb (first form)
          fsymb-ns (when-let [s (namespace fsymb)]
                     (symbol s))
          fq-symb (cond

                    ;; check OR
                    (or (and fsymb-ns (contains? all-ns-map fsymb-ns)) ;; it is already fully qualified
                        (special-symbol? fsymb)                        ;; it is a special symbol
                        (str/starts-with? (name fsymb) "."))          ;; it is field access or method
                    fsymb

                    ;; check if it is in our namespace
                    (contains? ns-vars fsymb)
                    (symbol (name ns-symb) (name fsymb))

                    ;; check if it is a namespaces symbol and can be expanded from aliases map
                    (and (namespace fsymb)
                         (contains? ns-alias-map fsymb-ns))
                    (expand-symbol-alias ns-alias-map fsymb-ns  fsymb)

                    ;; try to search in all required namespaces for a :refer-all
                    :else
                    (resolve-symbol all-ns-map ns-symb fsymb))]
      (if fq-symb
        (with-meta
          (conj (rest form) fq-symb)
          (meta form))
        (println "[Warning] couldn't fully qualify symbol for " {:fsymb fsymb
                                                                 :ns-symb ns-symb})))
    form))

(defn deep-form-facts [all-ns-map ns-symb form]
  (try
    (loop [zloc (z/of-string (str (with-meta form {})))
           facts []
           ctx {:namespace/name ns-symb}]
      (if (z/end? zloc)
        facts
        (let [{ffacts :facts fctx :ctx} (form-facts all-ns-map ctx (fully-qualify-form-first-symb all-ns-map
                                                                                                  ns-symb
                                                                                                  (z/sexpr zloc)))]
          (recur (z/find-next-tag zloc z/next :list)
                 (into facts ffacts)
                 (merge ctx fctx)))))
    (catch Exception e
      (prn "[Warning] couln't walk form " (with-meta form {}) "inside" ns-symb)
      (throw e))))

(defn namespace-forms-facts [all-ns-map ns-symb]
  (->> (:namespace/forms (get all-ns-map ns-symb))
       (mapcat (partial deep-form-facts all-ns-map ns-symb))))

(defn source-facts [all-ns-map]
  (let [all-ns-facts (mapcat namespace-facts (vals all-ns-map))
        all-ns-form-facts (mapcat (fn [[ns-symb _]] (namespace-forms-facts all-ns-map ns-symb)) all-ns-map)]
    (-> []
        (into all-ns-facts)
        (into all-ns-form-facts))))

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

  (def src-facts (source-facts all-ns))

  (deep-form-facts
   all-ns
   'clindex.indexer
   '(defn bla [x]
      (let [a x]
        (d/q (+ a 4)))))

  )
