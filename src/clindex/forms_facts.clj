(ns clindex.forms-facts
  (:require [clindex.utils :as utils]
            [clojure.string :as str]))

(defmulti form-facts (fn [all-ns-map ctx form] (first form)))

(defn- defn-facts [ctx ns-name [_ fname :as form] macro?]
  {:facts (let [fid (utils/function-id ns-name fname)
                form-str (:form-str (meta form))
                var-id (utils/var-id ns-name fname)]
            (when-not form-str
              (println "[Warning] not source-str for namespace" ns-name "at line" (:line (meta form)) "skipping"))

            (cond-> [[:db/add var-id :var/function fid]
                     [:db/add fid :function/source-form (vary-meta form dissoc :form-str)]]

              form-str
              (into [[:db/add fid :function/source-str form-str]])

              macro?
              (into [[:db/add fid :function/macro? true]])))
   :ctx (merge ctx {:in-function fname})})

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
        var-id (utils/var-id ns-name pname)]
    {:facts (-> [[:db/add var-id :var/protocol? true]]
                (into (->> r
                           (filter list?)
                           (map (fn [[f-name]]
                                  [:db/add (utils/function-id ns-name f-name) :function/proto-var var-id])))))
    :ctx (merge ctx {:in-protocol pname})}))

(defmethod form-facts :default
  [all-ns-map ctx form]
  ;; (println "Analyzing form " form "with context " ctx " and meta " (meta form))
  {:facts []
   :ctx ctx})
