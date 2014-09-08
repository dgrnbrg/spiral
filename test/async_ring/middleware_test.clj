(ns async-ring.middleware-test
  (:use clojure.test
        async-ring.core
        [clojure.string :only (split)]
        [clj-time.core :only (interval date-time)]  
        async-ring.middleware :reload))

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
