(ns spiral.adapters.http-kit
  "This namespace provides a spiral compatible http-kit adapter. In order to use it, simple pass your spiral handler to the function to-httpkit, and have the return value of that function be the http-kit handler.
   
   For example, to run my-async-handler on http-kit, just do:
   
   (http-kit/run-server (to-httpkit my-async-handler) {:port 8080})"
  (:require [org.httpkit.server :as http-kit]
            [clojure.tools.logging :as log]
            [clojure.stacktrace]
            [ring.util.response :refer (response status)]
            [clojure.core.async :as async]))

(defn to-httpkit
  "Allows the given async handler to be used on http-kit"
  [req-chan]
  (fn httpkit-adapter [req]
    (http-kit/with-channel req http-kit-chan
      (let [resp-chan (async/chan)
            error-chan (async/chan)]
        (async/>!! req-chan (assoc req
                                   :async-response resp-chan
                                   :async-error error-chan))
        (async/go
          (async/alt!
            resp-chan ([resp]
                       (if resp
                         (http-kit/send! http-kit-chan resp)
                         (http-kit/send! http-kit-chan (-> (response "nil response body in spiral.core/to-httpkit")
                                                           (status 500)))))
            error-chan ([e]
                        (clojure.stacktrace/print-cause-trace e)
                        (http-kit/send! http-kit-chan (-> (response (str "Encountered error! See log."))
                                                          (status 500)))
                        (log/error e))))))))

