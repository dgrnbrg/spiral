(ns async-ring.adapters.jetty
  "This namespace provides an async ring compatible Jetty adapter. This Jetty adapter supports normal ring and async ring handlers. If you want to pass an async ring handler to the adapter, then you must use the function to-jetty to convert the ring middleware to the async-jetty-adapter.
   
   For example, to run my-async-handler on jetty, just do:
   
   (async-jetty-adapter (to-jetty my-async-handler) {:port 8080})"
  (:require [clojure.core.async :as async]
            [clojure.stacktrace]
            [clojure.core.async.impl.protocols]
            [ring.adapter.jetty]
            [ring.util.response :refer (response status)]
            [clojure.tools.logging :as log]
            [ring.util.servlet :as servlet]))

;; This code is adapted from https://github.com/ninjudd/ring-async

(defn to-jetty
  [async-handler]
  (fn [request]
    async-handler))

(defn async-jetty-adapter
  [handler]
  (proxy [org.eclipse.jetty.server.handler.AbstractHandler] []
    (handle [_ ^org.eclipse.jetty.server.Request base-request servlet-request servlet-response]
      (let [request (servlet/build-request-map servlet-request)
            resp (handler request)]
        (if (satisfies? clojure.core.async.impl.protocols/Channel resp)
          (let [cont (org.eclipse.jetty.continuation.ContinuationSupport/getContinuation servlet-request)
                resp-chan (async/chan)
                error-chan (async/chan)] 
            (async/go
              (let [response
                    (async/alt!
                      resp-chan ([resp]
                                 (if resp
                                   resp
                                   (-> (response "nil response body in async-ring.core/to-httpkit")
                                       (status 500))))
                      error-chan ([e]
                                  (clojure.stacktrace/print-cause-trace e)
                                  (log/error e)
                                  (-> (response (str "Encountered error! See log."))
                                      (status 500))))]
                (servlet/update-servlet-response (.getServletResponse cont) response)
                (.complete cont)))
            (async/>!! resp (assoc request
                                   :async-response resp-chan
                                   :async-error error-chan))
            (.suspend cont))
          (when resp
            (servlet/update-servlet-response servlet-response resp)
            (.setHandled base-request true)))))))

(defn async-jetty-configurator
  [configurator handler]
  (fn [^org.eclipse.jetty.server.Server server]
    (.setHandler server (async-jetty-adapter handler))
    (when configurator
      (configurator server))))

(defn ^org.eclipse.jetty.server.Server run-jetty-async
  "Start a Jetty webserver to serve the given async handler. For a list
   of available options, see ring.adapter.jetty/run-jetty. Unlike
   run-jetty, this supports async ring handlers."
  [handler options]
  (ring.adapter.jetty/run-jetty nil (update-in options [:configurator]
                                               async-jetty-configurator
                                               handler)))

