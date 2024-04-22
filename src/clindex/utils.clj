(ns clindex.utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip])
  (:import [java.io File]
           [java.util.jar JarFile]))

(defn normalize-path [file]
  (-> file
      .toURI
      .normalize
      .getRawPath))

(defn jar-full-path [jar-path file-path]
  (format "jar:file:%s!/%s" jar-path file-path))

(defn make-file
  ([path]
   {:full-path (normalize-path (io/file path))
    :content-url (io/as-url (File. path))})
  ([jar-path path]
   (let [full-path (jar-full-path jar-path path)]
     {:jar jar-path
      :path path
      :full-path full-path
      :content-url (io/as-url full-path)})))

(defn all-files [base-dir pred]
  (->> (file-seq (File. base-dir))
       (keep (fn [^File f]
               (when (.canRead f)
                 (let [full-path (.getAbsolutePath f)]
                   (when (pred full-path)
                     (make-file full-path))))))))

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
  (filter (fn [[op e a v :as f]]
            (if-not (and (#{:db/add :db/retract} op)
                         (int? e)
                         (keyword? a)
                         (not (nil? v)))
              (println "[Warning] fact check failed " f)
              f))
          tx-data))

(defn get-clojure-jar-path []
  (some (fn [jar-path]
          (when (str/includes? jar-path "org/clojure/clojure/")
            jar-path))
        (str/split (System/getProperty "java.class.path") #":")))

(defn sane-classpath? []
  (not (str/includes? (System/getProperty "java.class.path")
                      "org/clojure/tools.namespace")))

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

(defn multi-id [namespace-symb var-symb]
  (stable-id :multi namespace-symb var-symb))

(defn multimethod-id [namespace-symb var-symb dispatch-val]
  (stable-id :multimethod namespace-symb var-symb dispatch-val))

(defn fspec-alpha-id [namespace-symb var-symb]
  (stable-id :fspec-alpha namespace-symb var-symb))

(defn spec-alpha-id [namespace-symb spec-key]
  (stable-id :spec-alpha namespace-symb spec-key))

(defn rectangle-select
  "Given a vector of strings, assumes each string is a line and
  returns a string with the subregion
  between l1 and l2 lines and c1 and c2 columns.
  No c2 means to the end of the line."
  ([lines l1 l2 c1] (rectangle-select lines l1 l2 c1 nil))
  ([lines l1 l2 c1 c2]
   (->> lines
        (drop (dec l1))
        (take (inc (- l2 l1)))
        (map (fn [l]
               (let [m (count l)]
                 (try
                   (if c2
                     (subs l
                           (min m (dec c1))
                           (min m (dec c2)))
                     (subs l (min m (dec c1))))
                   (catch Exception e
                     (prn "PROBLEM " m l c1 c2 )
                     (throw e))))
               ))
        (str/join "\n"))))

(defn reloadable-namespaces
  "Returns the subsets of namespaces that can be reloaded. Basically whatever is not
  inside a jar file"
  [all-ns]
  (reduce-kv (fn [r ns-symb {:keys [:namespace/file-content-path] :as ns}]
               (if-not (str/starts-with? file-content-path "jar:file:")
                 (assoc r ns-symb ns)
                 r))
   {}
   all-ns))
