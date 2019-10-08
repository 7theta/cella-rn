;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns cella.events
  (:require [cella.fx]
            [re-frame.core :refer [reg-event-fx]]))

(reg-event-fx
 :cella.async-storage/set!
 (fn [{:keys [db]} [_ props]]
   {:cella.async-storage/set! props}))

(reg-event-fx
 :cella.async-storage/remove!
 (fn [{:keys [db]} [_ props]]
   {:cella.async-storage/remove! props}))

(reg-event-fx
 :cella.async-storage/clear!
 (fn [{:keys [db]} [_ props]]
   {:cella.async-storage/clear! props}))
