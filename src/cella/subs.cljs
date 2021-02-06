;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns cella.subs
  (:require [cella.connection :as db]
            [re-frame.core :as rf]
            [reagent.ratom :as rr]
            [integrant.core :as ig]
            [utilis.js :as j]
            [utilis.fn :refer [fsafe]]))

;;; Declarations

(declare ensure-observable)

;;; Integrant

(defmethod ig/init-key :cella/subs [_ {:keys [db-connection]}]
  (let [subscriptions (atom {})]
    (rf/reg-sub-raw
     :cella/subscribe
     (fn [_ [_ expr]]
       (let [a (rr/atom nil)
             dispose (atom nil)
             expr (ensure-observable expr)]
         (-> db-connection
             (db/run expr)
             (j/call :then (fn [result]
                             (cond
                               (coll? result) (reset! a result)

                               (j/get result ":cella/subscribe")
                               (let [subscription (j/call result ":cella/subscribe" (partial reset! a))]
                                 (reset! dispose (fn [] (j/call subscription :unsubscribe))))

                               :else (js/console.warn "Unhandled subscribe value" result))))
             (j/call :catch #(js/console.warn %)))
         (rr/make-reaction (fn [] @a) :on-dispose #((fsafe @dispose))))))))

(defmethod ig/halt-key! :cella/subs [_ _])

;;; Implementation

(defn- ensure-observable
  [expr]
  (condp = (first (last expr))
    :observe expr
    :fetch expr
    :table (vec (concat expr [[:query] [:observe]]))
    (conj (vec expr) [:observe])))
