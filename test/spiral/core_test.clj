(ns spiral.core-test
  "Isn't testing fun? This is just one big inbred testing file, because it's
   much easier to start each server and run the full test battery against them.
   Eventually, this could be split into several test files."
  (:require [clojure.test :refer :all]
            [compojure.core :refer (ANY GET defroutes routes)]
            [org.httpkit.server :as http-kit]  
            [org.httpkit.client :as http]
            [hiccup.core :refer (html)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.file-info :refer (wrap-file-info)]
            [ring.util.response :refer (response content-type)]
            [spiral.beauty :refer :all]
            [spiral.adapters.http-kit :refer :all]
            [spiral.adapters.jetty :refer :all]
            [spiral.experimental :refer :all]
            [spiral.core :refer :all]))

(defroutes ring-app
  (GET "/" []
       (-> (response "all ok")
           (content-type "text/plain")))
  (GET "/magicfile" []
       (-> (response (java.io.File. "project.clj"))
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
       (let [server# (run-jetty-async (to-jetty ~app) {:port 12438 :join? false})]
         (try
           ~@body
           (finally
             (.stop server#)))))))

(deftest jetty-sync-mode
  (let [server (run-jetty-async (fn [req] {:status 200 :body "sync-hi" :headers {"Content-Type" "text/plain"}}) {:port 12438 :join? false})]
    (try
      (is (= (:body (deref (http/request {:url "http://localhost:12438" :method :get}))) "sync-hi"))
      (finally
        (.stop server)))))

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

(deftest serve-ring-preprocess-middleware
  (scaffold-servers (-> (sync->async-adapter #'ring-app {})
                        (sync->async-preprocess-middleware wrap-params {}))
                    (is (= (:body (request :get "http://localhost:12438/")) "all ok"))
                    (let [body ^String (:body (request :get "http://localhost:12438/param?q=clojure"))]
                      (is (.contains body "To clojure"))
                      (is (.startsWith body "<html>")))))

(deftest serve-ring-postprocess-middleware
  (scaffold-servers (-> (sync->async-adapter #'ring-app {})
                        (sync->async-postprocess-middleware wrap-file-info {}))
                    (is (= (:body (request :get "http://localhost:12438/")) "all ok"))
                    (let [{:as foo :keys [headers body]} (request :get "http://localhost:12438/magicfile")]
                      (is (= org.httpkit.BytesInputStream (class body)))
                      (is (contains? headers :content-length)))))

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

(defn string-response
  [msg]
  {:status 200 :body msg :headers {"Content-Type" "text-plain"}})

(deftest beauty-router-test
  (scaffold-servers (beauty-router
                      (routes
                        (GET "/" []
                             (beauty-route :normal
                                           (string-response "root resp")))
                        (GET "/boring" []
                             (string-response "boring"))
                        (GET "/test/:code" [code]
                             (beauty-route :test
                                           (string-response (str "test " code)))))
                      {:normal {:parallelism 1}
                       :test {:parallelism 10}})
                    (is (= (:body (request :get "http://localhost:12438/")) "root resp"))
                    (is (= (:body (request :get "http://localhost:12438/boring")) "boring"))
                    (is (= (:body (request :get "http://localhost:12438/test/blah")) "test blah"))))

;; TODO: test work shedder
