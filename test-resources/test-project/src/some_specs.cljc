(ns some-specs
  (:require [clojure.spec.alpha :as s]
            [dep-code :as dc]))

(s/def :person/name string?)

(s/fdef dc/function-with-doc
  :args (s/cat :args (s/coll-of :person/name))
  :ret boolean?)
