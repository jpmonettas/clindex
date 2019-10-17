(ns clindex.indexer
  (:require [datascript.core :as d]
            [clojure.zip :as zip]
            [clojure.string :as str]
            [clindex.utils :as utils]
            [clindex.forms-facts :refer [form-facts]]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as s]
            [clindex.specs]))

(defn- project-facts [{:keys [:project/name :project/dependencies :mvn/version] :as proj}]
  (let [proj-id (utils/project-id name)]
    (cond->> [[:db/add proj-id :project/name name]]
      version      (into [[:db/add proj-id :project/version version]])
      dependencies (into (mapv (fn [dep-symb]
                                 [:db/add proj-id :project/depends (utils/project-id dep-symb)])
                               dependencies)))))

(defn- files-facts [{:keys [:project/files] :as proj}]
  (->> files
       (mapv (fn [file]
               [:db/add (utils/file-id (:full-path file)) :file/name (:full-path file)]))))

(defn- namespace-facts [ns]
  (let [ns-id (utils/namespace-id (:namespace/name ns))
        vars-facts (fn [vs pub?]
                     (mapcat (fn [v]
                               (let [vid (utils/var-id (:namespace/name ns) v)
                                     vline (-> v meta :line)]
                                 (when (nil? vline)
                                   (println (format "[Warning], no line meta for %s/%s" (:namespace/name ns) v)))
                                 (cond-> [[:db/add vid :var/name v]
                                          [:db/add vid :var/public? pub?]
                                          [:db/add vid :var/namespace ns-id]
                                          [:db/add ns-id :namespace/vars vid]]
                                   vline (into [[:db/add vid :var/line vline]]))))
                             vs))
        facts (-> [[:db/add ns-id :namespace/name (:namespace/name ns)]
                   [:db/add (utils/project-id (:namespace/project ns)) :project/namespaces ns-id]
                   [:db/add ns-id :namespace/file (utils/file-id (:namespace/file-content-path ns))]]
                  (into (vars-facts (:namespace/public-vars ns) true))
                  (into (vars-facts (:namespace/private-vars ns) false))
                  (into (vars-facts (:namespace/macros ns) true)))]
    facts))

(defn- expand-symbol-alias [aliases-map current-ns-symb symb]
  (if-let [ns-symb (namespace symb)]
    (if-let [full-ns-symb (get aliases-map (symbol ns-symb))]
      (symbol (name full-ns-symb) (name symb))
      symb)
    symb))

(defn- resolve-symbol [all-ns-map ns-symb fsymb]
  (let [ns-requires (:namespace/dependencies (get all-ns-map ns-symb))]
    (some (fn [rns-symb]
            (let [{:keys [:namespace/public-vars :namespace/macros] :as rns} (get all-ns-map rns-symb)]
              (when (contains? (into public-vars macros) fsymb)
                (symbol (name (:namespace/name rns)) (name fsymb)))))
      ns-requires)))

(defn- function-call? [all-ns-map ns-symb fq-fsymb]
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

(defn- macro-call? [all-ns-map ns-symb fq-fsymb]
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

(defn- fully-qualify-symb [all-ns-map ns-symb symb]
  (let [ns (get all-ns-map ns-symb)
        ns-deps (:namespace/dependencies ns)
        ns-alias-map (:namespace/alias-map ns)
        ns-vars (-> #{}
                    (into (:namespace/public-vars ns))
                    (into (:namespace/private-vars ns))
                    (into (:namespace/macros ns)))
        symb-ns (when-let [s (namespace symb)]
                  (symbol s))
        fqs (cond

              ;; check OR
              (or (and symb-ns (contains? all-ns-map symb-ns)) ;; it is already fully qualified
                  (special-symbol? symb)                       ;; it is a special symbol
                  (str/starts-with? (name symb) "."))          ;; it is field access or method
              symb

              ;; check if it is in our namespace
              (contains? ns-vars symb)
              (symbol (name ns-symb) (name symb))

              ;; check if it is a namespaces symbol and can be expanded from aliases map
              (and (namespace symb)
                   (contains? ns-alias-map symb-ns))
              (expand-symbol-alias ns-alias-map symb-ns symb)

              ;; try to search in all required namespaces for a :refer-all
              :else
              (resolve-symbol all-ns-map ns-symb symb))]

    ;; transfer symbol meta
    (when fqs (with-meta fqs (meta symb)))))

(defn- fully-qualify-form-first-symb [all-ns-map ns-symb form]
  (if (symbol? (first form))
    (let [ns (get all-ns-map ns-symb)
          ns-alias-map (:namespace/alias-map ns)
          ns-vars (-> (:namespace/public-vars ns)
                      (into (:namespace/private-vars ns))
                      (into (:namespace/macros ns)))
          fsymb (first form)
          fq-symb (fully-qualify-symb all-ns-map ns-symb fsymb)]
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

(defn- all-vars
  "Return all the vars defined in all namespaces like [var-namespace var-name]"
  [all-ns-map]
  (->> (vals all-ns-map)
       (mapcat (fn [ns]
                 (let [ns-vars (-> (:namespace/public-vars ns)
                                   (into (:namespace/private-vars ns))
                                   (into (:namespace/macros ns)))]
                   (map (fn [v]
                          [(:namespace/name ns) v])
                        ns-vars))))
       (into #{})))

(defn- split-symb-namespace [fq-symb]
  (when fq-symb
    (->> ((juxt namespace name) fq-symb)
         (mapv #(when % (symbol %)))
         (into []))))

(defn- deep-form-facts [all-ns-map ns-symb form]
  (let [is-var (all-vars all-ns-map)]
    (loop [zloc (utils/code-zipper form)
           facts []
           ctx {:namespace/name ns-symb}]
      (if (zip/end? zloc)
        facts
        (let [token (zip/node zloc)]

          (cond
            ;; we are deep looking at a form
            ;; lets collect this form facts
            (list? token)
            (let [form' (fully-qualify-form-first-symb all-ns-map ns-symb token)
                  {ffacts :facts fctx :ctx} (form-facts all-ns-map ctx form')]
              (recur (utils/move-zipper-to-next zloc #(or (list? %) (symbol? %)))
                     (into facts ffacts)
                     (merge ctx fctx)))

            ;; we are deep looking at a symbol
            (symbol? token)
            (recur (utils/move-zipper-to-next zloc #(or (list? %) (symbol? %)))
                   (let [fq-symb (fully-qualify-symb all-ns-map ns-symb token)
                         var (split-symb-namespace fq-symb)]
                     (if (and (is-var var)
                              (not (:in-protocol ctx)))
                       (let [[var-ns var-symb] var
                             {:keys [line column]} (meta (zip/node zloc))
                             vr-id (utils/var-ref-id var-ns var-symb ns-symb line column)]
                         (into facts (cond-> [[:db/add (utils/var-id var-ns var-symb) :var/refs vr-id]
                                              [:db/add vr-id :var-ref/namespace (utils/namespace-id ns-symb)]
                                              [:db/add vr-id :var-ref/in-function (utils/function-id ns-symb (:in-function ctx))]]
                                       line (into [[:db/add vr-id :var-ref/line line]])
                                       column (into [[:db/add vr-id :var-ref/column column]]))))
                       facts))
                   ctx)

            :else
            (recur (utils/move-zipper-to-next zloc #(or (list? %) (symbol? %)))
                   facts
                   ctx)))))))

(defn- enhance-form-list [form-list form-str all-ns-map ns-symb]
  (let [form-list' (walk/postwalk
                    (fn [x]
                      (if (symbol? x)
                        (if-let [fqs (fully-qualify-symb all-ns-map ns-symb x)]
                          (let [ns (namespace fqs)]
                            (vary-meta x merge (when (and ns x)
                                                 {:var/id (utils/var-id (symbol ns) (symbol (name x)))})))
                          x)
                        x))
                    form-list)]
    (vary-meta form-list' merge (meta form-list) {:form-str form-str})))

(defn- namespace-forms-facts [all-ns-map ns-symb]
  (println "indexing " ns-symb)
  (->> (:namespace/forms (get all-ns-map ns-symb))
       (map (fn [{:keys [form-str form-list]}]
              (enhance-form-list form-list form-str all-ns-map ns-symb)))
       (mapcat (partial deep-form-facts all-ns-map ns-symb))))


(s/fdef namespace-full-facts
  :args (s/cat :all-ns-map :scanner/namespaces
               :ns-symb :namespace/name)
  :ret (s/coll-of :datomic/fact))

(defn namespace-full-facts [all-ns-map ns-symb]
  (into (namespace-facts (get all-ns-map ns-symb))
        (namespace-forms-facts all-ns-map ns-symb)))

;; (defn- source-facts [all-ns-map]
;;   (let [all-ns-facts (mapcat namespace-facts (vals all-ns-map))
;;         all-ns-form-facts (mapcat (fn [[ns-symb _]] (namespace-forms-facts all-ns-map ns-symb)) all-ns-map)]
;;     (-> []
;;         (into all-ns-facts)
;;         (into all-ns-form-facts))))


(s/fdef all-facts
    :args (s/cat :m (s/keys :req-un [:scanner/projects
                                     :scanner/namespaces]))
    :ret (s/coll-of :datomic/fact))

(defn all-facts [{:keys [projects namespaces]}]
  (let [all-projs-facts (mapcat project-facts (vals projects))
        all-files-facts (mapcat files-facts (vals projects))
        all-source-facts (mapcat (fn [[ns-symb _]] (namespace-full-facts namespaces ns-symb)) namespaces)]
    (-> []
        (into all-projs-facts)
        (into all-files-facts)
        (into all-source-facts))))

(comment

  (do (require '[clindex.scanner :as scanner])
      (require '[clojure.tools.namespace.find :as ctnf])

      (def all-projs (scanner/all-projects "/home/jmonetta/my-projects/clindex"
                                           {:platform ctnf/clj}))

      (def main-project {scanner/main-project-symb (get all-projs scanner/main-project-symb)})
      (def all-ns (scanner/all-namespaces
                   all-projs #_main-project
                   {:platform ctnf/clj #_ctnf/cljs})))

  (do (require '[clindex.scanner :as scanner])
      (require '[clojure.tools.namespace.find :as ctnf])

      (def all-projs (scanner/all-projects "/home/jmonetta/my-projects/district0x/memefactory"
                                           {:platform ctnf/cljs}))

      (def all-ns (scanner/all-namespaces all-projs {:platform ctnf/cljs #_ctnf/cljs})))

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
   '(defn bla [x]
      (map namespace-facts 1 2)
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
