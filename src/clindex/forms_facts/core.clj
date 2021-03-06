(ns clindex.forms-facts.core
  (:require [clindex.utils :as utils]
            [clojure.string :as str]
            [clojure.core.specs.alpha :as core-specs]
            [clojure.spec.alpha :as s]
            [clindex.resolve-utils :as resolve-utils]))

(defn- parse-fn-form [form]
  (let [{:keys [fn-name docstring meta fn-tail]} (s/conform ::core-specs/defn-args (rest form))
        arity (first fn-tail)]
    {:fn-name fn-name
     :docstring docstring
     :args (let [args+body (cond-> form
                             true rest     ;; drop defn
                             true rest     ;; drop name
                             docstring rest ;; drop docstring
                             meta      rest ;; drop meta
                             )]
             (case arity
               :arity-1 (list (first args+body))
               :arity-n (map first args+body)
               (do
                 (println "[Warning] unknown arity for " form)
                 [[]])))}))

(defmulti form-facts (fn [all-ns-map ctx form] (first form)))

(defn- defn-facts [ctx ns-name form macro?]
  (let [{:keys [fn-name docstring args]} (parse-fn-form form)]
    {:facts (let [fid (utils/function-id ns-name fn-name)
                  form-str (:form-str (meta form))
                  var-id (utils/var-id ns-name fn-name)]
              (when-not form-str
                (println "[Warning] not source-str for namespace" ns-name "at line" (:line (meta form)) "skipping"))

              (cond-> [[:db/add var-id :var/function fid]
                       [:db/add fid :source/form (vary-meta form dissoc :form-str)]]

                true (into (map (fn [arg-vec] [:db/add fid :function/args (str arg-vec)]) args))

                docstring
                (into [[:db/add var-id :var/docstring docstring]])

                form-str
                (into [[:db/add fid :source/str form-str]])

                macro?
                (into [[:db/add fid :function/macro? true]])))
     :ctx (merge ctx {:in-function fn-name})}))

(defmethod form-facts 'clojure.core/defn [all-ns-map {:keys [:namespace/name] :as ctx} form]
  (defn-facts ctx name form false))

(defmethod form-facts 'clojure.core/defn- [all-ns-map {:keys [:namespace/name] :as ctx} form]
  (defn-facts ctx name form false))

(defmethod form-facts 'clojure.core/defmacro [all-ns-map {:keys [:namespace/name] :as ctx} form]
  (defn-facts ctx name form true))

(defmethod form-facts 'cljs.core/defn [all-ns-map {:keys [:namespace/name] :as ctx} form]
  (defn-facts ctx name form false))

(defmethod form-facts 'cljs.core/defn- [all-ns-map {:keys [:namespace/name] :as ctx} form]
  (defn-facts ctx name form false))

(defmethod form-facts 'cljs.core/defmacro [all-ns-map {:keys [:namespace/name] :as ctx} form]
  (defn-facts ctx name form true))

(defn- defprotocol-facts [all-ns-map ctx [_ pname & r :as form]]
  (let [ns-name (:namespace/name ctx)
        var-id (utils/var-id ns-name pname)
        docstring (when (string? (first r)) (first r))
        proto-fns (if docstring (rest r) r)]
    {:facts (cond-> [[:db/add var-id :var/protocol? true]]
              docstring (into [[:db/add var-id :var/docstring docstring]])
              true      (into (->> proto-fns
                                   (filter list?)
                                   (mapcat (fn [[f-name args-vec]]
                                             (let [fid (utils/function-id ns-name f-name)
                                                   fvarid (utils/var-id ns-name f-name)]
                                               [[:db/add fvarid :var/function fid]
                                                [:db/add fid :function/proto-var var-id]
                                                [:db/add fid :function/args (str args-vec)]]))))))
     :ctx (merge ctx {:in-protocol pname})}))

(defmethod form-facts 'defprotocol
  [all-ns-map ctx form]
  (defprotocol-facts all-ns-map ctx form))

(defmethod form-facts 'cljs.core/defprotocol
  [all-ns-map ctx form]
  (defprotocol-facts all-ns-map ctx form))

(defn- defmulti-facts [all-ns-map ctx [_ var-name arg1 arg2 :as form]]
  (let [ns-name (:namespace/name ctx)
        multi-id (utils/multi-id ns-name var-name)
        var-id (utils/var-id ns-name var-name)
        dispatch-form (if (string? arg1) arg2 arg1)]

    (when-not dispatch-form
      (prn "[Warning] no dispatch form for" form " inside " ns-name))

    {:facts (into [[:db/add (utils/var-id ns-name var-name) :var/multi multi-id]]
                  (if (string? arg1)
                    ;; with doc
                    [[:db/add var-id   :var/docstring       arg1]
                     [:db/add multi-id :multi/dispatch-form dispatch-form]]

                    ;; no doc
                    [[:db/add multi-id :multi/dispatch-form dispatch-form]]))
     :ctx ctx}))

(defmethod form-facts 'clojure.core/defmulti [all-ns-map ctx form]
  (defmulti-facts all-ns-map ctx form))

(defmethod form-facts 'cljs.core/defmulti [all-ns-map ctx form]
  (defmulti-facts all-ns-map ctx form))

(defn- defmethod-facts [all-ns-map ctx [_ var-name dispatch-val :as form]]
  (let [current-ns-name (:namespace/name ctx)
        fqvar (resolve-utils/fully-qualify-symb all-ns-map current-ns-name var-name)]
    (if (and fqvar (namespace fqvar))
      (let [var-ns (namespace fqvar)
            form-str (:form-str (meta form))
            multi-id (utils/multi-id (symbol var-ns) (symbol (name fqvar)))
            method-id (utils/multimethod-id current-ns-name var-name dispatch-val)]
        {:facts (cond-> [[:db/add multi-id :multi/methods method-id]
                         ;; multimethods can dispatch on nil but we can't create a nil valued fact so
                         ;; lets use nil-value symbol
                         [:db/add method-id :multimethod/dispatch-val (or dispatch-val 'nil-value)]
                         [:db/add method-id :source/form (vary-meta form dissoc :form-str)]]
                  form-str (into [[:db/add method-id :source/str form-str]]))
         :ctx ctx})

      (do
        (println "[Warning] Couldn't resolve the ns for" var-name "inside" current-ns-name "when parsing defmethod form" form)
        {:facts []
         :ctx ctx}))))

(defmethod form-facts 'clojure.core/defmethod [all-ns-map ctx form]
  (defmethod-facts all-ns-map ctx form))

(defmethod form-facts 'cljs.core/defmethod [all-ns-map ctx form]
  (defmethod-facts all-ns-map ctx form))

(defn- spec-alpha-fdef-facts [all-ns-map ctx [_ f-name :as form]]
  (let [fqfn (resolve-utils/fully-qualify-symb all-ns-map (:namespace/name ctx) f-name)]
    (if (and fqfn (namespace fqfn))
      (let [fqfn-ns (namespace fqfn)
            f-id (utils/function-id (symbol fqfn-ns) (symbol (name fqfn)))
            fspec-id (utils/fspec-alpha-id (:namespace/name ctx) (symbol (name fqfn)))
            ns-id (utils/namespace-id (:namespace/name ctx))
            source-form (vary-meta form dissoc :form-str)]
        {:facts [[:db/add ns-id :namespace/fspecs-alpha fspec-id]
                 [:db/add f-id :function/spec.alpha fspec-id]
                 [:db/add fspec-id :source/form source-form]]
         :ctx ctx})

      (do
        (println "[Warning] Couldn't resolve the ns for" f-name "inside" (:namespace/name ctx) "when parsing spec form" form)
        {:facts []
         :ctx ctx}))))

(defmethod form-facts 'clojure.spec.alpha/fdef [all-ns-map ctx form]
  (spec-alpha-fdef-facts all-ns-map ctx form))

(defmethod form-facts 'cljs.spec.alpha/fdef [all-ns-map ctx form]
  (spec-alpha-fdef-facts all-ns-map ctx form))

(defn- spec-alpha-def-facts [all-ns-map ctx [_ spec-key :as form]]
  (let [spec-id (utils/spec-alpha-id (:namespace/name ctx) spec-key)
        ns-id (utils/namespace-id (:namespace/name ctx))
        source-form (vary-meta form dissoc :form-str)]
    {:facts [[:db/add ns-id :namespace/specs-alpha spec-id]
             [:db/add spec-id :spec.alpha/key spec-key]
             [:db/add spec-id :source/form source-form]]
     :ctx ctx}))

(defmethod form-facts 'clojure.spec.alpha/def [all-ns-map ctx form]
  (spec-alpha-def-facts all-ns-map ctx form))

(defmethod form-facts 'cljs.spec.alpha/def [all-ns-map ctx form]
  (spec-alpha-def-facts all-ns-map ctx form))

(defmethod form-facts :default
  [all-ns-map ctx form]
  ;; (println "Analyzing form " form "with context " ctx " and meta " (meta form))
  {:facts []
   :ctx ctx})
