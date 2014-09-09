(ns async-ring.middleware
  (:require [async-ring.core :refer (sync->async-preprocess-middleware sync->async-postprocess-middleware)]
            [clojure.core.async :as async] 
            [ring.middleware.file-info :as file-info]
            [ring.middleware.params :as params]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.file :as file]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.flash :as flash]
            [ring.middleware.head :as head]
            [ring.middleware.multipart-params :as multipart-params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.resource :as resource]
            [ring.middleware.session :as session]
            [ring.middleware.cookies :as cookies]))

(defn change-arg-to-async-handler
  [arglist]
  (if (#{'handler 'h 'app} (first arglist))
    (vec (concat ['async-handler 'async-options] (next arglist)))
    (throw (ex-info "Don't recognize arglist" {:arglist arglist}))))

(defn both-side-process-middleware
  [async-handler init-side preprocess-side postprocess-side
   {:keys [parallelism buffer-size]
    :or {parallelism 5
         buffer-size 10}
    :as options}
   & args]
  (let [req-chan (async/chan buffer-size)
        state (apply init-side args)]
    (dotimes [i parallelism]
      (async/go
        (while true
          (let [req (async/<! req-chan)
                resp-chan (async/chan)
                error-chan (async/chan)]
            (try
              (let [[state' preprocessed-req] (apply preprocess-side state req args)]
                (async/>! async-handler
                          (assoc preprocessed-req
                                 :async-response resp-chan
                                 :async-error error-chan))
                (let [value (async/alt!
                              resp-chan ([resp] resp)
                              error-chan ([e] (throw e)))
                      result (apply postprocess-side state' value args)]
                  (if result
                    (async/>! (:async-response req) result)
                    (async/>! (:async-error req)
                              (ex-info "post-process handler returned nil"
                                       {:req req :value-before-postprocess value})))))
              (catch Throwable e
                (async/>! (:async-error req) e)))))))
    req-chan))

(defmacro provide-process-middleware
  [f pre-or-post & [initpro prepro postpro :as impls]]
  (let [v (resolve f)
        details (-> (meta v)
                    (update-in [:arglists] #(map change-arg-to-async-handler %))
                    (assoc :ns *ns*)
                    (update-in [:doc] #(str "This is the async ring version of " (:ns (meta v)) \/ (:name (meta v)) \newline \newline %)))
        standard-case `(apply ~(case pre-or-post
                                 :post `sync->async-postprocess-middleware
                                 :pre `sync->async-preprocess-middleware
                                 :both nil
                                 (throw (ex-info "Must use :pre or :post for pre-or-post arg"
                                                 {:pre-or-post pre-or-post})))
                              ~'async-handler
                              ~f
                              ~'opts
                              ~'args)
        special-case `(apply both-side-process-middleware
                             ~'async-handler
                             ~initpro ~prepro ~postpro
                             ~'opts ~'args)]
    `(defn ~(:name (meta v))
       ~(:doc details)
       {:arglists '~(:arglists details)}
       [~'async-handler ~'opts & ~'args]
       ~(if (and (= :both pre-or-post) impls) special-case standard-case))))


(provide-process-middleware params/wrap-params :pre)
(provide-process-middleware file-info/wrap-file-info :post)
(provide-process-middleware
  file-info/wrap-file-info :both
  (fn wrap-init [& args])
  (fn wrap-pre
    [state request & args]
    [request request])
  (fn wrap-post
    [state response & [mime-types]]
    (file-info/file-info-response response state mime-types)))
(provide-process-middleware file/wrap-file :pre)
(provide-process-middleware keyword-params/wrap-keyword-params :pre)
(provide-process-middleware multipart-params/wrap-multipart-params :pre)
(provide-process-middleware nested-params/wrap-nested-params :pre)
(provide-process-middleware resource/wrap-resource :pre)

(provide-process-middleware
  content-type/wrap-content-type :both
  (fn wrap-init [& args])
  (fn wrap-pre
    [state request & args]
    [request request])
  (fn wrap-post
    [state response & [opts]]
    (content-type/content-type-response response state opts)))


(provide-process-middleware
  cookies/wrap-cookies :both
  (fn wrap-init [& args])
  (fn wrap-pre
    [state request]
    (let [request (if (request :cookies)
                    request
                    (assoc request :cookies (#'cookies/parse-cookies request)))]
      [nil request]))
  (fn wrap-post
    [state response]
    (-> response
        (#'cookies/set-cookies)
        (dissoc :cookies))))

(provide-process-middleware
  flash/wrap-flash :both
  (fn wrap-init [& args])
  (fn wrap-pre [state request]
    (let [session (:session request)
          flash   (:_flash session)
          session (dissoc session :_flash)
          request (assoc request :session session, :flash flash)]
      [{:session session
        :flash flash} request]))
  (fn wrap-post [state response]
    (if-let [response response]
      (let [session (if (contains? response :session)
                      (response :session)
                      (:session state))
            session (if-let [flash (response :flash)]
                      (assoc (response :session session) :_flash flash)
                      session)]
        (if (or (:flash state) (response :flash) (contains? response :session))
          (assoc response :session session)
          response)))))

(provide-process-middleware
  head/wrap-head :both
  (fn wrap-init [& args])
  (fn wrap-pre [state request]
    (if (= :head (:request-method request))
      [true (-> request
                (assoc :request-method :get))]
      [false request]))
  (fn wrap-post [state response]
    (if state
      (assoc response :body nil)
      response)))

(provide-process-middleware
  session/wrap-session :both
  (fn wrap-init
    ([] (wrap-init {}))
    ([options]
     (session/session-options options)))
  (fn wrap-pre
    ([opts request]
     (wrap-pre opts request {}))
    ([opts request _]
     (let [req (session/session-request request opts)]
       [[opts req] req])))
  (fn wrap-post
    ([state response]
     (wrap-post state response {}))
    ([[opts req] response _]
     (session/session-response response req opts))))
