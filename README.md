# com.7theta/cella-rn

> Latin word for `warehouse`, `room`, `cellar`, `granary` or `loft`.

[![Current Version](https://img.shields.io/clojars/v/com.7theta/cella-rn.svg)](https://clojars.org/com.7theta/cella-rn)
[![GitHub license](https://img.shields.io/github/license/7theta/cella-rn.svg)](LICENSE)
[![Circle CI](https://circleci.com/gh/7theta/cella-rn.svg?style=shield)](https://circleci.com/gh/7theta/cella-rn)
[![Dependencies Status](https://jarkeeper.com/7theta/cella-rn/status.svg)](https://jarkeeper.com/7theta/cella-rn)

Wrapper for [WatermelonDB](https://github.com/Nozbe/WatermelonDB) in
React Native.

## Usage

Using the API directly with promises.

```clojure
(:require [cella.connection :as db])
(def database (db/connect {:db-name "my-db-name"
                           :tables [{:name "users"}]}))

(-> database
    (cella/run [[:table :users]
                [:insert {:id "user/abc"}]])
    (j/call :then #(js/console.log "added user/abc"))
    (j/call :catch #(js/console.error "an error occurred" %)))

(-> database
    (cella/run [[:table :users]])
    (j/call :then #(cljs.pprint/pprint {:users %})))
```

re-frame integration

```clojure
(:require [re-frame.core :as rf])

@(rf/subscribe [[:cella/subscribe
                 [[:table :users]]]])

@(rf/subscribe [[:cella/subscribe
                 [[:table :users]
                  [:get "user/abc"]]]])

(rf/dispatch
 [:cella/run
  [[:table :users]
   [:insert {:id "user/abc"}]]])

(rf/dispatch
 [:cella/run
  [[:table :users]
   [:get "user/abc"]
   [:update {:name "ABC"}]]])

(rf/dispatch
 [:cella/run
  [[:table :users]
   [:get "user/abc"]
   [:delete]]])
```

## Copyright and License

Copyright © 2021 7theta
