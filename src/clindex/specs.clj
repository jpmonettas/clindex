(ns clindex.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.namespace.find :as ns-find]))

(s/def :file/path string?)
(s/def :datomic/fact any?)

;;;;;;;;;;;;;;;;;;;;;;
;; Scanner projects ;;
;;;;;;;;;;;;;;;;;;;;;;

(s/def :project/name symbol?)
(s/def :project/files any?) ;; TODO figure this out
(s/def :mvn/version string?)
(s/def :project/paths (s/coll-of string?))
(s/def :project/dependents (s/coll-of :project/name))
(s/def :project/dependencies (s/coll-of :project/name))

(s/def :scanner/project (s/keys :opt [:project/name
                                      :project/dependencies
                                      :project/files
                                      :mvn/version]
                                :opt-un [:project/paths
                                         :project/dependents]))
(s/def :scanner/projects (s/map-of :project/name :scanner/project))


;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scanner namespaces  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :namespace/name symbol?)
(s/def :namespace/project :project/name)
(s/def :var/name symbol?)
(s/def :namespace/public-vars (s/coll-of :var/name))
(s/def :namespace/private-vars (s/coll-of :var/name))
(s/def :namespace/macros (s/coll-of :var/name))
(s/def :namespace/dependencies (s/coll-of :namespace/name))
(s/def :namespace/file-content-path string?) ;; TODO figure this out
(s/def ::form-list any?)
(s/def ::form-str string?)
(s/def :namespace/forms (s/coll-of (s/keys :req-un [::form-list]
                                           :opt-un [::form-str])))
(s/def :namespace/alias-map (s/map-of symbol? :namespace/name))
(s/def :scanner/namespace (s/keys :req [:namespace/project
                                        :namespace/name

                                        :namespace/public-vars
                                        :namespace/private-vars
                                        :namespace/macros
                                        :namespace/dependencies
                                        :namespace/file-content-path
                                        :namespace/forms
                                        :namespace/alias-map]))
(s/def :scanner/namespaces (s/map-of :namespace/name :scanner/namespace))

(s/def :scanner/platform #{ns-find/clj ns-find/cljs})


(s/def :clindex/platform #{:clj :cljs})
(s/def :clindex/platforms (s/coll-of :clindex/platform))
(s/def :datascript/extra-schema map?)
