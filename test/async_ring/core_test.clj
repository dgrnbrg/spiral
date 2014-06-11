(ns async-ring.core-test
  (:require [clojure.test :refer :all]
            [compojure.core :refer (ANY GET defroutes routes)]
            [org.httpkit.server :as http-kit]  
            [org.httpkit.client :as http]
            [hiccup.core :refer (html)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.util.response :refer (response)]
            [async-ring.core :refer :all]))

(defroutes ring-app
  (GET "/" []
       (response "all ok")) 
  (GET "/param" [q]
       (html [:html
              [:body
               (concat
                 (when q
                   [[:p (str "To " q)]])
                 [[:p
                   "Hello world, from Ring"]])]])))

(defn request
  [method url]
  (deref (http/request {:url url :method method} identity) 1000 nil))

(defmacro scaffold-http-kit
  [app & body]
  `(let [server# (http-kit/run-server (to-httpkit ~app) {:port 12438})]
     (try
       ~@body
       (finally
         (server#)))))

(deftest serve-constant-response
  (scaffold-http-kit (constant-response {:status 200 :body "success"})
                     (is (= (:body (request :get "http://localhost:12438/")) "success"))
                     (is (= (:body (request :put "http://localhost:12438/")) "success"))
                     (is (= (:body (request :post "http://localhost:12438/foo")) "success"))))

(deftest serve-ring-app
  (scaffold-http-kit (sync->async-adapter #'ring-app)
                     (is (= (:body (request :get "http://localhost:12438/")) "all ok"))
                     (println "Expect to see an exception next--nothing to worry about")
                     (is (= (:status (request :post "http://localhost:12438/")) 500))))

(deftest serve-ring-middleware
  (scaffold-http-kit (-> (sync->async-adapter #'ring-app)
                         (sync->async-middleware wrap-params))
                     (is (= (:body (request :get "http://localhost:12438/")) "all ok"))
                     (let [body ^String (:body (request :get "http://localhost:12438/param?q=clojure"))]
                       (is (.contains body "To clojure"))
                       (is (.startsWith body "<html>")))))

(deftest serve-route-concurrently
  (scaffold-http-kit (-> (route-concurrently
                           "/app" (sync->async-adapter #'ring-app)
                           "/" (constant-response {:status 404 :body "invalid"}))
                         (sync->async-middleware wrap-params))
                     (is (= (:body (request :get "http://localhost:12438/")) "invalid"))
                     (is (= (:body (request :put "http://localhost:12438/")) "invalid"))
                     (is (= (:body (request :post "http://localhost:12438/foo")) "invalid"))
                     ;;TODO figure out what the correct behavior for this case is
                     ;(is (= (:body (request :post "http://localhost:12438/app")) "invalid"))
                     (is (= (:body (request :get "http://localhost:12438/app/")) "all ok"))))

#_(def app
  (-> 
      #_(route-concurrently
        ;"/" (sync->async-adapter #'ring-app)
        "/" (constant-response {:status 404 :body "nononon!"})
        )
    (sync->async-adapter #'ring-app)
    (sync->async-middleware wrap-params)
      (work-shed 3 {:status 503 :body "work shedding"})
      (to-httpkit)))

#_(def app (-> (constant-response {:status 200 :body "nononon!"})
             (sync->async-middleware wrap-params)
             (work-shed 10 {:status 503 :body "work shedding"})
             (to-httpkit)))

(comment
  (def server (http-kit/run-server #'app {:port 8080}))

  (server)
  )
