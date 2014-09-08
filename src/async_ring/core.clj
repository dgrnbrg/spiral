(ns async-ring.core
  "This namespace provides a core.async API for Ring. It allows you define and nest synchronous
   and async ring handlers to create efficient, async http servers.

   Async handlers are just core.async channels! To use them, put ring request maps into them.
   Each request map must contain 2 additional keys, :async-response and :async-error, which must
   both be channels. For each ring request map you put into the input channel, you will recieve
   either a response map via the :async-response channel or a Throwable via the :async-error
   channel."
  (:require [clojure.core.async :as async]))

;;; The fundamental unit is a channel. It expects to recieve Ring request maps, which each contain
;;; 2 extra keys: `:async-response` and `:async-error`, which contain channels onto which you can
;;; put Ring response maps or Exceptions, respectively.
;;;

(defn async->sync-adapter
  "Takes an async ring handler and converts into a normal ring handler.

   This uses blocking async operations, so the async ring handler shouldn't block."
  [async-middleware]
  (fn async->sync-handler-adapter-helper [req]
    (let [resp-chan (async/chan)
          error-chan (async/chan)]
      (async/go (async/>! async-middleware
                          (assoc req
                                 :async-response resp-chan
                                 :async-error error-chan)))
      (async/alt!!
        resp-chan ([resp] resp)
        error-chan ([e] (throw e))))))

(defn sync->async-adapter
  "This takes a normal ring handler and converts it into an async ring
   handler. It runs the ring handler on up to :parallelism goroutines,
   and it will queue up to :buffer-size requests before exhibiting
   back-pressure."
  [handler {:keys [parallelism buffer-size]
            :or {parallelism 5
                 buffer-size 10}
            :as options}]
  (let [req-chan (async/chan buffer-size)]
    (dotimes [i parallelism]
      (async/go
        (while true
          (let [req (async/<! req-chan)]
            (try
              (let [resp (handler req)]
                (if resp
                  (async/>! (:async-response req) resp)
                  (async/>! (:async-error req)
                            (ex-info "Handler returned null"
                                     {:req req :handler handler}))))
              (catch Exception e
                (async/>! (:async-error req) e)))))))
    req-chan))

(defn sync->async-middleware
  "This lets you use normal ring middleware in an async ring app. You must
   provide the async-handler that will be wrapped with the middleware,
   an options map (nil means use defaults) to configure the concurrent
   properties of the synchronous middleware, and you can optionally provide
   additional args for the ring middleware.

   For example, suppose that ah is an sync ring handler. To combine it
   with ring.middleware.json/wrap-json-body, we can write:

   (sync->async-middleware ah wrap-json-body {:parallelism 2} {:keywords? true})

   Thus you can see how extra arguments (i.e. {:keyswords? true}) are passed to
   wrap-json-body.

   See async->sync-middleware for the dual.
   "
  [async-handler middleware options & args]
  (let [handler (async->sync-adapter async-handler)]
    (sync->async-adapter (apply middleware handler args) options)))

(defn async->sync-middleware
  "This lets you use async ring middleware in a normal ring app. You
   simply provide the normal ring handler as well as the constructor
   function for the async-middlware, along with any args that the
   async-middleware might take.

   Options configures the concurrency properties of the given
   normal ring handler; this will affect performance of the async
   middleware.

   See sync->async-middleware for the dual.
   "
  [handler options async-middleware & args]
  (let [async-handler (sync->async-adapter handler options)]
    (async->sync-adapter (apply async-middleware async-handler args))))

(defn constant-response
  "Returns an async-handler that always returns the given response."
  [response]
  (let [req-chan (async/chan)]
    (async/go
      (while true
        (async/>! (:async-response (async/<! req-chan)) response)))
    req-chan))
