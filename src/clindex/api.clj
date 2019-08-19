(ns clindex.api
  (:require [clindex.scanner :as scanner]
            [clindex.indexer :as indexer]
            [datascript.core :as d]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ctnf]
            [clindex.schema :refer [schema]]
            [clindex.utils :as utils]))

(def db-conns (atom {}))



(defn index-project!
  ""
  [base-dir {:keys [platforms extra-schema]}]
  (doseq [p platforms]
    (swap! db-conns assoc p (d/create-conn (merge schema extra-schema)))
    (let [tx-data (-> (scanner/scan-all base-dir {:platform (case p
                                                              :clj  ctnf/clj
                                                              :cljs ctnf/cljs)})
                      (indexer/all-facts))]
      (utils/check-facts tx-data)
      (println (format "About to transact %d facts" (count tx-data)))
      (d/transact! (get @db-conns p) tx-data))))

(defn db
  ""
  [platform]
  @(get @db-conns platform))
