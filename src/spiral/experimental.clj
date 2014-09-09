(ns spiral.experimental
  "This namespace contains experimental spiral functionality.
   
   work-shed is an async middle that automatically returns short, precomputed responses to clients when the server gets overloaded.
   
   route-concurrently is a simple async routing macro that provides a way to expose concurrency between different routes. Beauty will probably replace it wholesale.
   "
  (:require [clojure.core.async :as async]))

(defn work-shed
  "Sheds work when there are more than `threshold-concurrency` number
   of outstanding requests. To shed, simply returns the
   shed-response rather than continuing to process."
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

;; Wow, you read this far into the code!
;; This async-middleware section is an experiment designed to make
;; it easier to write new async middleware. It's not clear to me
;; whether the complexity of making a pluggable middleware is enough
;; of an advantage over just writing the middleware yourself

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
