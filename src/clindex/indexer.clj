(ns clindex.indexer
  (:require [datascript.core :as d]
            [rewrite-clj.zip :as z]
            [clojure.string :as str]))

(def db-conn (d/create-conn {:project/name       {:db/cardinality :db.cardinality/one}
                             :project/depends    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                             :file/name          {:db/cardinality :db.cardinality/one}
                             :namespace/name     {:db/cardinality :db.cardinality/one}
                             :namespace/project  {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :namespace/file     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :var/name           {:db/cardinality :db.cardinality/one}
                             :var/line           {:db/cardinality :db.cardinality/one}
                             :var/namespace      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :var/public?        {:db/cardinality :db.cardinality/one}
                             :function/var       {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :function/namespace {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                             :function/calls     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                             :function/macro?    {:db/cardinality :db.cardinality/one}
                             }))

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

(defn function-id [namespace-symb var-symb]
  (stable-id :function namespace-symb var-symb))


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

(defn function-call? [all-ns-map ns-symb fq-fsymb]
  (let [fns-symb (when-let [n (namespace fq-fsymb)]
                   (symbol n))
        fsymb (symbol (name fq-fsymb))]
    (if (= fns-symb ns-symb)
      ;; it is referring to current namespace so lets check our fns
      (let [ns (get all-ns-map ns-symb)]
        (contains? (into (:namespace/public-vars ns)
                         (:namespace/private-vars ns))
                   fsymb))

      ;; it is calling other ns maybe
      (let [ns (get all-ns-map fns-symb)]
        (contains? (:namespace/public-vars ns) fsymb)))))

(defn macro-call? [all-ns-map ns-symb fq-fsymb]
  (let [fns-symb (when-let [n (namespace fq-fsymb)]
                   (symbol n))
        fsymb (symbol (name fq-fsymb))]
    (if (= fns-symb ns-symb)
      ;; it is referring to current namespace so lets check our fns
      (let [ns (get all-ns-map ns-symb)]
        (contains? (:namespace/macros ns) fsymb))

      ;; it is calling other ns maybe
      (let [ns (get all-ns-map fns-symb)]
        (contains? (:namespace/macros ns) fsymb)))))

(defmulti form-facts (fn [all-ns-map ctx form] (first form)))

(defn defn-facts [ctx ns-name fname macro?]
  {:facts (let [fid (function-id ns-name fname)]
            (cond-> [[:db/add fid :function/var (var-id ns-name fname)]
                     [:db/add fid :function/namespace (namespace-id ns-name)]]
              macro? (into [[:db/add fid :funciton/macro? true]])))
   :ctx (merge ctx {:in-function fname})})

(defmethod form-facts 'clojure.core/defn
  [all-ns-map {:keys [:namespace/name] :as ctx} [_ fname]]
  (defn-facts ctx name fname false))

(defmethod form-facts 'clojure.core/defn-
  [all-ns-map ctx [_ fname]]
  (defn-facts ctx name fname false))

(defmethod form-facts 'clojure.core/defmacro
  [all-ns-map ctx [_ fname]]
  (defn-facts ctx name fname true))

(defmethod form-facts 'defprotocol
  [all-ns-map ctx [_ pname]]
  {:facts []
   :ctx (merge ctx {:in-protocol pname})})

(defmethod form-facts :default
  [all-ns-map ctx form]
  #_(println "Analyzing form " form "with context " ctx " and meta " (meta form))
  (let [{:keys [fn-call? macro-call?]} (meta form)
        {in-function :in-function ns-name :namespace/name} ctx
        facts (cond-> []
                fn-call? (into (let [in-fn-id (function-id ns-name in-function)
                                     fn-call-fq-symb (first form)
                                     fn-call-symb-ns (when-let [n (namespace fn-call-fq-symb)]
                                                       (symbol n))
                                     fn-call-symb (symbol (name fn-call-fq-symb))
                                     fn-call-id (function-id fn-call-symb-ns fn-call-symb)]
                                 [[:db/add in-fn-id :function/calls fn-call-id]
                                  [:db/add fn-call-id :function/var (var-id fn-call-symb-ns fn-call-symb)]])))]
    {:facts facts
     :ctx ctx}))

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
          (cond-> (meta form)
            (function-call? all-ns-map ns-symb fq-symb) (assoc :fn-call?    true)
            (macro-call? all-ns-map ns-symb fq-symb)    (assoc :macro-call? true)))
        (do
          #_(println "[Warning] couldn't fully qualify symbol for " {:fsymb fsymb
                                                                     :ns-symb ns-symb})
          ;; if we couldn't resolve the symbol lets leave it as it is
          ;; stuff like defprotoco, defrecord or protocol forms will be here
          form)))
    form))

(defn deep-form-facts [all-ns-map ns-symb form]
  (try
    (loop [zloc (z/of-string (str (with-meta form {})))
           facts []
           ctx {:namespace/name ns-symb}]
      (if (or (z/end? zloc) (not (and (list? (z/sexpr zloc))
                                      (not-empty (z/sexpr zloc)))))
        facts
        (let [ form' (fully-qualify-form-first-symb all-ns-map ns-symb (z/sexpr zloc))
              {ffacts :facts fctx :ctx} (form-facts all-ns-map ctx form')]
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

  (deep-form-facts
   all-ns
   'clindex.indexer
   '(defprotocol  bla [x]
      (let [a x]
        (d/q (+ a 4)))))

  (fully-qualify-form-first-symb
   all-ns-map
   'clojure.data.xml.jvm.emit
   '(ns clojure.data.xml.jvm.emit
      "JVM implementation of the emitter details"
      {:author "Herwig Hochleitner"}
      (:require (clojure.data.xml [name :refer [qname-uri qname-local separate-xmlns gen-prefix *gen-prefix-counter*]] [pu-map :as pu] [protocols :refer [AsXmlString xml-str]] [impl :refer [extend-protocol-fns b64-encode compile-if]] event) [clojure.string :as str])
      (:import (java.io OutputStreamWriter Writer StringWriter) (java.nio.charset Charset) (java.util.logging Logger Level) (javax.xml.namespace NamespaceContext QName) (javax.xml.stream XMLStreamWriter XMLOutputFactory) (javax.xml.transform OutputKeys Transformer TransformerFactory) (clojure.data.xml.event StartElementEvent EmptyElementEvent EndElementEvent CharsEvent CDataEvent CommentEvent QNameEvent) (clojure.lang BigInt) (java.net URI URL) (java.util Date) (java.text DateFormat SimpleDateFormat))))

  (fully-qualify-form-first-symb
   all-ns-map
   'clojure.data.xml.jvm.emit
   '(clojure.data.xml [name :refer [qname-uri qname-local separate-xmlns gen-prefix *gen-prefix-counter*]] [pu-map :as pu] [protocols :refer [AsXmlString xml-str]] [impl :refer [extend-protocol-fns b64-encode compile-if]] event))
  )
