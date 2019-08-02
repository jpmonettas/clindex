(ns clindex.utils
  (:require [ike.cljj.file :as files]
            [ike.cljj.stream :as stream]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.util.jar JarFile]))

(defn jar-full-path [jar-path file-path]
  (format "jar:file:%s!/%s" jar-path file-path))

(defn make-file
  ([path]
   {:full-path path
    :content-url (io/as-url (File. path))})
  ([jar-path path]
   (let [full-path (jar-full-path jar-path path)]
     {:jar jar-path
      :path path
      :full-path full-path
      :content-url (io/as-url full-path)})))

(defn all-files [base-dir pred]
  (with-open [childs (files/walk (files/as-path base-dir))]
    (->> (stream/stream-seq childs)
         (filter pred)
         (mapv (fn [p] (make-file (str p)))))))

(defn jar-files [jar-path pred]
  (->> (JarFile. jar-path)
       .entries
       enumeration-seq
       (filter #(pred (.getName %)))
       (map (fn [p]
              (make-file jar-path (.getName p))))))

(defmethod print-method :clindex/form [form ^java.io.Writer w]
  (let [form (with-meta form nil)]
    (.write w (format "%s...\n"
                      (if (> (count (pr-str form)) 20)
                        (subs (pr-str form) 0 20)
                        (pr-str form))))))

(defmethod print-method java.net.URL [o w]
  (.write w (format "#url \"%s\"" (str o))))

(defn read-url [url]
  (java.net.URL. (str url)))
