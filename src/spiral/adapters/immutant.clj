(ns spiral.adapters.immutant
  "This namespace provides a spiral compatible immutant adapter. In order to use it, simple pass your spiral handler to the function to-immutant, and have the return value of that function be the immutant handler.

  For example, to run my-async-handler on immutant, just do:

  (immutant.web/run (to-immutant my-async-handler) :port 8080)"
  (:require [immutant.web.async :as immutant]
            [clojure.tools.logging :as log]
            [clojure.stacktrace]
            [ring.util.response :refer (response status)]
            [clojure.core.async :as async]))

(defn to-immutant
  "Allows the given async handler to be used on immutant"
  [req-chan]
  (fn immutant-adapter [req]
    (let [resp-chan (async/chan)
          error-chan (async/chan)]
      (immutant/as-channel req
        :on-open
        (fn [ch]
          (async/>!! req-chan (assoc req
                                :async-response resp-chan
                                :async-error error-chan))
          (async/go
            (async/alt!
              resp-chan ([resp]
                         (if resp
                           (immutant/send! ch resp
                             :close? (not (:websocket? req)))
                           (immutant/send! ch
                             (-> (response "nil response body in spiral.core/to-immutant")
                               (status 500))
                             :close? true)))
              error-chan ([e]
                          (clojure.stacktrace/print-cause-trace e)
                          (immutant/send! ch
                            (-> (response (str "Encountered error! See log."))
                              (status 500))
                            :close? true)
                          (log/error e "Encountered unhandled error from spiral error channel")))))))))
