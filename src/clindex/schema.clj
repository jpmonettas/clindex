(ns clindex.schema)

(def schema
  {:project/name           {:db/cardinality :db.cardinality/one}
   :project/version        {:db/cardinality :db.cardinality/one}
   :project/depends        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :file/name              {:db/cardinality :db.cardinality/one}
   :namespace/name         {:db/cardinality :db.cardinality/one}
   :namespace/project      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :namespace/file         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :var/name               {:db/cardinality :db.cardinality/one}
   :var/line               {:db/cardinality :db.cardinality/one}
   :var/namespace          {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :var/public?            {:db/cardinality :db.cardinality/one}
   :function/var           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :function/namespace     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :function/macro?        {:db/cardinality :db.cardinality/one}
   :function/source-form   {:db/cardinality :db.cardinality/one}
   :function/source-str    {:db/cardinality :db.cardinality/one}
   :var-ref/line           {:db/cardinality :db.cardinality/one}
   :var-ref/col            {:db/cardinality :db.cardinality/one}
   :var-ref/var            {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :var-ref/namespace      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :var-ref/in-function    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   })
