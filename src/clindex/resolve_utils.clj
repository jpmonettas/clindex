(ns clindex.resolve-utils
  (:require [clojure.string :as str]))

(defn- expand-symbol-alias [aliases-map current-ns-symb symb]
  (if-let [ns-symb (namespace symb)]
    (if-let [full-ns-symb (get aliases-map (symbol ns-symb))]
      (symbol (name full-ns-symb) (name symb))
      symb)
    symb))

(defn- resolve-symbol [all-ns-map ns-symb fsymb]
  (let [ns-requires (:namespace/dependencies (get all-ns-map ns-symb))]
    (some (fn [rns-symb]
            (let [{:keys [:namespace/public-vars :namespace/macros] :as rns} (get all-ns-map rns-symb)]
              (when (contains? (into public-vars macros) fsymb)
                (symbol (name (:namespace/name rns)) (name fsymb)))))
      ns-requires)))

(defn- function-call? [all-ns-map ns-symb fq-fsymb]
  (let [fns-symb (when-let [n (namespace fq-fsymb)]
                   (symbol n))
        fsymb (symbol (name fq-fsymb))]
    (if (= fns-symb ns-symb)
      ;; it is referring to current namespace so lets check our fns
      (let [ns (get all-ns-map ns-symb)]
        (contains? (into (:namespace/public-vars ns)
                         (:namespace/private-vars ns))
                   fsymb))

      ;; it is calling other ns maybe
      (let [ns (get all-ns-map fns-symb)]
        (contains? (:namespace/public-vars ns) fsymb)))))

(defn- macro-call? [all-ns-map ns-symb fq-fsymb]
  (let [fns-symb (when-let [n (namespace fq-fsymb)]
                   (symbol n))
        fsymb (symbol (name fq-fsymb))]
    (if (= fns-symb ns-symb)
      ;; it is referring to current namespace so lets check our fns
      (let [ns (get all-ns-map ns-symb)]
        (contains? (:namespace/macros ns) fsymb))

      ;; it is calling other ns maybe
      (let [ns (get all-ns-map fns-symb)]
        (contains? (:namespace/macros ns) fsymb)))))

(defn fully-qualify-symb [all-ns-map ns-symb symb]
  (let [ns (get all-ns-map ns-symb)
        ns-deps (:namespace/dependencies ns)
        ns-alias-map (:namespace/alias-map ns)
        ns-vars (-> #{}
                    (into (:namespace/public-vars ns))
                    (into (:namespace/private-vars ns))
                    (into (:namespace/macros ns)))
        symb-ns (when-let [s (namespace symb)]
                  (symbol s))
        fqs (cond

              ;; check OR
              (or (and symb-ns (contains? all-ns-map symb-ns)) ;; it is already fully qualified
                  (special-symbol? symb)                       ;; it is a special symbol
                  (str/starts-with? (name symb) "."))          ;; it is field access or method
              symb

              ;; check if it is in our namespace
              (contains? ns-vars symb)
              (symbol (name ns-symb) (name symb))

              ;; check if it is a namespaces symbol and can be expanded from aliases map
              (and (namespace symb)
                   (contains? ns-alias-map symb-ns))
              (expand-symbol-alias ns-alias-map symb-ns symb)

              ;; try to search in all required namespaces for a :refer-all
              :else
              (resolve-symbol all-ns-map ns-symb symb))]

    ;; transfer symbol meta
    (when fqs (with-meta fqs (meta symb)))))

(defn fully-qualify-form-first-symb [all-ns-map ns-symb form]
  (if (symbol? (first form))
    (let [ns (get all-ns-map ns-symb)
          ns-alias-map (:namespace/alias-map ns)
          ns-vars (-> (:namespace/public-vars ns)
                      (into (:namespace/private-vars ns))
                      (into (:namespace/macros ns)))
          fsymb (first form)
          fq-symb (fully-qualify-symb all-ns-map ns-symb fsymb)]
      (if fq-symb
        (with-meta
          (conj (rest form) fq-symb)
          (cond-> (meta form)
            (function-call? all-ns-map ns-symb fq-symb) (assoc :fn-call?    true)
            (macro-call? all-ns-map ns-symb fq-symb)    (assoc :macro-call? true)))
        (do
          #_(println "[Warning] couldn't fully qualify symbol for " {:fsymb fsymb
                                                                     :ns-symb ns-symb})
          ;; if we couldn't resolve the symbol lets leave it as it is
          ;; stuff like defprotoco, defrecord or protocol forms will be here
          form)))
    form))

(defn all-vars
  "Return all the vars defined in all namespaces like [var-namespace var-name]"
  [all-ns-map]
  (->> (vals all-ns-map)
       (mapcat (fn [ns]
                 (let [ns-vars (-> (:namespace/public-vars ns)
                                   (into (:namespace/private-vars ns))
                                   (into (:namespace/macros ns)))]
                   (map (fn [v]
                          [(:namespace/name ns) v])
                        ns-vars))))
       (into #{})))

(defn split-symb-namespace [fq-symb]
  (when fq-symb
    (->> ((juxt namespace name) fq-symb)
         (mapv #(when % (symbol %)))
         (into []))))
