(ns clindex.forms-facts
  (:require [clindex.utils :as utils]))

(defmulti form-facts (fn [all-ns-map ctx form] (first form)))

(defn- defn-facts [ctx ns-name fname macro?]
  {:facts (let [fid (utils/function-id ns-name fname)]
            (cond-> [[:db/add fid :function/var (utils/var-id ns-name fname)]
                     [:db/add fid :function/namespace (utils/namespace-id ns-name)]]
              macro? (into [[:db/add fid :funciton/macro? true]])))
   :ctx (merge ctx {:in-function fname})})

(defmethod form-facts 'clojure.core/defn [all-ns-map {:keys [:namespace/name] :as ctx} [_ fname]]
  (defn-facts ctx name fname false))

(defmethod form-facts 'clojure.core/defn- [all-ns-map ctx [_ fname]]
  (defn-facts ctx name fname false))

(defmethod form-facts 'clojure.core/defmacro [all-ns-map ctx [_ fname]]
  (defn-facts ctx name fname true))

(defmethod form-facts 'cljs.core/defn [all-ns-map {:keys [:namespace/name] :as ctx} [_ fname]]
  (defn-facts ctx name fname false))

(defmethod form-facts 'cljs.core/defn- [all-ns-map ctx [_ fname]]
  (defn-facts ctx name fname false))

(defmethod form-facts 'cljs.core/defmacro [all-ns-map ctx [_ fname]]
  (defn-facts ctx name fname true))

(defmethod form-facts 'defprotocol
  [all-ns-map ctx [_ pname]]
  {:facts []
   :ctx (merge ctx {:in-protocol pname})})

(defmethod form-facts :default
  [all-ns-map ctx form]
  ;; (println "Analyzing form " form "with context " ctx " and meta " (meta form))
  {:facts []
   :ctx ctx})
