(ns async-ring.core-test
  (:require [clojure.test :refer :all]
            [compojure.core :refer (ANY GET defroutes routes)]
            [org.httpkit.server :as http-kit]  
            [org.httpkit.client :as http]
            [hiccup.core :refer (html)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.util.response :refer (response content-type)]
            [async-ring.core :refer :all]))

(defroutes ring-app
  (GET "/" []
       (-> (response "all ok")
           (content-type "text/plain")))
  (GET "/param" [q]
       (-> (html [:html
                  [:body
                   (concat
                     (when q
                       [[:p (str "To " q)]])
                     [[:p
                       "Hello world, from Ring"]])]])
           (response)
           (content-type "text/html"))))

(defn request
  [method url]
  (deref (http/request {:url url :method method} identity) 1000 nil))

(defmacro scaffold-servers
  [app & body]
  `(do
     (testing "http-kit"
       (let [server# (http-kit/run-server (to-httpkit ~app) {:port 12438})]
         (try
           ~@body
           (finally
             (server#)))))
     (testing "jetty"
       (let [server# (run-jetty-async ~app {:port 12438 :join? false})]
         (try
           ~@body
           (finally
             (.stop server#)))))))

(comment
  (def srv (http-kit/run-server
             (to-httpkit
               (-> (route-concurrently
                     "/app" (sync->async-adapter #'ring-app {})
                     "/" (constant-response {:status 404 :body "invalid"}))
                   (sync->async-middleware wrap-params {}))) {:port 12438}))
  (srv)
  )

(deftest serve-constant-response
  (scaffold-servers (constant-response {:status 200 :body "success" :headers {"Content-Type" "text/plain"}})
                     (is (= (:body (request :get "http://localhost:12438/")) "success"))
                     (is (= (:body (request :put "http://localhost:12438/")) "success"))
                     (is (= (:body (request :post "http://localhost:12438/foo")) "success"))))

(deftest serve-ring-app
  (scaffold-servers (sync->async-adapter #'ring-app {})
                     (is (= (:body (request :get "http://localhost:12438/")) "all ok"))
                     (println "Expect to see an exception next--nothing to worry about")
                     (is (= (:status (request :post "http://localhost:12438/")) 500))))

(deftest serve-ring-middleware
  (scaffold-servers (-> (sync->async-adapter #'ring-app {})
                         (sync->async-middleware wrap-params {}))
                     (is (= (:body (request :get "http://localhost:12438/")) "all ok"))
                     (let [body ^String (:body (request :get "http://localhost:12438/param?q=clojure"))]
                       (is (.contains body "To clojure"))
                       (is (.startsWith body "<html>")))))

(deftest serve-route-concurrently
  (scaffold-servers (-> (route-concurrently
                           "/app" (sync->async-adapter #'ring-app {})
                           "/" (constant-response {:status 404 :body "invalid"}))
                         (sync->async-middleware wrap-params {}))
                     (is (= (:body (request :get "http://localhost:12438/")) "invalid"))
                     (is (= (:body (request :put "http://localhost:12438/")) "invalid"))
                     (is (= (:body (request :post "http://localhost:12438/foo")) "invalid"))
                     ;;TODO figure out what the correct behavior for this case is
                     ;(is (= (:body (request :post "http://localhost:12438/app")) "invalid"))
                     (is (= (:body (request :get "http://localhost:12438/app/")) "all ok"))))

;; TODO: test work shedder
