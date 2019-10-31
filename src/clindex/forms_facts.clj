(ns clindex.forms-facts
  (:require [clindex.utils :as utils]
            [clojure.string :as str]
            [clojure.core.specs.alpha :as core-specs]
            [clojure.spec.alpha :as s]))

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
               :arity-n (map first args+body)))}))

(defmulti form-facts (fn [all-ns-map ctx form] (first form)))

(defn- defn-facts [ctx ns-name form macro?]
  (let [{:keys [fn-name docstring args]} (parse-fn-form form)]
    {:facts (let [fid (utils/function-id ns-name fn-name)
                  form-str (:form-str (meta form))
                  var-id (utils/var-id ns-name fn-name)]
              (when-not form-str
                (println "[Warning] not source-str for namespace" ns-name "at line" (:line (meta form)) "skipping"))

              (cond-> [[:db/add var-id :var/function fid]
                       [:db/add fid :function/source-form (vary-meta form dissoc :form-str)]]

                true (into (map (fn [arg-vec] [:db/add fid :function/args (str arg-vec)]) args))

                docstring
                (into [[:db/add var-id :var/docstring docstring]])

                form-str
                (into [[:db/add fid :function/source-str form-str]])

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

(defmethod form-facts 'defprotocol
  [all-ns-map ctx [_ pname & r]]
  (let [ns-name (:namespace/name ctx)
        var-id (utils/var-id ns-name pname)
        docstring (when (string? (first r)) (first r))
        proto-fns (if docstring (rest r) r)]
    {:facts (cond-> [[:db/add var-id :var/protocol? true]]
              docstring (into [[:db/add var-id :var/docstring docstring]])
              true      (into (->> proto-fns
                                   (filter list?)
                                   (mapcat (fn [[f-name]]
                                             (let [fid (utils/function-id ns-name f-name)]
                                               [[:db/add fid :function/proto-var var-id]
                                                [:db/add (utils/var-id ns-name f-name) :var/function fid]]))))))
     :ctx (merge ctx {:in-protocol pname})}))

(defn- defmulti-facts [all-ns-map ctx [_ var-name arg1 arg2]]
  (let [ns-name (:namespace/name ctx)
        multi-id (utils/multi-id ns-name var-name)
        var-id (utils/var-id ns-name var-name)]
    {:facts (into [[:db/add (utils/var-id ns-name var-name) :var/multi multi-id]]
                  (if (string? arg1)
                    ;; with doc
                    [[:db/add var-id   :var/docstring       arg1]
                     [:db/add multi-id :multi/dispatch-form arg2]]

                    ;; no doc
                    [[:db/add multi-id :multi/dispatch-form arg1]]))
     :ctx ctx}))

(defmethod form-facts 'clojure.core/defmulti [all-ns-map ctx form]
  (defmulti-facts all-ns-map ctx form))

(defmethod form-facts 'cljs.core/defmulti [all-ns-map ctx form]
  (defmulti-facts all-ns-map (:namespace/name ctx) form))

(defmethod form-facts 'clojure.core/defmethod [all-ns-map ctx [_ var-name dispatch-val :as form]]
  (let [ns-name (:namespace/name ctx)
        form-str (:form-str (meta form))
        multi-id (utils/multi-id ns-name var-name)
        method-id (utils/multimethod-id ns-name var-name dispatch-val)]
    {:facts (cond-> [[:db/add multi-id :multi/methods method-id]
                     ;; multimethods can dispatch on nil but we can't create a nil valued fact so
                     ;; lets use nil-value symbol
                     [:db/add method-id :multimethod/dispatch-val (or dispatch-val 'nil-value)]
                     [:db/add method-id :multimethod/source-form (vary-meta form dissoc :form-str)]]
              form-str (into [[:db/add method-id :multimethod/source-str form-str]]))
    :ctx ctx}))

(defmethod form-facts :default
  [all-ns-map ctx form]
  ;; (println "Analyzing form " form "with context " ctx " and meta " (meta form))
  {:facts []
   :ctx ctx})
