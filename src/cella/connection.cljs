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
  (:refer-clojure :exclude [update find])
  (:require ["@nozbe/watermelondb" :as db :refer [appSchema tableSchema Database Model Q]]
            ["@nozbe/watermelondb/adapters/sqlite" :default SQLiteAdapter]
            ["@nozbe/watermelondb/Schema/migrations" :refer [schemaMigrations createTable addColumns]]
            [cljs.core :refer [PersistentQueue]]
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
            [reagent.ratom :as rr]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer [reg-sub]]))

;;; Declarations

(defn date?
  [x]
  (instance? t/DateTime x))

(def date-schema (m/-simple-schema {:type :date :pred date?}))

(def schema-registry
  (merge (m/default-schemas) {:date date-schema}))

(declare connect check-js-support database process-queue compile compile-and-maybe-fetch wdb-> read?)

;;; Integrant

(defmethod ig/init-key :cella/connection
  [_ {:keys [db-name schema-version tables] :as opts}]
  (try (doseq [{:keys [message]} (remove :supported (check-js-support))]
         (js/console.warn message))
       (let [db (connect opts)]
         (reg-sub
          :cella/queue-size
          (fn [] (j/get db :action-queue))
          (fn [queue] (count queue)))
         db)
       (catch js/Error e
         (js/console.warn e)
         (throw e))))

;;; Public

(defn connect
  "Connect to a WatermelonDB instance where `db-name` is the database name, and the list of `tables`
  each have both a `name` and a malli `schema` key."
  [{:keys [db-name tables]}]
  (try (doto (database
              {:db-name db-name
               :tables tables})
         (j/assoc! :action-queue (r/atom cljs.core/PersistentQueue.EMPTY))
         (j/assoc! :action-queue-state (r/atom :idle))
         (j/assoc! :subscriptions (atom {})))
       (catch js/Error e
         (js/console.warn e)
         (throw e))))

(defn run
  [database expr]
  (compile-and-maybe-fetch database expr))

(defn run-action
  [database expr]
  (js/Promise.
   (fn [resolve reject]
     (swap! (j/get database :action-queue) conj
            (fn [] (-> database
                      (j/call :action #(compile-and-maybe-fetch database expr)
                              #js {:toString #(pr-str expr)})
                      (j/call :then resolve)
                      (j/call :catch reject))))
     (process-queue database))))

(defn observe
  [database expr]
  (when (not (read? expr))
    (throw (ex-info "Can only observe read expressions" {:expr expr})))
  (let [a (r/atom nil)
        subscription (-> database
                         (compile expr)
                         (j/call :observe)
                         (j/call :subscribe (comp (partial reset! a) wdb->)))]
    (swap! (j/get database :subscriptions) assoc a {:dispose #(j/call subscription :unsubscribe)})
    a))

(defn dispose
  [database observable]
  (let [subscriptions (j/get database :subscriptions)
        {:keys [dispose]} (get @subscriptions observable)]
    (dispose)
    (swap! subscriptions dissoc observable)))

;;; Implementation

(defn read?
  [expr]
  (#{:table :get} (first (last expr))))

(defn process-queue*
  [database]
  (let [queue (j/get database :action-queue)]
    (if-let [handler (peek @queue)]
      (do (swap! queue pop)
          (-> (handler)
              (j/call :then (fn [_] (process-queue* database)))
              (j/call :catch (fn [_] (process-queue* database)))))
      (reset! (j/get database :action-queue-state) :idle))))

(defn process-queue
  [database]
  (let [queue-state (j/get database :action-queue-state)]
    (when (= @queue-state :idle)
      (reset! queue-state :busy)
      (process-queue* database))))

(defn check-js-support
  "Without checking for js/Reflect support, it becomes extremely hard to debug why nothing
  is working. Depending on the js version and output format being used, js/Reflect may be
  unavailable."
  []
  [{:name :reflect
    :message ":cella/connection - No support for 'js/Reflect' found. WatermelonDB relies on this property, and it must be polyfilled, or a version of js used that supports it."
    :supported (try js/Reflect true
                    (catch js/Error e
                      false))}])

(defn ->sql
  [v]
  (name (inflections/underscore v)))

(defn sql->
  [v]
  (keyword (inflections/dasherize v)))

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

(defn encode
  [value]
  (pr-str value))

(defn decode
  [value]
  (read-string value))

(defn table-schema
  [name columns]
  (tableSchema
   (clj->js
    {:name (->sql name)
     :columns columns})))

(defn app-schema
  [tables]
  (appSchema
   (clj->js
    {:version 1
     :tables (map (fn [{:keys [name columns]}]
                    (table-schema name columns))
                  tables)})))

(defn sqlite-adapter
  [db-name schema]
  (new SQLiteAdapter
       (clj->js
        {:schema schema
         :dbName (->sql db-name)
         :synchronous false})))

(defn model-class
  [table-name]
  (doto (extend-class Model)
    (j/assoc! :table (name table-name))))

(defn database
  [{:keys [db-name tables]}]
  (let [tables (map #(clojure.core/update % :schema m/schema {:registry schema-registry}) tables)]
    (new Database
         (clj->js
          {:adapter (sqlite-adapter
                     db-name
                     (app-schema
                      (map (fn [{:keys [name]}]
                             {:name name
                              :columns [{:name "value" :type :string}]})
                           tables)))
           :modelClasses (map (comp model-class ->sql :name) tables)
           :actionsEnabled true}))))

(defn table
  [database table-name]
  (j/call database :get (->sql table-name)))

(defn find
  [table id]
  (j/call table :find id))

(defn query
  [query]
  (j/call query :query))

(defn insert
  [database table doc-or-docs]
  (->> (cond
         (map? doc-or-docs) [doc-or-docs]
         (coll? doc-or-docs) (seq doc-or-docs)
         :else (throw (ex-info "Must provide either a single document or collection of documents to :insert"
                               {:table table
                                :args doc-or-docs})))
       (map (fn [doc]
              (j/call table :prepareCreate
                      (fn [obj]
                        (when-let [id (:id doc)]
                          (j/assoc-in! obj [:_raw :id] id))
                        (j/assoc-in! obj [:_raw :value] (encode doc))))))
       clj->js
       (j/call database :batch)))

(defn update*
  [obj doc]
  (when (= "deleted" (j/get-in obj [:_raw :_status]))
    (throw (ex-info "Record not found" {:id (j/get-in obj [:_raw :_id])})))
  (->> (-> (j/get-in obj [:_raw :value])
           decode
           (merge (dissoc doc :id))
           encode)
       (j/assoc-in! obj [:_raw :value])))

(defn update
  [obj doc]
  (j/call obj :then #(j/call % :update (fn [obj] (update* obj doc)))))

(defn upsert
  [database table doc]
  (js/Promise.
   (fn [resolve reject]
     (-> table
         (find (:id doc))
         (j/call :then #(resolve (j/call % :update (fn [obj] (update* obj doc)))))
         (j/call :catch (fn [error]
                          (if (re-find #"not found" (str error))
                            (resolve (insert database table doc))
                            (reject error))))))))

(defn delete
  [obj]
  (j/call obj :then #(j/call % :markAsDeleted)))

(defn compile
  [database expr]
  (let [expr (cond-> expr
               (read? expr) (conj [:query]))]
    (reduce (fn [result [op & args]]
              (case op
                :table (table result (first args))
                :get (find result (first args))
                :insert (insert database result (first args))
                :update (update result (first args))
                :upsert (upsert database result (first args))
                :delete (delete result)
                :query (query result)
                (throw (ex-info "Unable to compile expression"
                                {:expr expr
                                 :op op
                                 :args args}))))
            database
            expr)))

(defn compile-and-maybe-fetch
  [database expr]
  (cond-> database
    true (compile expr)
    (read? expr) (j/call :fetch)
    true (j/call :then wdb->)))

(defn arr->seq
  [tx arr]
  (let [n (j/get arr :length)]
    (loop [i 0
           result (transient [])]
      (if (< i n)
        (recur (inc i) (conj! result (tx (aget arr i))))
        (persistent! result)))))

(defn wdb->
  [obj]
  (cond
    (js/Array.isArray obj)
    (arr->seq wdb-> obj)

    (instance? js/Object obj)
    (if-let [value (j/get-in obj [:_raw :value])]
      (assoc (decode value) :id (j/get-in obj [:_raw :id]))
      (js->clj obj :keywordize-keys true))

    :else obj))
