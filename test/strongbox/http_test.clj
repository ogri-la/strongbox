(ns strongbox.http-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.spec.alpha :as s]
   [clj-http.fake :refer [with-fake-routes-in-isolation]]
   [strongbox
    [logging :as logging]
    [http :as http]]))

(deftest download-404
  (testing "regular (non-streaming) download that yields a 404 returns an error map"
    (let [url "https://www.curseforge.com/wow/addons/brewmastertools/files"
          fake-routes {url {:get (fn [req] {:status 404 :reason-phrase "Not Found" :body "<h1>Not Found</h1>"})}}]
      (with-fake-routes-in-isolation fake-routes
        (let [result (http/download url)
              expected {:status 404 :reason-phrase "Not Found" :host "www.curseforge.com"}]
          (is (s/valid? :http/error result))
          (is (= expected result)))))))

(deftest url-to-filename
  (testing "urls can be converted to filenames safe for a filesystem"
    (let [cases [["https://user:name@example.org/foo#anchor?bar=baz&baz=bar"
                  "aHR0cHM6Ly91c2VyOm5hbWVAZXhhbXBsZS5vcmcvZm9vI2FuY2hvcj9iYXI9YmF6JmJhej1iYXI=.html"]

                 ;; forward slashes are replaced with underscores
                 ["https://example.org/?"
                  "aHR0cHM6Ly9leGFtcGxlLm9yZy8_.html"]

                 ;; extensions are preserved if possible
                 ["https://example.org/foo.asdf"
                  "aHR0cHM6Ly9leGFtcGxlLm9yZy9mb28uYXNkZg==.asdf"]]]
      (doseq [[given expected] cases]
        (is (= expected (http/url-to-filename given)))))))

(deftest user-agent
  (testing "user agent version number"
    (let [cases [["0.1.0" "strongbox/0.1 (https://github.com/ogri-la/strongbox)"]
                 ["0.1.0-unreleased" "strongbox/0.1-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["0.10.0" "strongbox/0.10 (https://github.com/ogri-la/strongbox)"]
                 ["0.10.0-unreleased" "strongbox/0.10-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["0.10.10" "strongbox/0.10 (https://github.com/ogri-la/strongbox)"]
                 ["0.10.10-unreleased" "strongbox/0.10-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["10.10.10" "strongbox/10.10 (https://github.com/ogri-la/strongbox)"]
                 ["10.10.10-unreleased" "strongbox/10.10-unreleased (https://github.com/ogri-la/strongbox)"]

                 ["991.992.993-unreleased" "strongbox/991.992-unreleased (https://github.com/ogri-la/strongbox)"]]]

      (doseq [[given expected] cases]
        (is (= expected (http/strongbox-user-agent given)))))))

(deftest user-agent-present
  (testing "user agent is present on outgoing requests"
    (let [url "http://localhost/"
          fake-routes {url {:get (fn [req]
                                   {:status 200 :body "" :headers (:headers req)})}}
          output-file nil
          message nil
          params {}
          use-anon? false
          expected (http/user-agent use-anon?)]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (-> url
                            (http/-download output-file message params)
                            :headers
                            (get "user-agent"))))))))

(comment
  "doesn't work as expected. The fake response doesn't trigger a redirect. Not sure why."
  (deftest http-redirect
    (testing ""
      (let [url "https://edge.forgecdn.net/files/3135/377/MobInfo2-8.3.15+Classic.zip"
            redirect "https://media.forgecdn.net/files/3135/377/MobInfo2-8.3.15%2BClassic.zip"
            bad-redirect "https://media.forgecdn.net/files/3135/377/MobInfo2-8.3.15+Classic.zip"
            fake-routes {url {:get (fn [req]
                                     {:status 302 :reason-phrase "Moved temporarily"
                                      :headers {"location" redirect}})}
                         redirect {:get (fn [req]
                                          {:status 200 :body "woo!"})}
                         bad-redirect {:get (fn [req]
                                              {:status 500 :body "boo!"})}}
            default-request-config {:http-request-config (clj-http.core/request-config {})}
            expected-good "woo!"
            expected-bad "boo!"]
        (with-fake-routes-in-isolation fake-routes
          (is (= expected-bad (http/-download url nil "message" default-request-config)))
          (is (= expected-good (http/download url))))))))

(deftest socket-timeout
  (testing "timeouts are caught and a synthetic http response is returned"
    (let [url "http://foo.bar/"
          fake-routes {url {:get (fn [req]
                                   (throw (java.net.SocketTimeoutException. "Read timed out")))}}
          output-file nil
          message nil
          extra-params {}
          expected {:host "foo.bar", :reason-phrase "Connection timed out", :status 608}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (http/-download url output-file message extra-params)))))))

(deftest connect-exception
  (testing "when the server is refusing connections treat case like a read timeout with a synthetic http response"
    (let [url "http://foo.bar/"
          fake-routes {url {:get (fn [req]
                                   (throw (java.net.ConnectException. "Connection timed out")))}}
          output-file nil
          message nil
          extra-params {}
          expected {:host "foo.bar", :reason-phrase "Connection timed out", :status 608}]
      (with-fake-routes-in-isolation fake-routes
        (is (= expected (http/-download url output-file message extra-params)))))))

(deftest http-error
  (let [cases [;; matching host+status
               [{:host "raw.githubusercontent.com" :status 500 :reason-phrase "Internal server error."}
                "Github: service is down. Check www.githubstatus.com and try again later."]
               [{:host "api.github.com" :status 500 :reason-phrase "Internal server error."}
                "Github: api is down. Check www.githubstatus.com and try again later."]

               ;; match status alone
               [{:host "example.org" :status 503 :reason-phrase "java.net.UnknownHostException: host 'example.org' not found."}
                "Not found: the host is down (unlikely) or your connection to the internet is down (more likely)."]

               ;; matches nothing
               [{:host "example.org" :status 418 :reason-phrase "I'm a teapot"}
                "I'm a teapot"]]]
    (doseq [[request expected] cases]
      (is (= expected (http/http-error request))))))

(deftest download-with-backoff
  (let [expected "finally!"
        expected-warnings
        ["failed to fetch 'http://foo.bar/baz': huh? (HTTP 500)"
         "trying again (attempt 2 of 3)"
         "failed to fetch 'http://foo.bar/baz': wha? (HTTP 501)"
         "trying again (attempt 3 of 3)"]
        attempt (atom 0)
        url "http://foo.bar/baz"
        fake-routes {url {:get (fn [req]
                                 (swap! attempt inc)
                                 (case @attempt
                                   1 {:status 500 :reason-phrase "huh?"}
                                   2 {:status 501 :reason-phrase "wha?"}
                                   3 {:status 200 :body expected}))}}]
    (with-fake-routes-in-isolation fake-routes
      (with-redefs [http/*default-attempts* 3] ;; re-re-defined from main.clj
        (is (= expected-warnings
               (logging/buffered-log
                :warn
                (is (= expected (http/download-with-backoff url))))))))))
