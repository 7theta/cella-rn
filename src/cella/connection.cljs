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
  (:require ["@nozbe/watermelondb" :as db :refer [appSchema tableSchema Database Model]]
            ["@nozbe/watermelondb/adapters/sqlite" :default SQLiteAdapter]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [tempus.core :as t]
            [inflections.core :as inflections]
            [utilis.map :refer [compact map-vals]]
            [utilis.js :as j]
            [integrant.core :as ig]
            [clojure.string :as st]
            [goog.object :as gobj]))

;;; Declarations

(declare model-classes schema->columns ->sql sql-> encode decode cella-transformer with-registry date-schema connect
         copy-fn)

(defn date?
  [x]
  (instance? t/DateTime x))

;;; Integrant

(defmethod ig/init-key :cella/connection
  [_ {:keys [db-name schema-version tables] :as opts}]
  (try (connect opts)
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
                       :synchronous false}))]
    (doto (new Database
               (clj->js
                {:adapter adapter
                 :modelClasses (->> tables
                                    (map (comp ->sql :name))
                                    (model-classes))
                 :actionsEnabled true}))
      (j/assoc! :schemas (->> tables
                              (map (fn [{:keys [name schema]}]
                                     (let [tx (mt/transformer mt/json-transformer cella-transformer)]
                                       [name {:schema schema
                                              :encoder (m/encoder schema tx)
                                              :decoder (m/decoder schema tx)}])))
                              (into {}))))))

(defn compile
  [database expr]
  (reduce (fn [result [op & args]]
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
              :fetch (fn []
                       (-> result
                           (j/call :fetch)
                           (j/call :then (partial decode {:schemas (j/get database :schemas)}))))
              :observe (fn []
                         (new js/Promise
                              (fn [resolve reject]
                                (try (let [result (if (fn? result) (result) result)
                                           observe (fn [observable]
                                                     (let [observable (j/call observable :observe)
                                                           decode (partial decode {:schemas (j/get database :schemas)})]
                                                       (j/assoc! observable ":cella/subscribe"
                                                                 (fn [f]
                                                                   (j/call observable :subscribe
                                                                           (comp f decode))))
                                                       (resolve observable)))]
                                       (cond
                                         (j/get result :observe)
                                         (observe result)

                                         (j/get result :then) ;; promise
                                         (-> result
                                             (j/call :then observe)
                                             (j/call :catch reject))

                                         :else (reject (new js/Error "Unable to observe" result))))
                                     (catch js/Error e
                                       (reject e))))))))
          database
          expr))

(defn run
  [database expr]
  (j/call database :action (compile database expr)
          #js {:toString #(pr-str {:cella/action expr})}))

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
          value (encoder value)]
      (->> value
           (key-paths)
           (filter (partial leaf? value))
           (map (fn [ks] [(encode-ks ks) (encode context (get-in value ks))]))
           (into {})))

    (keyword? value) (name value)

    :else value))

(defn decode
  [context value]
  (cond
    (js/Array.isArray value) (map (partial decode context) (js->clj value))
    (j/get value :_raw) (-> context
                            (assoc :table (sql-> (j/get-in value [:collection :table])))
                            (decode (js->clj (j/get value :_raw))))
    (map? value) (let [{:keys [decoder]} (get-in context [:schemas (get context :table)])]
                   (->> value
                        (map (fn [[k value]]
                               (when (not (#{"_status" "_changed"} k))
                                 [(decode-ks k) value])))
                        (remove nil?)
                        (reduce (fn [m [ks value]]
                                  (assoc-in m ks value))
                                {})
                        (decoder)))
    :else value))

(defn extend-class
  [class]
  (let [target-atom (atom nil)
        target (fn [& args] (js/Reflect.construct class (clj->js args) @target-atom))]
    (reset! target-atom target)
    (js/Object.assign (j/get target :prototype) (j/get class :prototype))
    (js/Object.assign target class)
    (when-let [props (js/Object.getOwnPropertyDescriptors (j/get class :prototype))]
      (doseq [prop (remove #{"constructor"} (js->clj (js/Object.keys props)))]
        (js/Object.defineProperty
         (j/get target :prototype)
         prop (j/get props prop))))
    target))

(defn model-classes
  [table-names]
  (map (fn [table-name]
         (doto (extend-class Model)
           (j/assoc! :table (name table-name))))
       table-names))

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
                    (= :sequential type) (resolve-type (last (m/form schema)))
                    (keyword? type) type
                    (or (fn? type) (symbol? type)) (get symbol->keyword type)
                    :else (throw (ex-info "Unhandled type" {:type type}))))
        (throw (new js/Error (str "Could not resolve type to one of string, boolean or number: "
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

(defn cella-transformer []
  (mt/transformer
   {:name :string
    :decoders {:date (partial t/from :edn)}
    :encoders {:date (partial t/into :edn)}}))

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
