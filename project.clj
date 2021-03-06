;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/cella-rn "0.6.1"
  :description "ReactNative reactive storage with a re-frame interface."
  :url "https://github.com7theta/cella-rn"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[re-frame "1.2.0"]
                 [inflections "0.13.2"]
                 [integrant "0.8.0"]
                 [com.7theta/utilis "1.12.2"]
                 [com.7theta/tempus "0.3.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.3"]
                                  [org.clojure/clojurescript "1.10.866"]
                                  [integrant/repl "0.3.2"]]}}
  :scm {:name "git"
        :url "https://github.com/7theta/cella-rn"})
