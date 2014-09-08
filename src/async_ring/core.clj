(ns async-ring.core
  "This namespace provides a core.async API for Ring. It allows you define and nest synchronous
   and async ring handlers to create efficient, async servers built on http-kit.

   Async handlers are just core.async channels! To use them, put ring request maps into them.
   Each request map must contain 2 additional keys, :async-response and :async-error, which must
   both be channels. For each ring request map you put into the input channel, you will recieve
   either a response map via the :async-response channel or a Throwable via the :async-error
   channel."
  (:require [clojure.core.async :as async]
            [clojure.stacktrace]
            [clojure.tools.logging :as log]
            [ring.util.response :refer (response status)]
            [org.httpkit.server :as http-kit]))

;;; The fundamental unit is a channel. It expects to recieve Ring request maps, which each contain
;;; 2 extra keys: `:async-response` and `:async-error`, which contain channels onto which you can
;;; put Ring response maps or Exceptions, respectively.
;;;

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
                         (http-kit/send! http-kit-chan (-> (response "nil response body in async-ring.core/to-httpkit")
                                                           (status 500)))))
            error-chan ([e]
                        (clojure.stacktrace/print-cause-trace e)
                        (http-kit/send! http-kit-chan (-> (response (str "Encountered error! See log."))
                                                          (status 500)))
                        (log/error e))))))))

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
   "
  [async-handler middleware options & args]
  (let [handler (async->sync-adapter async-handler)]
    (sync->async-adapter (apply middleware handler args) options)))

(defn constant-response
  "Returns an async-handler that always returns the given response."
  [response]
  (let [req-chan (async/chan)]
    (async/go
      (while true
        (async/>! (:async-response (async/<! req-chan)) response)))
    req-chan))

#_(defn async-middleware
  [h & {request-transform :req
        response-tranform :resp
        error-transform :error
        parallelism :parallism
        :or {parallelism 5}}]
  (let [req-chan (async/chan)]
    (dotimes [i parallelism]
      (async/go
        (while true
          (let [request-map (async/<! req-chan)
                resp-chan (async/chan)
                error-chan (async/chan)
                tranformed-request (request-transform request-map)]
            (async/go (async/alt!
                        resp-chan ([resp] ((or response-tranform identity)
                                           (async/>! (:async-response request-map) resp)))
                        error-chan ([e] ((or error-transform identity)
                                         (async/>! (:async-error request-map) e)))))
            ;; nil = short circuit
            (when tranformed-request
              (async/>! h
                        (assoc tranformed-request
                               :async-response resp-chan
                               :async-error error-chan)))))))
    req-chan))

(defn work-shed
  "Sheds work when there are more than `threshold-concurrency` number
   of outstanding requests"
  [async-middleware threshold-concurrency shed-response]
  (let [req-chan (async/chan)
        outstanding (atom 0)]
    (async/go
      (while true
        (let [req (async/<! req-chan)
              resp-chan (async/chan)
              error-chan (async/chan)]
          (if (< @outstanding threshold-concurrency)
            (do (swap! outstanding inc)
                (async/go (async/alt!
                            resp-chan ([resp] (swap! outstanding dec) (async/>! (:async-response req) resp))
                            error-chan ([e] (swap! outstanding dec) (async/>! (:async-error req) e))))
                (async/>! async-middleware
                          (assoc req
                                 :async-response resp-chan
                                 :async-error error-chan)))
            (async/>! (:async-response req) shed-response)))))
    req-chan))

#_(defn work-shed
  [h threshold-concurrency]
  (let [outstanding (atom 0)
        bouncer (constant-response [:status 503 :body "overload"])]
    (async-middleware
      h
      :req (fn [req next-chan]
             (swap! outstanding inc)
             (if (< @outstanding threshold-concurrency)
               req
               (do
                 (async/>!! bouncer)
                 nil)))
      :resp (fn [resp] (swap! outstanding dec) resp)
      :error (fn [e] (swap! outstanding dec) e))))

(defn route-concurrently-
  [pairs]
  (let [req-chan (async/chan)]
    (async/go
      (while true
        (let [req (async/<! req-chan)
              [guard fwd] (some (fn [[^String guard :as pair]]
                                  (when (.startsWith (:uri req "") guard)
                                    pair))
                                pairs)
              uri-suffix (.substring ^String (:uri req) (count guard))
              uri-suffix (if (= uri-suffix "")
                           "/"
                           uri-suffix)]
          (async/>! fwd (assoc req :uri uri-suffix)))))
    req-chan))

(defmacro route-concurrently
  "TODO: We must consider whether we should route and pass along the prefix or not,
   and whether we should force the inclusion of the trailing slash (because the
   standalone / is the start and the end, so what's up with that?)"
  [& args]
  (let [pairs (partition 2 args)
        compiled (mapv (fn [[guard fwd]]
                         (when-not (string? guard)
                           (throw (ex-info "guard must be a string" {:guard guard})))
                         [guard fwd])
                       pairs)]
    `(route-concurrently- ~compiled)))

