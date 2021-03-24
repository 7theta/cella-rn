;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns cella.connection
  (:require ["@nozbe/watermelondb" :as db :refer [appSchema tableSchema Database Model Q]]
            ["@nozbe/watermelondb/adapters/sqlite" :default SQLiteAdapter]
            [clojure.walk :refer [postwalk]]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [cljs.reader :refer [read-string]]
            [cljs.core.async :refer [go chan <! put! close!]]
            [tempus.core :as t]
            [inflections.core :as inflections]
            [utilis.map :refer [compact map-vals]]
            [utilis.js :as j]
            [utilis.fn :refer [fsafe]]
            [integrant.core :as ig]
            [clojure.string :as st]
            [goog.object :as gobj]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer [reg-sub-raw]]))

;;; Declarations

(declare model-class schema->columns ->sql sql-> encode decode cella-transformer with-registry date-schema connect
         copy-fn check-js-support process-queue)

(defn date?
  [x]
  (instance? t/DateTime x))

;;; Integrant

(defmethod ig/init-key :cella/connection
  [_ {:keys [db-name schema-version tables] :as opts}]
  (try (doseq [{:keys [message]} (remove :supported (check-js-support))]
         (js/console.warn message))
       (let [db (connect opts)]
         (reg-sub-raw
          :cella/queue-size
          (fn []
            (reaction
             (count @(j/get db :action-queue)))))
         db)
       (catch js/Error e
         (js/console.warn e)
         (throw e))))

(defmethod ig/halt-key! :cella/connection
  [_ connection])

;;; Public

(defn connect
  [{:keys [db-name schema-version tables]
    :or {schema-version 1}}]
  (let [tables (doall
                (map (fn [table]
                       (update table :schema
                               m/schema {:registry (merge (m/default-schemas) {:date (date-schema)})}))
                     tables))
        schema (appSchema
                (clj->js
                 {:version schema-version
                  :tables (map (fn [{:keys [name schema]}]
                                 (tableSchema
                                  (clj->js
                                   {:name (->sql name)
                                    :columns (schema->columns schema)})))
                               tables)}))
        adapter (new SQLiteAdapter
                     (clj->js
                      {:schema schema
                       :dbName (->sql db-name)
                       :synchronous false}))
        model-classes (doall (map (comp model-class ->sql :name) tables))]
    (doto (new Database
               (clj->js
                {:adapter adapter
                 :modelClasses model-classes
                 :actionsEnabled true}))
      (j/assoc! :schemas (->> tables
                              (map (fn [{:keys [name schema]}]
                                     (let [tx (mt/transformer mt/json-transformer cella-transformer)]
                                       [name {:schema schema
                                              :schema-keys (->> (m/form schema)
                                                                (rest)
                                                                (map (fn [[k & args]]
                                                                       (let [{:keys [optional]} (when (= 2 (count args))
                                                                                                  (first args))]
                                                                         [k {:optional (boolean optional)
                                                                             :type (last args)}])))
                                                                (into {}))
                                              :encoder (m/encoder schema tx)
                                              :decoder (m/decoder schema tx)}])))
                              (into {})))
      (j/assoc! :action-queue (r/atom []))
      (j/assoc! :has-processor (r/atom false)))))

(defn compile
  [database expr]
  (reduce (fn [result [op & args :as expr-inner]]
            (condp = op
              :table (j/call result :get (->sql (first args)))
              :create (let [copy-fns (map (partial copy-fn database (j/get result :table)) args)]
                        (fn []
                          (->> copy-fns
                               (map (fn [copy-fn] (j/call result :prepareCreate copy-fn)))
                               (clj->js)
                               (j/call database :batch))))
              :update (let [[update-row & _] args]
                        (fn []
                          (j/call (result) :then
                                  #(->> update-row
                                        (copy-fn database (j/get-in % [:collection :table]))
                                        (j/call % :update)))))
              :delete (fn [] (j/call (result) :then #(j/call % :markAsDeleted)))
              :find (let [[id & _] args]
                      (fn [] (j/call result :find id)))
              :query (->> args
                          (map (partial compile database))
                          (apply j/call result :query))
              :where (if (not (sequential? (first args)))
                       (throw (js/Error.
                               (str ":cella/connection - First argument to ':where' operator must be a sequence of arguments"
                                    (pr-str {:args args}))))
                       (let [[where-op key & where-args :as where] (first args)]
                         (condp = where-op
                           :eq (j/call Q :where
                                       (->sql key)
                                       (j/call Q :eq (first where-args)))
                           :not-eq (j/call Q :where
                                           (->sql key)
                                           (j/call Q :notEq (first where-args)))
                           :gt (j/call Q :where
                                       (->sql key)
                                       (j/call Q :gt (first where-args)))
                           :gte (j/call Q :where
                                        (->sql key)
                                        (j/call Q :gte (first where-args)))
                           :weak-gt (j/call Q :where
                                            (->sql key)
                                            (j/call Q :weakGt (first where-args)))
                           :lt (j/call Q :where
                                       (->sql key)
                                       (j/call Q :lt (first where-args)))
                           :lte (j/call Q :where
                                        (->sql key)
                                        (j/call Q :lte (first where-args)))
                           :between (j/call Q :where
                                            (->sql key)
                                            (j/call Q :between #js [(first where-args)
                                                                    (second where-args)]))
                           :one-of (j/call Q :where
                                           (->sql key)
                                           (j/call Q :oneOf (clj->js where-args)))
                           :not-in (j/call Q :where
                                           (->sql key)
                                           (j/call Q :notIn (clj->js where-args)))
                           :like (j/call Q :where
                                         (->sql key)
                                         (j/call Q :like
                                                 (j/call Q :sanitizeLikeString
                                                         (first where-args))))
                           :not-like (j/call Q :where
                                             (->sql key)
                                             (j/call Q :notLike
                                                     (j/call Q :sanitizeLikeString
                                                             (first where-args))))
                           (throw (js/Error.
                                   (str ":cella/connection - Unrecognized Q.where operator: "
                                        (pr-str {:where where})))))))
              :fetch (fn []
                       (-> result
                           (j/call :fetch)
                           (j/call :then (partial decode {:schemas (j/get database :schemas)}))))
              :observe (fn []
                         (new js/Promise
                              (fn [resolve reject]
                                (try (let [result (if (fn? result) (result) result)
                                           table (j/get-in result [:collection :modelClass :table])
                                           observe (fn [observable]
                                                     (let [observable (j/call observable :observe)
                                                           decode (partial decode {:schemas (j/get database :schemas)})
                                                           t (atom nil)
                                                           timeout-ms 100
                                                           debounce (fn [f result]
                                                                      (when-let [t @t] (js/clearTimeout t))
                                                                      (reset! t (js/setTimeout
                                                                                 (fn []
                                                                                   (f result)
                                                                                   (reset! t nil))
                                                                                 timeout-ms)))]
                                                       (j/assoc! observable ":cella/subscribe"
                                                                 (fn [f]
                                                                   (let [f (comp f decode)]
                                                                     (j/call observable :subscribe
                                                                             (partial debounce f)))))
                                                       (resolve observable)))]
                                       (cond
                                         (j/get result :observe)
                                         (observe result)

                                         (j/get result :then) ;; promise
                                         (-> result
                                             (j/call :then observe)
                                             (j/call :catch reject))

                                         :else (reject (new js/Error ":cella/connection - Unable to observe" result))))
                                     (catch js/Error e
                                       (reject e))))))
              (throw (js/Error.
                      (str ":cella/connection - Unrecognized operation encountered when compiling expression: "
                           (pr-str {:outer expr
                                    :inner expr-inner}))))))
          database
          expr))

(defn run-action
  [database expr]
  (try (let [action-queue (j/get database :action-queue)
             p-ch (chan)
             p (new js/Promise
                    (fn [resolve reject]
                      (put! p-ch {:resolve resolve :reject reject})))
             done (fn [f result]
                    (go (try (let [{:keys [resolve reject]} (<! p-ch)]
                               (case f
                                 :resolve (resolve result)
                                 :reject (reject result)))
                             (close! p-ch)
                             (catch js/Error e
                               (js/console.error ":cella/connection - Error occurred in resolve go block" e)))))]
         (swap! action-queue conj
                {:action (compile database expr)
                 :resolve (fn [result] (done :resolve result))
                 :reject (fn [error] (done :reject error))})
         (when (not @(j/get database :has-processor))
           (reset! (j/get database :has-processor) true)
           (process-queue database))
         p)
       (catch js/Error e
         (js/console.warn e)
         (throw e))))

(defn run
  [database expr]
  (try ((compile database expr))
       (catch js/Error e
         (js/console.warn e)
         (throw e))))

;;; Implementation

(def ^:dynamic *parent-ks* nil)
(def connector-symbol "__DOT__")
(def connector-symbol-pattern (re-pattern connector-symbol))

(defn ->sql
  [v]
  (name (inflections/underscore v)))

(defn sql->
  [v]
  (keyword (inflections/dasherize v)))

(defn- key-paths
  ([m] (key-paths m []))
  ([m prefix]
   (mapcat (fn [[k v]]
             (let [kp (concat prefix [k])]
               (if (and (map? v)
                        (not (date? v))
                        (not-empty v))
                 (cons kp (key-paths v kp))
                 [kp]))) m)))

(defn- leaf?
  [m key-path]
  (not (coll? (get-in m key-path))))

(defn encode-ks
  [ks]
  (->> ks
       (map (comp name ->sql))
       (clojure.string/join connector-symbol)))

(defn decode-ks
  [key]
  (map sql-> (clojure.string/split key connector-symbol-pattern)))

(defn encode
  [context value]
  (cond
    (map? value)
    (let [{:keys [schemas table]} context
          {:keys [encoder schema]} (get schemas table)
          encoded (encoder value)]
      (->> encoded
           (key-paths)
           (filter (partial leaf? encoded))
           (map (fn [ks] [(encode-ks ks) (encode context (get-in encoded ks))]))
           (into {})))

    (keyword? value) (name value)

    :else value))

(defn decode
  [context value]
  (cond
    (js/Array.isArray value) (let [length (j/get value :length)]
                               (loop [result (transient [])
                                      i 0]
                                 (if (< i length)
                                   (recur (->> (aget value i)
                                               (decode context)
                                               (conj! result))
                                          (inc i))
                                   (persistent! result))))
    (j/get value :_raw) (-> context
                            (assoc :table (sql-> (j/get-in value [:collection :table])))
                            (decode (j/get value :_raw)))
    (object? value) (let [{:keys [schema-keys decoder]} (get-in context [:schemas (get context :table)])
                          object-keys (js/Object.keys value)
                          length (j/get object-keys :length)
                          assoc-in-ks (atom [])
                          result (decoder
                                  (loop [result (transient {})
                                         i 0]
                                    (if (< i length)
                                      (let [k (aget object-keys i)]
                                        (recur (if (or (= "_status" k)
                                                       (= "_changed" k))
                                                 result
                                                 (let [ks (decode-ks k)
                                                       v (j/get value k)
                                                       {:keys [optional type]} (if (= (count ks) 1)
                                                                                 (get schema-keys (first ks))
                                                                                 (get-in schema-keys ks))
                                                       empty-value? (and optional
                                                                         (or (and (= type :string)
                                                                                  (string? v)
                                                                                  (empty? v))
                                                                             (and (= type :keyword)
                                                                                  (string? v)
                                                                                  (empty? v))
                                                                             (and (= type :date)
                                                                                  (number? v)
                                                                                  (zero? v))))]
                                                   (if empty-value?
                                                     result
                                                     (if (= 1 (count ks))
                                                       (assoc! result (first ks) v)
                                                       (swap! assoc-in-ks conj [ks v])))))
                                               (inc i)))
                                      (persistent! result))))]
                      (if-let [assoc-in-ks (not-empty @assoc-in-ks)]
                        (reduce (fn [result [ks value]]
                                  (assoc-in result ks value))
                                result
                                assoc-in-ks)
                        result))
    :else value))

(def symbol->keyword
  {'any? :any
   'some? :some
   'number? :number
   'integer? :integer
   'int? :int
   'pos-int? :pos-int
   'neg-int? :neg-int
   'nat-int? :nat-int
   'float? :float
   'double? :double
   'boolean? :boolean
   'string? :string
   'ident? :ident
   'simple-ident? :simple-ident
   'qualified-ident? :qualified-ident
   'keyword? :keyword
   'simple-keyword? :simple-keyword
   'qualified-keyword? :qualified-keyword
   'symbol? :symbol
   'simple-symbol? :simple-symbol
   'qualified-symbol? :qualified-symbol
   'uuid? :uuid
   'uri? :uri
   'decimal? :decimal
   'inst? :inst
   'seqable? :seqable
   'indexed? :indexed
   'map? :map
   'vector? :vector
   'list? :list
   'seq? :seq
   'char? :char
   'set? :set
   'nil? :nil
   'false? :false
   'true? :true
   'zero? :zero
   'rational? :rational
   'coll? :coll
   'empty? :empty
   'associative? :associative
   'sequential? :sequential
   'ratio? :ratio
   'bytes? :bytes})

(defn sql-type
  [type]
  (let [type (get {:int :number
                   :integer :number
                   :pos-int :number
                   :neg-int :number
                   :nat-int :number
                   :float :number
                   :double :number
                   :keyword :string
                   :date :number} type type)]
    (when (#{:number :boolean :string :map} type)
      type)))

(defn resolve-type
  [schema]
  (let [type (m/type schema)]
    (or (sql-type (cond
                    (= :sequential type) :string
                    (keyword? type) type
                    (or (fn? type) (symbol? type)) (get symbol->keyword type)
                    :else (throw (ex-info "Unhandled type" {:type type}))))
        (throw (new js/Error (str ":cella/connection - Could not resolve type to one of string, boolean or number: "
                                  {:type type}))))))

(defn schema->columns
  [schema]
  (->> (m/walk schema (fn [schema properties children options]
                        (let [type (resolve-type schema)]
                          (compact
                           {:type type
                            :children (when (= type :map) children)}))))
       :children
       (mapcat (fn flatten-children [[key _ {:keys [type children]}]]
                 (if (seq children)
                   (binding [*parent-ks* (conj (vec *parent-ks*) key)]
                     (mapcat flatten-children children))
                   [{:ks (conj (vec *parent-ks*) key) :type type}])))
       (map (fn [{:keys [ks type]}]
              {:name (encode-ks ks)
               :type type}))))

(defn decode-seq
  [sq]
  (->> sq
       read-string
       (postwalk (fn [value]
                   (if (instance? js/Date value)
                     (t/from :long (j/call value :getTime))
                     value)))))

(defn encode-seq
  [sq]
  (->> sq
       (postwalk (fn [value]
                   (if (date? value)
                     (:date-time value)
                     value)))
       (pr-str)))

(defn cella-transformer []
  (mt/transformer
   {:name :string
    :decoders {:sequential decode-seq
               :vector decode-seq
               :date (partial t/from :long)}
    :encoders {:sequential encode-seq
               :vector encode-seq
               :date (partial t/into :long)}}))

(defn with-registry
  [schema registry]
  (mu/update-properties schema assoc :registry registry))

(defn date-schema
  []
  (m/-simple-schema
   {:type :date
    :pred date?}))

(defn copy-fn
  [database table row]
  (let [prepped-row (encode {:table (sql-> table)
                             :schemas (j/get database :schemas)} row)]
    (fn [row]
      (doseq [[k v] prepped-row]
        (j/assoc-in! row [:_raw k] v)))))

(defn get-own-property-descriptors
  [obj]
  (let [names (js/Object.getOwnPropertyNames obj)]
    (when (and names (pos? (j/get names :length)))
      (let [result (new js/Object)]
        (j/call names :forEach
                (fn [property-name]
                  (->> property-name
                       (js/Object.getOwnPropertyDescriptor obj)
                       (j/assoc! result property-name))))
        result))))

(defn extend-class
  [class]
  (let [target-atom (atom nil)
        target (fn [& args]
                 (let [result (js/Reflect.construct class (clj->js args) @target-atom)]
                   result))]
    (reset! target-atom target)
    (js/Object.assign (j/get target :prototype) (j/get class :prototype))
    (js/Object.assign target class)
    (when-let [props (get-own-property-descriptors (j/get class :prototype))]
      (doseq [prop (remove #{"constructor"} (js->clj (js/Object.keys props)))]
        (js/Object.defineProperty
         (j/get target :prototype)
         prop (j/get props prop))))
    target))

(defn model-class
  [table-name]
  (doto (extend-class Model)
    (j/assoc! :table (name table-name))))

(defn check-js-support
  []
  [{:name :reflect
    :message ":cella/connection - No support for 'js/Reflect' found. WatermelonDB relies on this property, and it must be polyfilled, or a version of js used that supports it."
    :supported (try js/Reflect true
                    (catch js/Error e
                      false))}])

(defn process-queue
  [database]
  (let [action-queue (j/get database :action-queue)
        {:keys [action resolve reject]} (first @action-queue)]
    (if action
      (do (swap! action-queue (comp vec rest))
          (try (-> database
                   (j/call :action action #js {:toString #(pr-str {:cella/action action})})
                   (j/call :then (fn [result] (resolve result) (process-queue database)))
                   (j/call :catch (fn [error] (reject error) (process-queue database))))
               (catch js/Error e
                 (reject e))))
      (reset! (j/get database :has-processor) false))))
