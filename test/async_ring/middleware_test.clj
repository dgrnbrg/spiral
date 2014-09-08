(ns async-ring.middleware-test
  (:use clojure.test
        async-ring.core
        [ring.middleware.session :only [session-request session-response]]
        ring.middleware.session.store  
        [clojure.string :only (split)]
        [ring.util.io :only (string-input-stream)] 
        [clj-time.core :only (interval date-time)]  
        async-ring.middleware)
  (:require ring.middleware.session.memory)
  (:import java.io.File))

(defmacro patch-test
  "I'm bored of refactoring tests. This refacts the middleware tests automatically,
   usually."
  [middleware constant-handler & args]
  `(async->sync-adapter (~middleware (sync->async-adapter ~constant-handler {}) {} ~@args)))

(deftest wrap-content-type-test
  (testing "response without content-type"
    (let [response {:headers {}}
          handler  (async->sync-adapter (wrap-content-type (constant-response response) {}))]
      (is (= (handler {:uri "/foo/bar.png"})
             {:headers {"Content-Type" "image/png"}}))
      (is (= (handler {:uri "/foo/bar.txt"})
             {:headers {"Content-Type" "text/plain"}}))))

  (testing "response with content-type"
    (let [response {:headers {"Content-Type" "application/x-foo"}}
          handler (async->sync-adapter (wrap-content-type (constant-response response) {}))]
      (is (= (handler {:uri "/foo/bar.png"})
             {:headers {"Content-Type" "application/x-foo"}}))))

  (testing "unknown file extension"
    (let [response {:headers {}}
          handler (async->sync-adapter (wrap-content-type (constant-response response) {}))]
      (is (= (handler {:uri "/foo/bar.xxxaaa"})
             {:headers {"Content-Type" "application/octet-stream"}}))
      (is (= (handler {:uri "/foo/bar"})
             {:headers {"Content-Type" "application/octet-stream"}})))))

(deftest wrap-cookies-set-basic-cookie
  (let [handler (constant-response {:cookies {"a" "b"}})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (= {"Set-Cookie" (list "a=b")}
           (:headers resp)))))

(deftest wrap-cookies-set-multiple-cookies
  (let [handler (constant-response {:cookies {"a" "b", "c" "d"}})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (= {"Set-Cookie" (list "a=b" "c=d")}
           (:headers resp)))))

(deftest wrap-cookies-set-keyword-cookie
  (let [handler (constant-response {:cookies {:a "b"}})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (= {"Set-Cookie" (list "a=b")}
           (:headers resp)))))

(defn =cookie
  [l r]
  (let [lc (get l "Set-Cookie")
        rc (get r "Set-Cookie")]
    (->> (map vector lc rc)
         (map (fn [[l r]]
                (= (set (split l #";"))
                   (set (split r #";")))))
         (every? true?))))

(deftest wrap-cookies-set-extra-attrs
  (let [cookies {"a" {:value "b", :path "/", :secure true, :http-only true }}
        handler (constant-response {:cookies cookies})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (=cookie {"Set-Cookie" (list "a=b;Path=/;HttpOnly;Secure")}
                 (:headers resp)))))

(deftest wrap-cookies-set-urlencoded-cookie
  (let [handler (constant-response {:cookies {"a" "hello world"}})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})] 
    (is (= {"Set-Cookie" (list "a=hello+world")}
           (:headers resp)))))

(deftest wrap-cookies-keep-set-cookies-intact
  (let [handler (constant-response {:headers {"Set-Cookie" (list "a=b")}
                             :cookies {:c "d"}})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (= {"Set-Cookie" (list "a=b" "c=d")}
           (:headers resp)))))

(deftest wrap-cookies-invalid-attrs
  (let [response {:cookies {"a" {:value "foo" :invalid true}}}
        handler    (async->sync-adapter (wrap-cookies (constant-response response) {}))]
    (is (thrown? AssertionError (handler {})))))

(deftest wrap-cookies-accepts-max-age
  (let [cookies {"a" {:value "b", :path "/",
                      :secure true, :http-only true,
                      :max-age 123}}
        handler (constant-response {:cookies cookies})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (=cookie {"Set-Cookie" (list "a=b;Path=/;Secure;HttpOnly;Max-Age=123")}
           (:headers resp)))))

(deftest wrap-cookies-accepts-expires
  (let [cookies {"a" {:value "b", :path "/",
                      :secure true, :http-only true,
                      :expires "123"}}
        handler (constant-response {:cookies cookies})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {})]
    (is (=cookie {"Set-Cookie" (list "a=b;Path=/;Secure;HttpOnly;Expires=123")}
           (:headers resp)))))

(deftest wrap-cookies-accepts-max-age-from-clj-time
  (let [cookies {"a" {:value "b", :path "/",
                      :secure true, :http-only true,
                      :max-age (interval (date-time 2012)
                                         (date-time 2015))}}
        handler (constant-response {:cookies cookies})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {}) 
        max-age 94694400]
    (is (= {"Set-Cookie" (list (str "a=b;Path=/;Secure;HttpOnly;Max-Age=" max-age))}
           (:headers resp)))))

(deftest wrap-cookies-accepts-expires-from-clj-time
  (let [cookies {"a" {:value "b", :path "/",
                      :secure true, :http-only true,
                      :expires (date-time 2015 12 31)}}
        handler (constant-response {:cookies cookies})
        resp    ((async->sync-adapter (wrap-cookies handler {})) {}) 
        expires "Thu, 31 Dec 2015 00:00:00 +0000"]
    (is (= {"Set-Cookie" (list (str "a=b;Path=/;Secure;HttpOnly;Expires=" expires))}
           (:headers resp)))))

(deftest wrap-cookies-throws-exception-when-not-using-intervals-correctly
  (let [cookies {"a" {:value "b", :path "/",
                      :secure true, :http-only true,
                      :expires (interval (date-time 2012)
                                         (date-time 2015))}}
        handler (constant-response {:cookies cookies})]

    (is (thrown? AssertionError ((async->sync-adapter (wrap-cookies handler {})) {})))))

(deftest wrap-cookies-throws-exception-when-not-using-datetime-correctly
  (let [cookies {"a" {:value "b", :path "/",
                      :secure true, :http-only true,
                      :max-age (date-time 2015 12 31)}}
        handler (constant-response {:cookies cookies})]
    (is (thrown? AssertionError ((async->sync-adapter (wrap-cookies handler {})) {})))))

(deftest wrap-file-no-directory
  (is (thrown-with-msg? Exception #".*Directory does not exist.*"
    (wrap-file (constant-response {::response true}) {} "not_here"))))

(def public-dir "test/ring/assets")
(def index-html (File. ^String public-dir "index.html"))
(def foo-html   (File. ^String public-dir "foo.html"))

(def app (async->sync-adapter (wrap-file (constant-response {::response true}) {} public-dir)))

(deftest test-wrap-file-unsafe-method
  (is (::response (app {:request-method :post :uri "/foo"}))))

(deftest test-wrap-file-forbidden-url
  (is (::response (app {:request-method :get :uri "/../foo"}))))

(deftest test-wrap-file-no-file
  (is (::response (app {:request-method :get :uri "/dynamic"}))))

(deftest test-wrap-file-directory
  (let [{:keys [status headers body]} (app {:request-method :get :uri "/"})]
    (is (= 200 status))
    (is (= index-html body))))

(deftest test-wrap-file-with-extension
  (let [{:keys [status headers body]} (app {:request-method :get :uri "/foo.html"})]
    (is (= 200 status))
    (is (= foo-html body))))

(deftest test-wrap-file-no-index
  (let [app  (async->sync-adapter (wrap-file (constant-response {::response true}) {} public-dir {:index-files? false}))
        resp (app {:request-method :get :uri "/"})]
    (is (::response resp))))

(def non-file-app (async->sync-adapter (wrap-file-info (constant-response {:headers {} :body "body"}) {})))

(def known-file (File. "test/ring/assets/plain.txt"))
(def known-file-app (async->sync-adapter (wrap-file-info (constant-response {:headers {} :body known-file}) {})))

(def unknown-file (File. "test/ring/assets/random.xyz"))
(def unknown-file-app (async->sync-adapter (wrap-file-info (constant-response {:headers {} :body unknown-file}) {})))

(defmacro with-last-modified 
  "Lets us use a known file modification time for tests, without permanently changing
   the file's modification time."
  [[file new-time] form]
  `(let [old-time# (.lastModified ~file)] 
     (.setLastModified ~file ~(* new-time 1000))
     (try ~form
          (finally (.setLastModified ~file old-time#)))))

(def custom-type-app
  (async->sync-adapter (wrap-file-info
    (constant-response {:headers {} :body known-file}) {}
    {"txt" "custom/type"})))

(deftest wrap-file-info-non-file-response
  (is (= {:headers {} :body "body"} (non-file-app {}))))

(deftest wrap-file-info-known-file-response
  (with-last-modified [known-file 1263506400]
    (is (= {:headers {"Content-Type"   "text/plain"
                      "Content-Length" "6"
                      "Last-Modified"  "Thu, 14 Jan 2010 22:00:00 +0000"}
            :body    known-file}
           (known-file-app {:headers {}})))))

(deftest wrap-file-info-unknown-file-response
  (with-last-modified [unknown-file 1263506400]
    (is (= {:headers {"Content-Type"   "application/octet-stream"
                      "Content-Length" "7"
                      "Last-Modified"  "Thu, 14 Jan 2010 22:00:00 +0000"}
            :body    unknown-file}
           (unknown-file-app {:headers {}})))))

(deftest wrap-file-info-custom-mime-types
  (with-last-modified [known-file 0]
    (is (= {:headers {"Content-Type"   "custom/type"
                      "Content-Length" "6"
                      "Last-Modified"  "Thu, 01 Jan 1970 00:00:00 +0000"}
            :body known-file}
           (custom-type-app {:headers {}})))))

(deftest wrap-file-info-if-modified-since-hit
  (with-last-modified [known-file 1263506400]
    (is (= {:status  304
            :headers {"Content-Type"   "text/plain"
                      "Content-Length" "0"
                      "Last-Modified"  "Thu, 14 Jan 2010 22:00:00 +0000"}
            :body    ""}
           (known-file-app
             {:headers {"if-modified-since" "Thu, 14 Jan 2010 22:00:00 +0000" }})))))

(deftest wrap-file-info-if-modified-miss
  (with-last-modified [known-file 1263506400]
    (is (= {:headers {"Content-Type" "text/plain"
                      "Content-Length" "6"
                      "Last-Modified" "Thu, 14 Jan 2010 22:00:00 +0000"}
            :body    known-file}
           (known-file-app
             {:headers {"if-modified-since" "Wed, 13 Jan 2010 22:00:00 +0000"}})))))

(deftest flash-is-added-to-session
  (let [message  {:error "Could not save"}
        handler  (patch-test wrap-flash (constantly {:flash message}))
        response (handler {:session {}})]
    (is (= (:session response) {:_flash message}))))

(deftest flash-is-retrieved-from-session
  (let [message  {:error "Could not save"}
        handler  (async->sync-adapter
                   (wrap-flash
                     (sync->async-adapter (fn [request]
                                            (is (= (:flash request) message))
                                            {})
                                          {}) {}))]
    (handler {:session {:_flash message}})))

(deftest flash-is-removed-after-read
  (let [message  {:error "Could not save"}
        handler  (patch-test wrap-flash (constantly {:session {:foo "bar"}}))
        response (handler {:session {:_flash message}})]
    (is (nil? (:flash response)))
    (is (= (:session response) {:foo "bar"}))))

(deftest flash-doesnt-wipe-session
  (let [message  {:error "Could not save"}
        handler  (patch-test wrap-flash (constantly {:flash message}))
        response (handler {:session {:foo "bar"}})]
    (is (= (:session response) {:foo "bar", :_flash message}))))

(deftest flash-overwrites-nil-session
  (let [message  {:error "Could not save"}
        handler  (patch-test wrap-flash (constantly {:flash message, :session nil}))
        response (handler {:session {:foo "bar"}})]
    (is (= (:session response) {:_flash message}))))

(deftest test-wrap-head
  (let [handler (sync->async-adapter
                  (fn [req]
                    {:status 200
                     :headers {"X-method" (name (:request-method req))}
                     :body "Foobar"}) {})]
    (let [resp ((async->sync-adapter (wrap-head handler {})) {:request-method :head})]
      (is (nil? (:body resp)))
      (is (= "get" (get-in resp [:headers "X-method"]))))
    (let [resp ((async->sync-adapter (wrap-head handler {})) {:request-method :post})]
      (is (= (:body resp) "Foobar"))
      (is (= "post" (get-in resp [:headers "X-method"]))))))

(deftest test-wrap-keyword-params
  (let [wrapped-echo (async->sync-adapter (wrap-keyword-params (sync->async-adapter (fn [h] h) {}) {}))]
    (are [in out] (= out (:params (wrapped-echo {:params in})))
         {"foo" "bar" "biz" "bat"}
         {:foo  "bar" :biz  "bat"}
         {"foo" "bar" "biz" [{"bat" "one"} {"bat" "two"}]}
         {:foo  "bar" :biz  [{:bat "one"}  {:bat  "two"}]}
         {"foo" 1}
         {:foo  1}
         {"foo" 1 "1bar" 2 "baz*" 3 "quz-buz" 4 "biz.bang" 5}
         {:foo 1 "1bar" 2 :baz* 3 :quz-buz 4 "biz.bang" 5}
         {:foo "bar"}
         {:foo "bar"}
         {"foo" {:bar "baz"}}
         {:foo {:bar "baz"}})))

;;TODO continue with multipart params
(defn string-store [item]
  (-> (select-keys item [:filename :content-type])
      (assoc :content (slurp (:stream item)))))

(deftest test-wrap-multipart-params
  (let [form-body (str "--XXXX\r\n"
                       "Content-Disposition: form-data;"
                       "name=\"upload\"; filename=\"test.txt\"\r\n"
                       "Content-Type: text/plain\r\n\r\n"
                       "foo\r\n"
                       "--XXXX\r\n"
                       "Content-Disposition: form-data;"
                       "name=\"baz\"\r\n\r\n"
                       "qux\r\n"
                       "--XXXX--")
        handler (patch-test wrap-multipart-params identity {:store string-store})
        request {:content-type "multipart/form-data; boundary=XXXX"
                 :content-length (count form-body)
                 :params {"foo" "bar"}
                 :body (string-input-stream form-body)}
        response (handler request)]
    (is (= (get-in response [:params "foo"]) "bar"))
    (is (= (get-in response [:params "baz"]) "qux"))
    (let [upload (get-in response [:params "upload"])]
      (is (= (:filename upload)     "test.txt"))
      (is (= (:content-type upload) "text/plain"))
      (is (= (:content upload)      "foo")))))

(deftest test-multiple-params
  (let [form-body (str "--XXXX\r\n"
                       "Content-Disposition: form-data;"
                       "name=\"foo\"\r\n\r\n"
                       "bar\r\n"
                       "--XXXX\r\n"
                       "Content-Disposition: form-data;"
                       "name=\"foo\"\r\n\r\n"
                       "baz\r\n"
                       "--XXXX--")
        handler (patch-test wrap-multipart-params identity {:store string-store})
        request {:content-type "multipart/form-data; boundary=XXXX"
                 :content-length (count form-body)
                 :body (string-input-stream form-body)}
        response (handler request)]
    (is (= (get-in response [:params "foo"])
           ["bar" "baz"]))))

(deftest nested-params-test
  (let [handler (patch-test wrap-nested-params :params)]
    (testing "nested parameter maps"
      (are [p r] (= (handler {:params p}) r)
        {"foo" "bar"}      {"foo" "bar"}
        {"x[y]" "z"}       {"x" {"y" "z"}}
        {"a[b][c]" "d"}    {"a" {"b" {"c" "d"}}}
        {"a" "b", "c" "d"} {"a" "b", "c" "d"}))
    (testing "nested parameter lists"
      (are [p r] (= (handler {:params p}) r)
        {"foo[]" "bar"}         {"foo" ["bar"]}
        {"foo[]" ["bar" "baz"]} {"foo" ["bar" "baz"]}
        {"a[x][]" ["b"], "a[x][][y]" "c"} {"a" {"x" ["b" {"y" "c"}]}}
        {"a[][x]" "c", "a[][y]" "d"}      {"a" [{"y" "d"} {"x" "c"}]}))))

(def wrapped-echo (patch-test wrap-params identity))

(deftest wrap-params-query-params-only
  (let [req  {:query-string "foo=bar&biz=bat%25"}
        resp (wrapped-echo req)]
    (is (= {"foo" "bar" "biz" "bat%"} (:query-params resp)))
    (is (empty? (:form-params resp)))
    (is (= {"foo" "bar" "biz" "bat%"} (:params resp)))))

(deftest wrap-params-query-and-form-params
  (let [req  {:query-string "foo=bar"
              :content-type "application/x-www-form-urlencoded"
              :body         (string-input-stream "biz=bat%25")}
        resp (wrapped-echo req)]
    (is (= {"foo" "bar"}  (:query-params resp)))
    (is (= {"biz" "bat%"} (:form-params resp)))
    (is (= {"foo" "bar" "biz" "bat%"} (:params resp)))))

(deftest wrap-params-not-form-encoded
  (let [req  {:content-type "application/json"
              :body         (string-input-stream "{foo: \"bar\"}")}
        resp (wrapped-echo req)]
    (is (empty? (:form-params resp)))
    (is (empty? (:params resp)))))

(deftest wrap-params-always-assocs-maps
  (let [req  {:query-string ""
              :content-type "application/x-www-form-urlencoded"
              :body         (string-input-stream "")}
        resp (wrapped-echo req)]
    (is (= {} (:query-params resp)))
    (is (= {} (:form-params resp)))
    (is (= {} (:params resp)))))

(deftest wrap-params-encoding
  (let [req  {:character-encoding "UTF-16"
              :content-type "application/x-www-form-urlencoded"
              :body (string-input-stream "hello=world" "UTF-16")}
        resp (wrapped-echo req)]
    (is (= (:params resp) {"hello" "world"}))
    (is (= (:form-params resp) {"hello" "world"}))))

(defn test-handler [request]
  {:status 200
   :headers {}
   :body (string-input-stream "handler")})

(deftest resource-test
  (let [handler (patch-test wrap-resource test-handler "/ring/assets")]
    (are [request body] (= (slurp (:body (handler request))) body)
      {:request-method :get, :uri "/foo.html"}      "foo"
      {:request-method :get, :uri "/index.html"}    "index"
      {:request-method :get, :uri "/bars/foo.html"} "foo"
      {:request-method :get, :uri "/handler"}       "handler"
      {:request-method :post, :uri "/foo.html"}     "handler")))

(defn- make-store [reader writer deleter]
  (reify SessionStore
    (read-session [_ k] (reader k))
    (write-session [_ k s] (writer k s))
    (delete-session [_ k] (deleter k))))

(defn trace-fn [f]
  (let [trace (atom [])]
    (with-meta
      (fn [& args]
        (swap! trace conj args)
        (apply f args))
      {:trace trace})))

(defn trace [f]
  (-> f meta :trace deref))

(deftest session-is-read
  (let [reader   (trace-fn (constantly {:bar "foo"}))
        writer   (trace-fn (constantly nil))
        deleter  (trace-fn (constantly nil))
        store    (make-store reader writer deleter)
        handler  (trace-fn (constantly {}))
        handler* (patch-test wrap-session handler {:store store})]
    (handler* {:cookies {"ring-session" {:value "test"}}})
    (is (= (trace reader) [["test"]]))
    (is (= (trace writer) []))
    (is (= (trace deleter) []))
    (is (= (-> handler trace first first :session)
           {:bar "foo"}))))

(deftest session-is-written
  (let [reader  (trace-fn (constantly {}))
        writer  (trace-fn (constantly nil))
        deleter (trace-fn (constantly nil))
        store   (make-store reader writer deleter)
        handler (constantly {:session {:foo "bar"}})
        handler (patch-test wrap-session handler {:store store})]
    (handler {:cookies {}})
    (is (= (trace reader) [[nil]]))
    (is (= (trace writer) [[nil {:foo "bar"}]]))
    (is (= (trace deleter) []))))

(deftest session-is-deleted
  (let [reader  (trace-fn (constantly {}))
        writer  (trace-fn (constantly nil))
        deleter (trace-fn (constantly nil))
        store   (make-store reader writer deleter)
        handler (constantly {:session nil})
        handler (patch-test wrap-session handler {:store store})]
    (handler {:cookies {"ring-session" {:value "test"}}})
    (is (= (trace reader) [["test"]]))
    (is (= (trace writer) []))
    (is (= (trace deleter) [["test"]]))))

(deftest session-write-outputs-cookie
  (let [store (make-store (constantly {})
                          (constantly "foo:bar")
                          (constantly nil))
        handler (constantly {:session {:foo "bar"}})
        handler (patch-test wrap-session handler {:store store})
        response (handler {:cookies {}})]
    (is (=cookie (get response :headers)
                 {:headers ["ring-session=foo%3Abar;Path=/"]}))))

(deftest session-delete-outputs-cookie
  (let [store (make-store (constantly {:foo "bar"})
                          (constantly nil)
                          (constantly "deleted"))
        handler (constantly {:session nil})
        handler (patch-test wrap-session handler {:store store})
        response (handler {:cookies {"ring-session" {:value "foo:bar"}}})]
    (is (=cookie (get response :headers)
                 {:headers ["ring-session=deleted;Path=/"]}))))

(deftest session-cookie-has-attributes
  (let [store (make-store (constantly {})
                          (constantly "foo:bar")
                          (constantly nil))
    handler (constantly {:session {:foo "bar"}})
    handler (patch-test wrap-session handler {:store store
                                       :cookie-attrs {:max-age 5 :path "/foo"}})
    response (handler {:cookies {}})]
    (is (=cookie (get response :headers)
                 {:headers ["ring-session=foo%3Abar;Max-Age=5;Path=/foo"]}))))

(deftest session-does-not-clobber-response-cookies
  (let [store (make-store (constantly {})
                          (constantly "foo:bar")
                          (constantly nil))
    handler (constantly {:session {:foo "bar"}
                 :cookies {"cookie2" "value2"}})
    handler (patch-test wrap-session handler {:store store :cookie-attrs {:max-age 5}})
    response (handler {:cookies {}})]
    (is (=cookie (get response :headers)
                 {:headers ["ring-session=foo%3Abar;Max-Age=5;Path=/" "cookie2=value2"]}))))

(deftest session-root-can-be-set
  (let [store (make-store (constantly {})
                          (constantly "foo:bar")
                          (constantly nil))
    handler (constantly {:session {:foo "bar"}})
    handler (patch-test wrap-session handler {:store store, :root "/foo"})
    response (handler {:cookies {}})]
    (is (=cookie (get response :headers)
                 {:headers ["ring-session=foo%3Abar;Path=/foo"]}))))

(deftest session-attrs-can-be-set-per-request
  (let [store (make-store (constantly {})
                          (constantly "foo:bar")
                          (constantly nil))
    handler (constantly {:session {:foo "bar"}
                             :session-cookie-attrs {:max-age 5}})
    handler (patch-test wrap-session handler {:store store})
    response (handler {:cookies {}})]
    (is (=cookie (get response :headers)
                 {:headers ["ring-session=foo%3Abar;Max-Age=5;Path=/"]}))))

(deftest session-made-up-key
  (let [store-ref (atom {})
        store     (make-store
                   #(@store-ref %)
                   #(do (swap! store-ref assoc %1 %2) %1)
                   #(do (swap! store-ref dissoc %) nil))
        handler   (patch-test wrap-session
                   (constantly {:session {:foo "bar"}})
                   {:store store})]
    (handler {:cookies {"ring-session" {:value "faked-key"}}})
    (is (not (contains? @store-ref "faked-key")))))

(deftest session-request-test
  (is (fn? session-request)))

(deftest session-response-test
  (is (fn? session-response)))

(deftest session-cookie-attrs-change
  (let [a-resp   (atom {:session {:foo "bar"}})
        handler  (patch-test wrap-session (fn [req] @a-resp))
        response (handler {})
        sess-key (->> (get-in response [:headers "Set-Cookie"])
                      (first)
                      (re-find #"(?<==)[^;]+"))]
    (is (not (nil? sess-key)))
    (reset! a-resp {:session-cookie-attrs {:max-age 3600}})

    (testing "Session cookie attrs with no active session"
      (is (= (handler {}) {})))

    (testing "Session cookie attrs with active session"
      (let [response (handler {:foo "bar" :cookies {"ring-session" {:value sess-key}}})]
        (is (get-in response [:headers "Set-Cookie"]))))))


