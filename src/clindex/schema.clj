(ns clindex.schema)

(def schema
  {
   ;;;;;;;;;;;;;;
   ;; Projects ;;
   ;;;;;;;;;;;;;;

   ;; A symbol with the project name for named dependencies or clindex/main-project for the project
   ;; being analyzed
   :project/name           {:db/cardinality :db.cardinality/one}

   ;; The project version as a string (only maven version now)
   :project/version        {:db/cardinality :db.cardinality/one}

   ;; A collection of references to other projects which this one depends on
   :project/depends        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}

   ;; A collection of references to namespaces this project contains
   :project/namespaces     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   ;;;;;;;;;;;
   ;; Files ;;
   ;;;;;;;;;;;

   ;; A string with the file name, can be a local file or a jar url
   :file/name              {:db/cardinality :db.cardinality/one}

   ;;;;;;;;;;;;;;;;
   ;; Namespaces ;;
   ;;;;;;;;;;;;;;;;

   ;; A symbol with the namespace name
   :namespace/name         {:db/cardinality :db.cardinality/one}

   ;; A reference to the file that contains this namespace declaration
   :namespace/file         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}

   ;; A collection of references to vars this namespace defines
   :namespace/vars         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   ;; Namespace documentation string
   :namespace/docstring    {:db/cardinality :db.cardinality/one}

   ;; A collection of references to other namespaces which this depends on
   :namespace/depends      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}

   ;;;;;;;;;;
   ;; Vars ;;
   ;;;;;;;;;;

   ;; A non namespaced symbol with the var name
   :var/name               {:db/cardinality :db.cardinality/one}

   ;; A integers containing the var definition coordinates
   :var/line               {:db/cardinality :db.cardinality/one}
   :var/column             {:db/cardinality :db.cardinality/one}
   :var/end-column         {:db/cardinality :db.cardinality/one}

   ;; True if the var is public in the namespace
   :var/public?            {:db/cardinality :db.cardinality/one}

   ;; A reference to function if this var is pointing to one
   :var/function           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/isComponent true}

   ;; A collection of references to var-ref, which are all the references pointing to this var
   :var/refs               {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   ;; True if the var is pointing to a protocol definition, like (defprotocol TheProtoVar ...)
   :var/protocol?          {:db/cardinality :db.cardinality/one}

   ;; A reference to the multimethod this var points to
   :var/multi              {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/isComponent true}

   ;; Var documentation
   :var/docstring          {:db/cardinality :db.cardinality/one}

   ;;;;;;;;;;;;;;;
   ;; Functions ;;
   ;;;;;;;;;;;;;;;

   ;; True if this function is a macro
   :function/macro?        {:db/cardinality :db.cardinality/one}

   ;; Function source form. It contains all the data the clojure reader adds (:line, :column, etc) plus for
   ;; each symbol inside, if it points to a var is has its :var/id
   :function/source-form   {:db/cardinality :db.cardinality/one}

   ;; Function source representation as it appears on the file, contains comments, newlines etc
   :function/source-str    {:db/cardinality :db.cardinality/one}

   ;; When this is a protocol function, it points to the protocol definition var
   :function/proto-var     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}

   ;; A collection of argument vectors as strings, it is a collection because fns can have multiple arities
   :function/args          {:db/cardinality :db.cardinality/many}

   ;;;;;;;;;;;;;;;;;;
   ;; Multimethods ;;
   ;;;;;;;;;;;;;;;;;;

   ;; The form used for dispatching
   :multi/dispatch-form    {:db/cardinality :db.cardinality/one}

   ;; A collection of references to multimethods that implement this defmulti
   :multi/methods          {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}

   ;; The dispatch value as it appears on the defmethod
   :multimethod/dispatch-val {:db/cardinality :db.cardinality/one}

   ;; The multimethod source form, same as :function/source-form but for the specific implementation
   :multimethod/source-form  {:db/cardinality :db.cardinality/one}

   ;; The multimethod source form as a string, same as :function/source-form but for the specific implementation
   :multimethod/source-str   {:db/cardinality :db.cardinality/one}

   ;;;;;;;;;;;;;;;;;;;;
   ;; Var references ;;
   ;;;;;;;;;;;;;;;;;;;;

   ;; A reference to the namespace in which this var-ref is found
   :var-ref/namespace      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}

   ;; The var reference coordinates
   :var-ref/line           {:db/cardinality :db.cardinality/one}
   :var-ref/column         {:db/cardinality :db.cardinality/one}
   :var-ref/end-column     {:db/cardinality :db.cardinality/one}

   ;; A reference to the function this var-ref is in
   :var-ref/in-function    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   })
