(ns clindex.schema)

(def schema
  {:project/name           {:db/cardinality :db.cardinality/one}
   :project/version        {:db/cardinality :db.cardinality/one}
   :project/depends        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :project/namespaces     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   :file/name              {:db/cardinality :db.cardinality/one}

   :namespace/name         {:db/cardinality :db.cardinality/one}
   :namespace/file         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :namespace/vars         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   :var/name               {:db/cardinality :db.cardinality/one}
   :var/line               {:db/cardinality :db.cardinality/one}
   :var/public?            {:db/cardinality :db.cardinality/one}
   :var/function           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/isComponent true}
   :var/refs               {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}
   :var/protocol?          {:db/cardinality :db.cardinality/one}
   :var/multi              {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/isComponent true}


   :function/macro?        {:db/cardinality :db.cardinality/one}
   :function/source-form   {:db/cardinality :db.cardinality/one}
   :function/source-str    {:db/cardinality :db.cardinality/one}
   :function/proto-var     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}

   :multi/dispatch-form    {:db/cardinality :db.cardinality/one}
   :multi/methods          {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   :multimethod/dispatch-val {:db/cardinality :db.cardinality/one}
   :multimethod/source-form  {:db/cardinality :db.cardinality/one}
   :multimethod/source-str   {:db/cardinality :db.cardinality/one}

   :var-ref/line           {:db/cardinality :db.cardinality/one}
   :var-ref/col            {:db/cardinality :db.cardinality/one}
   :var-ref/namespace      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :var-ref/in-function    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   })
