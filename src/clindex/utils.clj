(ns clindex.utils
  (:require [ike.cljj.file :as files]
            [ike.cljj.stream :as stream]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip])
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
                      (if (> (count (pr-str form)) 40)
                        (subs (pr-str form) 0 40)
                        (pr-str form))))))

(defmethod print-method java.net.URL [o w]
  (.write w (format "#url \"%s\"" (str o))))

(defn read-url [url]
  (java.net.URL. (str url)))

(defn print-file-lines-arround [path line-num]
  (println)
  (doseq [[i l] (->> (str/split-lines (slurp path))
                     (map vector (iterate inc 1))
                     (filter (fn [[i _]] (< (- line-num 3) i (+ line-num 3)))))]
    (println i " " l))
  (println))

;; TODO: improve this!
(defn code-zipper
  "Returns a zipper for nested sequences, given a root sequence"
  [root]
  (zip/zipper coll?
              seq
              (fn [node children] (with-meta children (meta node)))
              root))

(defn move-zipper-to-next [zloc pred]
  (loop [z (zip/next zloc)]
    (if (or (zip/end? z)
            (pred (zip/node z)))
      z
      (recur (zip/next z)))))

(defn check-facts [tx-data]
  (doseq [[_ _ _ v :as f] tx-data]
    (when (nil? v)
      (println "Error, nil valued fact " f))))

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

(defn var-ref-id [namespace-symb var-symb ref-ns-symb ref-line ref-col]
  (stable-id :var-ref namespace-symb var-symb ref-ns-symb ref-line ref-col))

(defn function-id [namespace-symb var-symb]
  (stable-id :function namespace-symb var-symb))
