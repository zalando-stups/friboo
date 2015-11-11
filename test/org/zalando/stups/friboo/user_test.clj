(ns org.zalando.stups.friboo.user-test
  (:require
    [clojure.test :refer :all]
    [org.zalando.stups.friboo.user :refer :all]
    [org.zalando.stups.friboo.test-utils :refer :all]
    [clj-http.client :as http])
  (:import (clojure.lang ExceptionInfo)))

(deftest test-trim-slashes
  (are [input expected-output] (= (trim-realm input) expected-output)
                               "employees" "employees"
                               "/employees" "employees"
                               "employees/" "employees"
                               "/employees/" "employees"
                               "//employees//" "employees"
                               "/" "/"
                               "employees/tech" "employees/tech"
                               "/employees/tech" "employees/tech"
                               "employees/tech/" "employees/tech"
                               "/employees/tech/" "employees/tech"))

(deftest test-trim-slashes-resilience-empty
  (is (= "" (trim-realm ""))))

(deftest test-trim-slashes-resilience-nil
  (is (= nil (trim-realm nil))))

(deftest test-require-realm
  (is (= "employees"
         (require-realms #{"employees"} {:tokeninfo {"realm" "employees"}}))))

(deftest test-require-realm-with-leading-slash
  (is (= "employees" (require-realms #{"employees"} {:tokeninfo {"realm" "/employees"}}))))

(deftest test-require-missing-realm
  (let [ex (is (thrown? ExceptionInfo (require-realms #{"services"} {:tokeninfo {"realm" "employees"}})))]
    (is (= (-> ex .getData :http-code) 403))))

(deftest test-get-teams-cache
  (let [calls (atom [])]
    (with-redefs [http/get (comp (constantly {:body [{:name "team1"} {:name "team2"}]})
                                 (track calls :http-get))]
      (is (= (get-teams "https://teams.example.org" "TOKEN12345" "a-user") [{:name "team1"} {:name "team2"}]))
      (is (= (count @calls) 1))
      (is (= (:args (first @calls))
             ["https://teams.example.org/api/accounts/aws" {:query-params {:member "a-user"}
                                                            :oauth-token "TOKEN12345"
                                                            :as :json}]))
      (is (= (get-teams "https://teams.example.org" "TOKEN12345" "a-user") [{:name "team1"} {:name "team2"}]))
      ; we should have a cache hit, i.e. no HTTP call this time..
      (is (= (count @calls) 1)))))

(deftest test-require-teams
  (testing "should call get-teams if in employees realm"
    (let [calls (atom [])
          mock-teams [{:name "team1"} {:name "team2"}]]
      (with-redefs [get-teams (comp (constantly mock-teams)
                                    (track calls :get-teams))
                    get-service-teams (track calls :get-service-teams)]
        (let [teams (require-teams "user"
                                   {:tokeninfo {"realm" "employees"}}
                                   "team-api"
                                   "service-api")]
          (same! (count @calls) 1)
          (same! (:key (first @calls))
                 :get-teams)
          (same! (count teams) 2)))))

  (testing "should call get-service-teams if in services realm"
    (let [calls (atom [])
          mock-team "team1"]
      (with-redefs [get-teams (track calls :get-teams)
                    get-service-teams (comp (constantly [{:name mock-team}])
                                            (track calls :get-service-teams))]
        (let [teams (require-teams "user"
                                   {:tokeninfo {"realm" "services"}}
                                   "team-api"
                                   "service-api")]
          (same! (count @calls) 1)
          (same! (:key (first @calls))
                 :get-service-teams)
          (same! (count teams) 1)
          (same! (first teams) mock-team)))))

  (testing "should throw 403 if in another realm"
    (try
      (require-teams "user"
                     {:tokeninfo {"realm" "foo"}}
                     "team-api"
                     "service-api")
      (is false)
      (catch ExceptionInfo ex
        (let [data (ex-data ex)]
          (same! 403 (:http-code data)))))))
