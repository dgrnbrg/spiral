(ns spiral.macros
  "Probably makes sense to merge some of this stuff into core, keeping separate
  while figuring things out"
  (:require  [clojure.core.async :as async]
             [clojure.walk :as walk]))



;; This indiscriminately captures everything in the clojure.core.async namespace and
;; and rewrites it.
;; TODO - make a list of ones in core.async that take channel as the last parameter
;; TODO - walk the tree to find all the relevant core.async functions
(defn wrap-async-chans [form chan]

  (let [invocation (first form)
        invocation-namespace (namespace invocation)
        recursive-fn #(wrap-async-chans % chan)]
    (cond
    ;; Following deals sensibly with function calls, since
    ;; function symbol will not be wrapped
    (= invocation-namespace "clojure.core.async")  (let [internals (rest form)]
                                                     `(~invocation ~@internals chan))
    (seq? form) (map  recursive-fn form )
    (vector? form ) (map recursive-fn form)
    (map? form) (map recursive-fn form)
    (set? form) (map recursive-fn form)
    :else form)))




(defmacro defhandler [args & body]
  `(let [req-chan# (async/chan)]
     (async/go
       (wrap-async-chans ~@body 'req-chan#)
      )
    )
  )




;;; Test code
;; (defhandler [req] (async/<! (:test {})))
;; (defhandler [req] (do (async/<! (:test {}))))
;; (defhandler [req] (do (loop [i 0] (async/<! (:test {})) (recur (if (< i 2) (inc i))))))
;; (defhandler [req] (let [chan (async/chan)] ))
