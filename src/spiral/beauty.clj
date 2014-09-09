(ns spiral.beauty
  "This namespace contains the Beauty concurrent quality of service routing middleware. See README.md for details on how to use Beauty in your application."
  (:require [clojure.data.priority-map :refer (priority-map)]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defmacro beauty-route
  "This defers its body to run on the specied pool, with the optionally specified
   priority. If you want to execute side-effects in the body, you'll want to wrap it
   in a do."
  ;; Note that we prioritize so that a higher user-provided priority runs sooner,
  ;; and the oldest requests run sooner
  ([pool body]
   `{::beauty true
     :thunk (fn [] ~body)
     :pool ~pool
     :priority [5 (- (System/currentTimeMillis))]})
  ([pool priority body]
   `{::beauty true
     :thunk (fn [] ~body)
     :pool ~pool
     :priority [~priority (- (System/currentTimeMillis))]}))

(defn beauty-router
  "Creates a beauty-router pool. The pools-config should be a map, where the keys
   are the pool names, and the values are maps with 2 keys: :parallelism, which
   defines how many requests the pool can process concurrently, and :buffer-size,
   which defines how many requests can be queued on the pool before it starts to
   exhibit backpressure. Those keys have default values of 5 and 10, respectively."
  [handler pools-config]
  (let [pools (->> pools-config
                   (map (fn [[k]]
                          [k (async/chan)]))
                   (into {}))
        req-chan (async/chan)]
    (doseq [[pool {:keys [parallelism buffer-size]
                   :or {parallelism 5 buffer-size 10}}] pools-config
            :let [c (get pools pool)
                  work-chan (async/chan)]]
      (log/debug  "Creating beauty router pool" pool
                 "with parallelism" parallelism
                 "and buffer size" buffer-size)
      ;;Execution workers mustn't block the goroutine scheduler
      (dotimes [i parallelism]
        (async/thread
          (while true
            (let [{:keys [thunk request] :as task} (async/<!! work-chan)]
              (try
                (async/>!! (:async-response request) (thunk))
                (catch Throwable t
                  (async/>!! (:async-error request) t)))))))
      ;;This manages the priority queue
      (async/go
        (loop [buf (priority-map)]
          (let [cur-buf-size (count buf)
                request-op (when (<= cur-buf-size buffer-size)
                             [c])
                next-work-item (ffirst (rseq buf))
                work-op (when (pos? cur-buf-size)
                          [[work-chan next-work-item]])
                ops (vec (concat request-op work-op))
                [val port] (async/alts! ops)]
            (if (= port work-chan)
              (recur (dissoc buf next-work-item)) 
              (recur (assoc buf val (:priority val))))))))
    ;;TODO: can add extra copies of the following router worker
    ;;to improve routing performance
    (async/go
      (while true
        (let [req (async/<! req-chan)
              ;; First, let the router run
              resp (try
                     (handler req)
                     (catch Throwable t
                       {::error t}))]
          (cond
            (::beauty resp)
            ;; It's a beauty route
            (async/>! (get pools (:pool resp)) (assoc resp :request req))
            ;; It's an error
            (::error resp)
            (async/>! (:async-error req) (::error resp))
            ;; It's a normal response
            :else
            (async/>! (:async-response req) resp)))))
    req-chan))
