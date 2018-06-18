(ns artstor-group-service.core-test
  (:require [clojure.test :refer :all]
            [artstor-group-service.schema :refer :all]
            [artstor-group-service.schema :as data]
            [clojure.tools.logging :as logger]
            [clojurewerkz.elastisch.rest.document :as es-client]
            [cheshire.core :as cheshire]
            [artstor-group-service.core :as core]
            [schema.core :as s]
            [ring.mock.request :as mock]
            [schema.coerce :as coerce]
            [ragtime.repl :refer [migrate rollback]]
            [environ.core :refer [env]]
            [ragtime.jdbc :as jdbc]
            [artstor-group-service.schema :as data]
            [artstor-group-service.util :as util]
            [clj-sequoia.logging :as cptlog]
            [captains-common.core :refer [wrap-web-logging]]
            [artstor-group-service.repository :as repo]
            [artstor-group-service.service.metadata :as metadata]
            [clojure.string :as string]
            [clojure.data.json :as json])

  (:import (java.util UUID)))

;
(def config {:datastore  (jdbc/sql-database {:connection-uri (env :artstor-group-db-url)})
             :migrations (jdbc/load-resources "test-migrations")})

;;"web-token" header
(def artstor_cookie (artstor-group-service.auth/generate-web-token 299277 1000 false "qa001@artstor.org"))
;;Unauthorized user
(def naughty_user_cookie (artstor-group-service.auth/generate-web-token 111111 1111  false "baddog@artstor.org"))
;; invalid cookie
(def invalid_cookie (str "aadsfasdfasdfasdfasdfasdfasdfasdfasdfasdf:asdfasdfasdf:ASdfasdf:Adsfadsf"))
;;Group 2 writer
(def my_penpals_cookie  (artstor-group-service.auth/generate-web-token 123456 1000  false "professor@artstor.org"))

;code borrowed from asynchronizer
(defn- match? [expected-log actual-log]
  (= expected-log
     (select-keys actual-log
                  (keys expected-log))))

;code borrowed from asynchronizer
(defn included? [expected logs]
  (some (partial match? expected) logs))

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(defn parse-body [body]


  (cheshire/parse-string body true))

(deftest nils-in-db
  (let [app core/app
        group-coercer (coerce/coercer Group coerce/json-coercion-matcher)]
   (with-db config
            (testing "Test GET a particular group with no items or tags"
              (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/3") "web-token" artstor_cookie)))
                    body (parse-body (slurp (:body response)))
                    group (group-coercer body)]
                (is (not (= [nil] (group :items))))
                (is (= (:status response) 200))
                (is (nil? (s/check Group group))))))))

(deftest test-spec-changes-catch-institution-permission-problems
  (let [app core/app
        group-coercer (coerce/coercer Group coerce/json-coercion-matcher)]
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (testing "Test POST a new group"
        (with-db config
                 (let [json-body (cheshire/generate-string {:name "My fancy group" :description "This is my group"
                                                            :sequence_number 1 :access [{:entity_type 200
                                                                                         :entity_identifier "299277"
                                                                                         :access_type 200}]
                                                            :items ["objectid1" "objectid2"]
                                                            :tags ["tag1" "tag2"]})
                       ;Call POST twice.  Once to capture the Captains Logging, second to test the actual response
                       {:keys [logs _]} (cptlog/collect-parsed-logs
                                          (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                                   (mock/content-type "application/json"))))
                       response (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       raw_body (slurp (:body response))
                       body (parse-body raw_body)
                       group (group-coercer body)]
                   (is (= (:status response) 400))))))))

(deftest test-view-image-group-log-message-owner-vs-non-owner
  (let [app core/app]
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))]
      (testing "Test log message for owner reading image group"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                        :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                    {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
          (with-db config
                   (let [{:keys [logs _]} (cptlog/collect-parsed-logs
                                            (app (-> (mock/header (mock/request :get "/api/v1/group/2") "web-token" artstor_cookie))))]
                     (is (included? {:eventtype  "artstor_read_group"
                                     :dests ["captains-log"]
                                     :profileid "299277"}
                                    logs))))))
      (testing "Test log message for non-owner reading image group"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                        :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                    {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 123456}])})]
          (with-db config
                   (let [{:keys [logs _]} (cptlog/collect-parsed-logs
                                            (app (-> (mock/header (mock/request :get "/api/v1/group/2") "web-token" my_penpals_cookie))))]
                     (is (included? {:eventtype  "artstor_view_image_group"
                                     :dests ["captains-log"]
                                     :profileid "123456"}
                                    logs)))))))))

(deftest test-view-image-group-log-message-internal-flag
  (let [app core/app]
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))]
      (testing "Test log message for owner reading image group"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                        :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                    {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
          (with-db config
                   (let [{:keys [logs _]} (cptlog/collect-parsed-logs
                                            (app (-> (mock/header (mock/request :get "/api/v1/group/2?internal=true") "web-token" artstor_cookie))))]
                     (is (included? {:eventtype  "artstor_read_group_internal"
                                     :dests ["captains-log"]
                                     :profileid "299277"}
                                    logs))))))
      (testing "Test log message for non-owner reading image group"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                        :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                    {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 123456}])})]
          (with-db config
                   (let [{:keys [logs _]} (cptlog/collect-parsed-logs
                                            (app (-> (mock/header (mock/request :get "/api/v1/group/2?internal=true") "web-token" my_penpals_cookie))))]
                     (is (included? {:eventtype  "artstor_view_image_group_internal"
                                     :dests ["captains-log"]
                                     :profileid "123456"}
                                    logs)))))))))

(deftest api-tests
  (let [app core/app
        group-coercer (coerce/coercer Group coerce/json-coercion-matcher)
        groups-coercer (coerce/coercer [Group] coerce/json-coercion-matcher)
        status-coercer (coerce/coercer RequestStatus coerce/json-coercion-matcher)]
    ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))]
      (testing "test with redef iac services with artstor cookie"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                        :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                    {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
          (with-db config
                   (testing "Test GETting a list of image groups"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group") "web-token" artstor_cookie)))
                           body (parse-body (slurp (:body response)))
                           groups (groups-coercer body)]
                       (is (= (:status response) 200)))))
          (with-db config
                   (testing "Test GET a particular group"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/1") "web-token" artstor_cookie)))
                           body (parse-body (slurp (:body response)))
                           group (group-coercer body)]
                       (is (= (:status response) 200))
                       (is (contains? group :creation_date))
                       (is (not (nil? (group :creation_date))))
                       (is (contains? group :update_date))
                       (is (not (nil? (group :update_date))))
                       (is (nil? (s/check Group group))))))
          (with-db config
                   (testing "Test GET a particular group with no items or tags"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/3") "web-token" artstor_cookie)))
                           body (parse-body (slurp (:body response)))
                           group (group-coercer body)]
                       (is (= (:status response) 200))
                       (is (contains? group :creation_date))
                       (is (not (nil? (group :creation_date))))
                       (is (contains? group :update_date))
                       (is (not (nil? (group :update_date))))
                       (is (nil? (s/check Group group))))))
          (with-db config
                   (testing "Test GET a particular group not found"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/00000") "web-token" artstor_cookie)))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 404))
                       (is (= (nil? (s/check data/RequestStatus status-response)))))))


          (with-db config
                   (testing "Test POST a new group"

                     (let [json-body (cheshire/generate-string {:name "My fancy group" :description "This is my group"
                                                                :sequence_number 1 :access [{:entity_type 100
                                                                                             :entity_identifier "299277"
                                                                                             :access_type 200}]
                                                                :items ["objectid1" "objectid2"]
                                                                :tags ["tag1" "tag2"]})
                           ;Call POST twice.  Once to capture the Captains Logging, second to test the actual response
                           {:keys [logs _]} (cptlog/collect-parsed-logs
                                              (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                                       (mock/content-type "application/json"))))
                           response (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw_body (slurp (:body response))
                           body (parse-body raw_body)
                           group (group-coercer body)]
                       (is (= (:status response) 200))
                       (is (nil? (s/check Group group)))
                       (is (contains? group :creation_date))
                       (is (not (nil? (group :creation_date))))
                       (is (contains? group :update_date))
                       (is (not (nil? (group :update_date))))
                       (is (= (group :creation_date) (group :update_date)))
                       (is (included? {:eventtype  "artstor_create_group"
                                       :dests ["captains-log"]
                                       :profileid "299277"}
                                      logs)))))

          (with-db config
                   (testing "Test POST an invalid group name"
                     (let [json-body (cheshire/generate-string {:name "My" :description "This is my group"
                                                                :sequence_number 1 :access [{:entity_type 100
                                                                                             :entity_identifier "Will"
                                                                                             :access_type 200}]
                                                                :items ["objectid1" "objectid2"]
                                                                :tags ["tag1" "tag2"]})
                           response (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw_body (slurp (:body response))
                           body (parse-body raw_body)
                           group (group-coercer body)]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test POST an invalid tags"
                     (let [json-body (cheshire/generate-string {:name "Bad Tags Group" :description "This is my group"
                                                                :sequence_number 1 :access [{:entity_type 100
                                                                                             :entity_identifier "Will"
                                                                                             :access_type 200}]
                                                                :items ["objectid1" "objectid2"]
                                                                :tags ["" "tag2"]})
                           response (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw_body (slurp (:body response))
                           body (parse-body raw_body)
                           group (group-coercer body)]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test POSTing nils in items and tags"
                     (let [json-body (cheshire/generate-string {:name "Bad Tags Group" :description "This is my group"
                                                                :sequence_number 1 :access [{:entity_type 100
                                                                                             :entity_identifier "Will"
                                                                                             :access_type 200}]
                                                                :items [nil]
                                                                :tags [nil]})
                           response (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw_body (slurp (:body response))
                           body (parse-body raw_body)
                           group (group-coercer body)]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test copying a group"
                     (let [json-body (cheshire/generate-string {:name "I'm a copy -- Rabbit"})
                           ;Call POST twice.  Once to capture the Captains Logging, second to test the actual response
                           {:keys [logs _]} (cptlog/collect-parsed-logs
                                              (app (-> (mock/header (mock/request :post "/api/v1/group/1/copy" json-body) "web-token" artstor_cookie)
                                                       (mock/content-type "application/json"))))
                           response (app (-> (mock/header (mock/request :post "/api/v1/group/1/copy" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw_body (slurp (:body response))
                           body (parse-body raw_body)
                           group (group-coercer body)]
                       (is (= (:status response) 200))
                       (is (nil? (s/check Group group)))
                       (is (= (group :description) "This is the first group"))
                       (is (contains? group :creation_date))
                       (is (not (nil? (group :creation_date))))
                       (is (contains? group :update_date))
                       (is (not (nil? (group :update_date))))
                       (is (= (group :creation_date) (group :update_date)))
                       (is (included? {:eventtype  "artstor_copy_group"
                                       :dests ["captains-log"]
                                       :profileid "299277"}
                                      logs)))))
          (with-db config
                   (testing "Test copying a non-existant group"
                     (let [json-body (cheshire/generate-string {:name "Ain't gonna work"})
                           ;Call POST twice.  Once to capture the Captains Logging, second to test the actual response
                           {:keys [logs _]} (cptlog/collect-parsed-logs
                                              (app (-> (mock/header (mock/request :post "/api/v1/group/no-group/copy" json-body) "web-token" artstor_cookie)
                                                       (mock/content-type "application/json"))))
                           response (app (-> (mock/header (mock/request :post "/api/v1/group/no-group/copy" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 404))
                       (is (nil? (s/check RequestStatus status-response)))
                       (is (included? {:eventtype  "artstor_copy_group"
                                       :dests ["captains-log"]
                                       :profileid "299277"
                                       :status 404}
                                      logs)))))
          (with-db config
                   (testing "Test copying a non-existant group with Cookies Header"
                     (let [json-body (cheshire/generate-string {:name "Ain't gonna work"})

                           ;Call POST twice.  Once to capture the Captains Logging, second to test the actual response
                           {:keys [logs _]} (cptlog/collect-parsed-logs
                                              (app (-> (mock/header (mock/request :post "/api/v1/group/no-group/copy" json-body) "web-token" artstor_cookie)
                                                       (mock/content-type "application/json"))))
                           response (app (-> (mock/request :post "/api/v1/group/no-group/copy" json-body)
                                             (mock/header  "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 404))
                       (is (nil? (s/check RequestStatus status-response)))
                       (is (included? {:eventtype  "artstor_copy_group"
                                       :dests ["captains-log"]
                                       :profileid "299277"
                                       :status 404}
                                      logs)))))
          (with-db config
                   (testing "Test get meta data for a non-existant group"
                     (let [json-body (cheshire/generate-string {:name "Ain't gonna work"})
                           response (app (-> (mock/header (mock/request :post "/api/v1/group/no-group/metadata" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 404))
                       (is (nil? (s/check RequestStatus status-response))))))
          (with-db config
                   (testing "Test copying with bad new group name"
                     (let [json-body (cheshire/generate-string {:name "AI"})
                           response (app (-> (mock/header (mock/request :post "/api/v1/group/1/copy" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test POST with invalid group"
                     (let  [json-body (cheshire/generate-string {:name "My fancy group" :description "This is my invalid group"})
                            response (app (-> (mock/header (mock/request :post "/api/v1/group" json-body) "web-token" artstor_cookie)
                                              (mock/content-type "application/json")))
                            body (parse-body (slurp (:body response)))
                            group (group-coercer body)]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test PUT to updated group 1"
                     (let [json-body (cheshire/generate-string {:name "My first fancy group" :description "This is my group"
                                                                :sequence_number 10 :access [{:entity_type 100
                                                                                              :entity_identifier "299277"
                                                                                              :access_type 300}]
                                                                :items ["objectid5" "objectid6"]
                                                                :tags ["tag1" "tag2" "tag3"]})
                           ;Call PUT twice.  Once to capture the Captains Logging, second to test the actual response
                           {:keys [logs _]} (cptlog/collect-parsed-logs
                                              (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" artstor_cookie)
                                                       (mock/content-type "application/json"))))
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           body (parse-body (slurp (:body response)))
                           group (group-coercer body)]
                       (is (= (:status response) 200))
                       (is (= "My first fancy group" (get group :name)))
                       (is (nil? (s/check Group group)))
                       (is (contains? group :creation_date))
                       (is (not (nil? (group :creation_date))))
                       (is (contains? group :update_date))
                       (is (not (nil? (group :update_date))))
                       (is (included? {:eventtype  "artstor_update_group"
                                       :dests ["captains-log"]
                                       :profileid "299277"
                                       :group_id "1"}
                                      logs)))))
          (with-db config
                   (testing "Test PUTTing nulls into the database"
                     (let [json-body (cheshire/generate-string {:name "My first fancy group" :description "This is my group"
                                                                :sequence_number 10 :access [{:entity_type 100
                                                                                              :entity_identifier "299277"
                                                                                              :access_type 300}]
                                                                :items [nil]
                                                                :tags [nil]})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           body (parse-body (slurp (:body response)))
                           group (group-coercer body)
                           logevent (fn [])]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test PUT to update with invalid group"
                     (let [json-body (cheshire/generate-string { :name "My fancy group" :description "This is my invalid group"})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           body (parse-body (slurp (:body response)))]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test PUT to update with invalid group Name"
                     (let [json-body (cheshire/generate-string {:name "B" :description "This is my group"
                                                                :sequence_number 1 :access [{:entity_type 100
                                                                                             :entity_identifier "Will"
                                                                                             :access_type 200}]
                                                                :items ["objectid1" "objectid2"]
                                                                :tags ["tag1" "tag2"]})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           body (parse-body (slurp (:body response)))]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test PUT to update with invalid items"
                     (let [json-body (cheshire/generate-string {:name "Bad Items Group" :description "This is my group"
                                                                :sequence_number 1 :access [{:entity_type 100
                                                                                             :entity_identifier "Will"
                                                                                             :access_type 200}]
                                                                :items ["objectid1" ""]
                                                                :tags ["tag1" "tag2"]})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           body (parse-body (slurp (:body response)))]
                       (is (= (:status response) 400)))))
          (with-db config
                   (testing "Test PUT to update with group not found"
                     (let [json-body (cheshire/generate-string {:name "My non-existing group" :description "This is my group"
                                                                :sequence_number 4 :access [{:entity_type 200
                                                                                             :entity_identifier "Artstor"
                                                                                             :access_type 300}]
                                                                :items ["objectid10"]
                                                                :tags ["tag1" "tag2" "tag3"]})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/not-found-anywhere" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 404))
                       (is (= (nil? (s/check data/RequestStatus status-response)))))))
          (with-db config
                   (testing "Test PUT to update public flag with Artstor Admin"
                     (let [json-body (cheshire/generate-string {:public true})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1/admin/public" json-body) "web-token" artstor_cookie)
                                             (mock/content-type "application/json")))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           group (group-coercer body)]
                       (is (= (:status response) 200))
                       (is (= "First Group" (get group :name)))
                       (is (= true (get group :public)))
                       (is (nil? (s/check Group group))))))
          (with-db config
                   (testing "Test DELETE a particular group"
                     (let [response (app (-> (mock/header (mock/request :delete "/api/v1/group/1") "web-token" artstor_cookie)))
                           ;Call DELETE twice.  Once to test the actual response, once to capture the Captains Logging
                           {:keys [logs _]} (cptlog/collect-parsed-logs
                                              (app (-> (mock/header (mock/request :delete "/api/v1/group/2") "web-token" artstor_cookie))))]
                       (is (= (:status response) 200))
                       (is (included? {:eventtype  "artstor_delete_group"
                                       :dests ["captains-log"]
                                       :profileid "299277"}
                                      logs)))
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/1") "web-token" artstor_cookie)))
                           raw-body (slurp (:body response))
                           body (parse-body raw-body)
                           status-response (status-coercer body)]
                       (is (= (:status response) 404))
                       (is (= (nil? (s/check data/RequestStatus status-response)))))))))
      (testing "test with redef iac services with naughty cookie"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                        :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                    {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1111)) :type "Institution"}]})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 111111}])})]
          (with-db config
                   (testing "Test PUT to update public flag with Bad Dog"
                     (let [json-body (cheshire/generate-string {:public true})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1/admin/public" json-body) "web-token" naughty_user_cookie)
                                             (mock/content-type "application/json")))]
                       (is (= (:status response) 403)))))
          (with-db config
                   (testing "Test BadDog is authenticated for Search"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group") "web-token" naughty_user_cookie)))]
                       (is (= (:status response) 200)))
                     (testing "Test Authenticated OK, but NOT ALLOWED for GET individual group"
                       (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/1") "web-token" naughty_user_cookie)))]
                         (is (= (:status response) 403))))
                     (testing "Test Authenticated OK, and ALLOWED to GET Public group"
                       (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/294484") "web-token" naughty_user_cookie)))]
                         (is (= (:status response) 200))))
                     (testing "Test Authenticated OK, but NOT ALLOWED for PUT"
                       (let [json-body (cheshire/generate-string {:name "My first fancy group" :description "This is my group"
                                                                  :sequence_number 10 :access [{:entity_type 200
                                                                                                :entity_identifier "Artstor"
                                                                                                :access_type 300}]
                                                                  :items ["objectid5" "objectid6"]
                                                                  :tags ["tag1" "tag2" "tag3"]})
                             response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" naughty_user_cookie)
                                               (mock/content-type "application/json")))]
                         (is (= (:status response) 403))))
                     (testing "Test Authenticated OK, but NOT ALLOWED for delete"
                       (let [response (app (-> (mock/header (mock/request :delete "/api/v1/group/1") "web-token" naughty_user_cookie)))]
                         (is (= (:status response) 403))))))))
      (testing "test with redef iac services with nil profile id"
        (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth false})
                      org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId nil}])})]
          (with-db config
                   (testing "Test NOT AUTHORIZED for GET Search"
                     (let [response (app (-> (mock/request :get "/api/v1/group")))]
                       (is (= (:status response) 401))))        ; Public groups mean anyone can search but has to be logged in or ip authorized
                   (testing "Test NOT AUTHORIZED for GET individual group"
                     (let [response (app (-> (mock/request :get "/api/v1/group/1")))]
                       (is (= (:status response) 401))))
                   (testing "Test NOT AUTHORIZED for GET Public group (not logged in, no cookie)"
                     (let [response (app (-> (mock/request :get "/api/v1/group/294484")))]
                       (is (= (:status response) 401))))
                   (testing "Test NOT AUTHORIZED for POST"
                     (let [response (app (-> (mock/request :post "/api/v1/group")))]
                       (is (= (:status response) 401))))
                   (testing "Test NOT AUTHORIZED for POST/copy"
                     (let [response (app (-> (mock/request :post "/api/v1/group/1/copy")))]
                       (is (= (:status response) 401))))
                   (testing "Test NOT AUTHORIZED for PUT"
                     (let [response (app (-> (mock/request :put "/api/v1/group/1")))]
                       (is (= (:status response) 401))))
                   (testing "Test NOT AUTHORIZED for delete"
                     (let [response (app (-> (mock/request :delete "/api/v1/group/1")))]
                       (is (= (:status response) 401)))))
          (with-db config
                   (testing "Test invalid cookie NOT AUTHORIZED to access groups"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group") "web-token" invalid_cookie)))]
                       (is (= (:status response) 401))))
                   (testing "Test invalid cookie NOT AUTHORIZED for GET individual group"
                     (let [response (app (-> (mock/header (mock/request :get "/api/v1/group/1") "web-token" invalid_cookie)))]
                       (is (= (:status response) 401))))
                   (testing "Test invalid cookie NOT AUTHORIZED for PUT"
                     (let [json-body (cheshire/generate-string {:name "My first fancy group" :description "This is my group"
                                                                :sequence_number 10 :access [{:entity_type 200
                                                                                              :entity_identifier "Artstor"
                                                                                              :access_type 300}]
                                                                :items ["objectid5" "objectid6"]
                                                                :tags ["tag1" "tag2" "tag3"]})
                           response (app (-> (mock/header (mock/request :put "/api/v1/group/1" json-body) "web-token" invalid_cookie)
                                             (mock/content-type "application/json")))]
                       (is (= (:status response) 401))))
                   (testing "Test invalid cookie NOT AUTHORIZED for delete"
                     (let [response (app (-> (mock/header (mock/request :delete "/api/v1/group/1") "web-token" invalid_cookie)))]
                       (is (= (:status response) 401))))))))))

(deftest api-metadata-tests
  (let [app core/app
        group-id 1
        group {:something 1}
        user [{:institution_ids [1000]}]
        metadata {:body {:resolution_x 600,
                         :object_id "obj1",
                         :collection_id "37510",
                         :object_type_id 10,
                         :width 683,
                         :collection_name "Collection Name 4",
                         :image_url "largeImageUrl",
                         :resolution_y 600,
                         :height 1024,
                         :download_size "1024,1024"}}]
    ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
    (with-redefs [repo/get-group (fn [id] group)
                  metadata/get-metadata (fn [group-id object_ids legacy xml institute_id] metadata)
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str 1000) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/art-aa (fn [_ _] {:status 200 :body (json/write-str {:user {:institutionId 1000}})})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (testing "Test retrieving group metadata"
        (with-db config
                 (let [req (-> (mock/request :get  "/api/v1/group/1/metadata?object_ids=obj1&legacy=true&xml=false")
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "fastly-client-ip" "10.10.10.2"))
                       response (app req)
                       raw_body (slurp (:body response))
                       body (parse-body raw_body)]
                   (is (= (:status response) 200))
                   (is (= (body :image_url) "largeImageUrl"))
                   (is (= (body :download_size) "1024,1024"))))))))

(deftest api-logging-tests
  (let [app core/app
        group-coercer (coerce/coercer Group coerce/json-coercion-matcher)
        groups-coercer (coerce/coercer [Group] coerce/json-coercion-matcher)
        status-coercer (coerce/coercer RequestStatus coerce/json-coercion-matcher)
        group {:institution_ids [1000]}
        metadata {:success true,
                  :total 1
                  :metadata [{:resolution_x 600
                              :object_id "SS37510_37510_38738213"
                              :collection_id "37510"
                              :object_type_id 10
                              :width 683
                              :collection_name "Collection Name 4"
                              :image_url "sslps/c37510/19243617.fpx/GKZ8tPTq8QwNLUDG9ncDbw/1510339322/"
                              :resolution_y 600
                              :height 1024
                              :download_size "1024,1024"}]}]
    ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str 1000) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/art-aa (fn [_ _] {:status 200 :body (json/write-str {:user {:institutionId 1000}})})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
    (with-db config
         (testing "Test copying a group"
           (let [json-body (cheshire/generate-string {:name "I'm a copy -- Rabbit"})
                 ;Call POST twice.  Once to capture the Captains Logging, second to test the actual response
                 {:keys [logs _]} (cptlog/collect-parsed-logs
                                    (app (-> (mock/request :post "/api/v1/group/1/copy" json-body)
                                             (mock/header "web-token" artstor_cookie)
                                             (mock/header "Cookie" "AccessSession=H4sIAAAAAAAAAHVRu27cMBDs7ysE1dFll2-mO7-QIJ3tVEGKFR-JDPl8kKgAgeF_j0TpLB2CFCTAmdnZ4e7rrijKxpfFp6L0EOtAjpNUKGJNJrIodJSOhNUKZPlhErtF3ZCriAj0DJ8WWHorFdfcQo3C15YYGuakVw6QE-Oz-mlRoxDXVlzfcdD6ZhTKW7i5MwwY3gK7YlezulvUrmZWRVZzj0HU4EnH4CNB1DJEbRdvGtKvSZ66IWTgN7WzAUpkctQxVAYy1ZyyMTd7pfZa79li4Z-b48REavvZpO9fLoHkLt9T24NzqZ_g78XriK2j5VKgkCq7j2h7_j4CQD5nJiyMGf9DzpqK0FMluFYVeW8qJxEDcqMN6nNN-nMKuejLsU9NGlLzcnzv9E42_5KuC_4i7iawMFyd824Sc2OsZKhxpcbxDuFyjPCRbWrXfKd7Ov4MG9c13Ux960O30ttNZuyt-LHL97yStC5Vg0UQmPGWtoRct90O_yEopS4PYtnaw-Oc9_Hz4euhnFru3v4Cu65-GCoDAAA; AccessSessionSignature=9d408b8fa67fd510958bcb45425e4a644831cd4ddc0b3b787c24e840c1012506;UUID=asd-121-s2d2-23sdsd-2323dsd")
                                             (mock/header "fastly-client-ip" "10.10.10.2")
                                             (mock/content-type "application/json"))))
                 response (app (-> (mock/request :post "/api/v1/group/1/copy" json-body)
                                   (mock/header "web-token" artstor_cookie)
                                   (mock/header "Cookie" "AccessSession=H4sIAAAAAAAAAHVRu27cMBDs7ysE1dFll2-mO7-QIJ3tVEGKFR-JDPl8kKgAgeF_j0TpLB2CFCTAmdnZ4e7rrijKxpfFp6L0EOtAjpNUKGJNJrIodJSOhNUKZPlhErtF3ZCriAj0DJ8WWHorFdfcQo3C15YYGuakVw6QE-Oz-mlRoxDXVlzfcdD6ZhTKW7i5MwwY3gK7YlezulvUrmZWRVZzj0HU4EnH4CNB1DJEbRdvGtKvSZ66IWTgN7WzAUpkctQxVAYy1ZyyMTd7pfZa79li4Z-b48REavvZpO9fLoHkLt9T24NzqZ_g78XriK2j5VKgkCq7j2h7_j4CQD5nJiyMGf9DzpqK0FMluFYVeW8qJxEDcqMN6nNN-nMKuejLsU9NGlLzcnzv9E42_5KuC_4i7iawMFyd824Sc2OsZKhxpcbxDuFyjPCRbWrXfKd7Ov4MG9c13Ux960O30ttNZuyt-LHL97yStC5Vg0UQmPGWtoRct90O_yEopS4PYtnaw-Oc9_Hz4euhnFru3v4Cu65-GCoDAAA; AccessSessionSignature=9d408b8fa67fd510958bcb45425e4a644831cd4ddc0b3b787c24e840c1012506;UUID=asd-121-s2d2-23sdsd-2323dsd")
                                   (mock/header "fastly-client-ip" "10.10.10.2")
                                   (mock/content-type "application/json")))
                 raw_body (slurp (:body response))
                 body (parse-body raw_body)
                 group (group-coercer body)]
             (is (= (:status response) 200))
             (is (nil? (s/check Group group)))
             (is (= (group :description) "This is the first group"))
             (is (included? {:eventtype  "artstor_copy_group"
                             :dests ["captains-log"]
                             :profileid "299277"}
                            logs))))))))

(deftest api-legacy-metadata-tests
  (let [app core/app
        group-id 1
        group {:something 1}
        user [{:institution_ids [1000]}]
        metadata '({:mdString "",
                    :SSID "",
                    :editable false,
                    :objectId "LESSING_ART_10310752347",
                    :fileProperties [],
                    :title "Lady with an Ermine",
                    :imageUrl "/thumb/imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg",
                    :metaData ({:celltype "",
                                :count 1,
                                :fieldName "Creator",
                                :fieldValue "Leonardo De Caprio?",
                                :index 1,
                                :link "",
                                :textsize 1,
                                :tooltip "" },
                                {:celltype "",
                                 :count 1,
                                 :fieldName "Title",
                                 :fieldValue "Lady with an Ermine",
                                 :index 0,
                                 :link "",
                                 :textsize 1,
                                 :tooltip ""})})]
    ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
    (with-redefs [repo/get-group (fn [id] group)
                  metadata/get-legacy-metadata (fn [group-id object-id institute_id] metadata)
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str 1000) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/art-aa (fn [_ _] {:status 200 :body (json/write-str {:user {:institutionId 1000}})})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (with-db config
               (testing "Test getting legacy /secure/metadata object"
                 (let [req (-> (mock/request :get  "/api/v1/group/1/secure/metadata/obj1")
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "fastly-client-ip" "10.10.10.2"))
                       response (app req)
                       raw_body (slurp (:body response))
                       body (parse-body raw_body)]
                   (is (= (:status response) 200))
                   (is (= ((first body) :title ) "Lady with an Ermine"))))))))

(deftest api-items-tests
  (let [app core/app
        group {:something 1}
        item {:body {:collectionId "10",
              :objectId "obj1",
              :downloadSize "1024,1024",
              :tombstone [],
              :collectionType 1,
              :thumbnailImgUrl "thumbnailImageUrl",
              :objectTypeId 10,
              :clustered 0,
              :largeImgUrl "largeImageUrl",
              :cfObjectId ""}}]
    ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
    (with-redefs [repo/get-group (fn [id] group)
                  metadata/get-items (fn [group-id object_ids institute_id] item)
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str 1000) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/art-aa (fn [_ _] {:status 200 :body (json/write-str {:user {:institutionId 1000}})})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (with-db config
               (testing "Test copying a group"
                 (let [req (-> (mock/request :get  "/api/v1/group/1/items?object_ids=obj1")
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "fastly-client-ip" "10.10.10.2"))
                       response (app req)
                       raw_body (slurp (:body response))
                       body (parse-body raw_body)]

                   (is (= (:status response) 200))
                   (is (= (body :objectId) "obj1"))
                   (is (= (body :downloadSize) "1024,1024"))
                   ))))))

(deftest log-all-invalid-requests
  (let [app core/app
        status-coercer (coerce/coercer RequestStatus coerce/json-coercion-matcher)]
    (with-db config
             (with-redefs [wrap-web-logging (fn [h] h)
                           logger/log* (fn [_ lvl _ msg]
                                         (if (= lvl :error)
                                           (is (true? (string/starts-with? msg "Invalid :put /api/v1/group/1")))))
                           org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                             :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                         {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                           org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
               (testing "When a request failed input schema validation, it gets logged"
                 (let [json-body (cheshire/generate-string {:name "My first fancy group" :description "This is my group"
                                                            :sequence_number 10 :access [{:entity_type 200
                                                                                          :entity_identifier "Artstor"
                                                                                          :access_type 300}]
                                                            :items ["objectid5" "objectid6"]
                                                            :tags [123]})
                       response (app (-> (mock/request :put "/api/v1/group/1" json-body)
                                         (mock/header "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       raw-body (slurp (:body response))
                       body (parse-body raw-body)
                       status-response (status-coercer body)]
                   (is (= (:status response) 400))
                   (is (true? (contains? (:errors body) :tags)))
                   (is (= (nil? (s/check data/RequestStatus status-response))))))))))

(deftest json-all-the-things
  (let [app core/app
        status-coercer (coerce/coercer RequestStatus coerce/json-coercion-matcher)]
    (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (with-db config
               (testing "Random URL returns 404 and JSON"
                 (let [response (app (-> (mock/header (mock/request :delete "/api/v1/group/blah/blah") "web-token" artstor_cookie)))
                       raw-body (slurp (:body response))
                       body (parse-body raw-body)
                       status-response (status-coercer body)]
                   (is (= (:status response) 404))
                   (is (= (nil? (s/check data/RequestStatus status-response))))))))))

(deftest cors-everywhere
  (let [app core/app]
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (with-db config
               (testing "GET call does NOT return CORS headers"
                 (let [req (-> (mock/request :get "/api/v1/group")
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "origin" "spooky-origin"))
                       response (app req)]
                   (is (= (:status response) 200))
                   (is (not (contains? (:headers response) "Access-Control-Allow-Origin"))))))
      (with-db config
               (testing "POST calls does NOT return CORS headers"
                 (let [json-body (cheshire/generate-string {:name "My non-existing group"
                                                            :description "This is my group"
                                                            :sequence_number 4 :access [{:entity_type 200
                                                                                         :entity_identifier "1000"
                                                                                         :access_type 100}]
                                                            :items ["objectid10"]
                                                            :tags ["tag1" "tag2" "tag3"]})
                       req (-> (mock/request :post "/api/v1/group" json-body)
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "origin" "spooky-origin")
                               (mock/content-type "application/json"))
                       response (app req)]
                   (is (= (:status response) 200))
                   (is (not (contains? (:headers response) "Access-Control-Allow-Origin"))))))
      (with-db config
               (testing "PUT calls returns CORS headers"
                 (let [json-body (cheshire/generate-string {:name "My non-existing group"
                                                            :description "This is my group"
                                                            :sequence_number 4 :access [{:entity_type 100
                                                                                         :entity_identifier "1000"
                                                                                         :access_type 300}]
                                                            :items ["objectid10"]
                                                            :tags ["tag1" "tag2" "tag3"]})
                       req (-> (mock/request :put "/api/v1/group/1" json-body)
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "origin" "spooky-origin")
                               (mock/content-type "application/json"))
                       response (app req)]
                   (is (= (:status response) 200))
                   (is (= (get (:headers response) "Access-Control-Allow-Origin") "spooky-origin")))))
      (with-db config
               (testing "DELETE calls returns CORS headers"
                 (let [req (-> (mock/request :delete "/api/v1/group/1")
                               (mock/header "web-token" artstor_cookie)
                               (mock/header "origin" "spooky-origin"))
                       response (app req)]
                   (is (= (:status response) 200))
                   (is (= (get (:headers response) "Access-Control-Allow-Origin") "spooky-origin"))))))))

(deftest searching
  (let [app core/app]
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth false})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId nil}])})]
      (with-db config
               (testing "Test explicitly denied ip user can NOT search institution level"
                 (let [response (app (-> (mock/request :get "/api/v1/group?level=institution")
                                         (mock/header "fastly-client-ip" "10.10.10.2")))]
                   (is (= (:status response) 401))))
               (testing "Test user cannot search without having a valid cookie or an authorized ip"
                 (let [response (app (-> (mock/request :get "/api/v1/group")))]
                   (is (= (:status response) 401))
                   (is (= (nil? (s/check data/GroupSearch response))))))
               (testing "Test unauthenticated user cannot search for public groups, has to be logged in or ip-authorized"
                 (let [response (app (-> (mock/request :get "/api/v1/group?level=public")))]
                   (is (= (:status response) 401))))
               (testing "Test unauthenticated user cannot search for private image groups"
                 (let [response (app (-> (mock/request :get "/api/v1/group?level=private")))]
                   (is (= (:status response) 401))))
               (testing "Test unauthenticated user cannot search for institutional image groups"
                 (let [response (app (-> (mock/request :get "/api/v1/group?level=institution")))]
                   (is (= (:status response) 401)))))))
  (with-redefs [es-client/search (fn [_ _ _ query] {:hits { :total 1 :hits [{ :_source {"description" nil
                                                                                        "tags" []
                                                                                        "sequence_number" 0
                                                                                        "name" "cuba"
                                                                                        "public" false
                                                                                        "id" "282413"
                                                                                        "access" []
                                                                                        "items" []}}]}

                                                    :aggregations {:tags nil}})
                logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))]
    (let [app core/app]
      (testing "Validation fails in presence of description"
        (let [response (app (-> (mock/header (mock/request :get "/api/v1/group") "web-token" artstor_cookie)))]
          (is (= (:status response) 500)))))))

(deftest tokens-for-all
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)
                logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                  :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                              {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
    (let [app core/app
          group-coercer (coerce/coercer Group coerce/json-coercion-matcher)]
      (testing "An admin can create a token for access to a group, simplest case"
        (with-db config
          (let [response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share") "web-token" artstor_cookie)
                                  (mock/content-type "application/json")))
                data (parse-body (slurp (response :body)))]
            (is (= (:status response) 200))
            (is (:success data)))))
      (testing "An admin can create a token for access to a group, giving access-type"
        (with-db config
                 (let [request-data (cheshire/generate-string {:access_type 200})
                       response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data)
                                                      "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 200))
                   (is (:success data)))))
      (testing "An admin can create a token for access to a group, giving access-type, and expiration"
        (with-db config
                 (let [request-data (cheshire/generate-string {:access_type 200
                                                               :expiration_time "2018-01-01T00:00:00Z"})
                       response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data)
                                                      "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 200))
                   (is (:success data)))))
      (testing "a create token request will fail on a bad expiration timestamp"
        (with-db config
                 (let [request-data (cheshire/generate-string {:access_type 200
                                                               :expiration_time "WEIRD"})
                       response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data)
                                                      "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 400))
                   (is (not (nil? ((data :errors) :expiration_time)))))))
      (testing "a create token request will fail on a bad access-type"
        (with-db config
                 (let [request-data (cheshire/generate-string {:access_type 300})
                       response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data)
                                                      "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 400))
                   (is (not (nil? ((data :errors) :access_type)))))))
      (testing "retrieve all the tokens a user has created for a group"
        (with-db config
                 (let [response (app (-> (mock/request :get "/api/v1/group/1/tokens")
                                         (mock/header "web-token" artstor_cookie)))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 200))
                   (is (= 1 (count (data :tokens)))))))
      (testing "user can't retrieve all the tokens for a group in which they are not the admin"
        (with-db config
                 (let [response (app (-> (mock/request :get "/api/v1/group/100/tokens")
                                         (mock/header "web-token" artstor_cookie)))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 403)))))
      (testing "user can't retrieve tokens for a group to which they do not have access"
        (with-db config
                 (let [response (app (-> (mock/request :get "/api/v1/group/2/tokens")
                                         (mock/header "web-token" artstor_cookie)))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 404)))))
      (testing "redeeming a valid token provides access to the group"
        (with-db config
                 (let [token3 (util/encode-uuid (UUID/fromString "18cb49bf-2061-41f0-8ce9-2484db43af78"))
                       response (app (-> (mock/header (mock/request :post (str "/api/v1/group/redeem/" token3))
                                                      "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 200))
                   (is (nil? (s/check Group (group-coercer (data :group)))))
                   (is (contains? (set (map :entity_identifier ((data :group) :access))) "299277")))))
      (testing "redeeming an invalid token returns 404"
        (with-db config
                 (let [response (app (-> (mock/header (mock/request :post "/api/v1/group/redeem/not-a-token")
                                                      "web-token" artstor_cookie)
                                         (mock/content-type "application/json")))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 404)))))
      (testing "expiring a token causes it to become invalid"
        (with-db config
          (let [token1 (util/encode-uuid (UUID/fromString "9bb3315a-df55-4b3b-bdbc-7421e6d79961"))]
            (let [response (app
                             (-> (mock/request :delete (str "/api/v1/group/expire/" token1))
                                 (mock/header "web-token" artstor_cookie)))
                       data (parse-body (slurp (response :body)))]
                 (is (= (:status response) 200)))
            (let [response (app (-> (mock/request :post (str "/api/v1/group/redeem/" token1))
                                    (mock/header "web-token" artstor_cookie)
                                    (mock/content-type "application/json")))
                  data (parse-body (slurp (response :body)))]
              (is (= (:status response) 404))))))
      (testing "expiring an invalid token returns a 404"
        (with-db config
                 (let [response (app (-> (mock/header (mock/request :delete "/api/v1/group/expire/not-a-token")
                                                      "web-token" artstor_cookie)))
                       data (parse-body (slurp (response :body)))]
                   (is (= (:status response) 404))))))))

(deftest put-that-access
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)
                logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                  :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                              {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 123456}])})]
    (let [app core/app
          group-coercer (coerce/coercer Group coerce/json-coercion-matcher)]
      (testing "A regular writer can't put that access"
        (with-db config
                 (let [json-body (cheshire/generate-string {:name "My second fancy group" :description "Not gonna happen"
                                                            :sequence_number 10 :access [{:entity_type 200
                                                                                          :entity_identifier "99999"
                                                                                          :access_type 300}]
                                                            :items ["objectid5" "objectid6"]
                                                            :tags ["tag1" "tag2" "tag3"]})
                       response (app (-> (mock/header (mock/request :put "/api/v1/group/2" json-body) "web-token" my_penpals_cookie)
                                         (mock/content-type "application/json")))
                       body (parse-body (slurp (:body response)))
                       group (group-coercer body)]
                   (is (= (:status response) 403)))))
      (testing "A regular writer can put without access"
        (with-db config
                 (let [json-body (cheshire/generate-string {:name "My second fancy group" :description "A much better description"
                                                            :sequence_number 10 :access []
                                                            :items ["objectid5" "objectid6"]
                                                            :tags ["tag1" "tag2" "tag3"]})
                       response (app (-> (mock/header (mock/request :put "/api/v1/group/2" json-body) "web-token" my_penpals_cookie)
                                         (mock/content-type "application/json")))
                       body (parse-body (slurp (:body response)))
                       group (group-coercer body)]
                   (is (= (:status response) 200))
                   (is (= "A much better description" (get group :description)))
                   (is (nil? (s/check Group group)))))))))

(deftest test-limit-group-shares
  (let [app core/app]
    (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (testing "Test to generate token if the group is not shared with any entity - simple case"
        (with-redefs [repo/count-group-shares (fn [lengg] 0)]
          (with-db config
                   (let [response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share") "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 200))
                     (is (= (:success data) true))))))
      (testing "Test to generate token if the group is shared to less than 1000 entities - given acces_type"
        (with-redefs [repo/count-group-shares (fn [lengg] 999)]
          (with-db config
                   (let [request-data (cheshire/generate-string {:access_type 200})
                         response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data) "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 200))
                     (is (= (:success data) true))))))
      (testing "Test to generate token if the group is shared to less than 1000 entities - given acces_type and expiration"
        (with-redefs [repo/count-group-shares (fn [lengg] 999)]
          (with-db config
                   (let [request-data (cheshire/generate-string {:access_type 200
                                                                 :expiration_time "2018-01-01T00:00:00Z"})
                         response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data) "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 200))
                     (is (= (:success data) true))))))
      (testing "Test to not generate token if the group is already shared to 1000 entities - given access_type"
        (with-redefs [repo/count-group-shares (fn [lengg] 1000)]
          (with-db config
                   (let [request-data (cheshire/generate-string {:access_type 200})
                         response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data) "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 400))
                     (is (= (:success data) false))))))
      (testing "Test to not generate token if the group is already shared to more than 1000 entities - given access_type and expiration"
        (with-redefs [repo/count-group-shares (fn [lengg] 1001)]
          (with-db config
                   (let [request-data (cheshire/generate-string {:access_type 200
                                                                 :expiration_time "2018-01-01T00:00:00Z"})
                         response (app (-> (mock/header (mock/request :post "/api/v1/group/1/share" request-data) "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 400))
                     (is (= (:success data) false)))))))))

(deftest test-limit-group-shares-while-redeeming-token
  (let [app core/app
        group-coercer (coerce/coercer Group coerce/json-coercion-matcher)]
    (with-redefs [org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]

      (testing "If the group is shared with less than 1000 entities, redeeming a valid token provides access to the group"
        (with-redefs [repo/count-redeeming-group-access (fn [lengg] 999)]
          (with-db config
                   (let [token3 (util/encode-uuid (UUID/fromString "18cb49bf-2061-41f0-8ce9-2484db43af78"))
                         response (app (-> (mock/header (mock/request :post (str "/api/v1/group/redeem/" token3))
                                                        "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 200))
                     (is (nil? (s/check Group (group-coercer (data :group)))))
                     (is (contains? (set (map :entity_identifier ((data :group) :access))) "299277"))))))
      (testing "If the group is already shared with 1000 entities, redeeming a valid token should not provide access to the group"
        (with-redefs [repo/count-redeeming-group-access (fn [lengg] 1000)]
          (with-db config
                   (let [token3 (util/encode-uuid (UUID/fromString "18cb49bf-2061-41f0-8ce9-2484db43af78"))
                         response (app (-> (mock/header (mock/request :post (str "/api/v1/group/redeem/" token3))
                                                        "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 400))
                     (is (= (:success data) false))
                     (is (= (:message data) "Cannot share to more than 1000 entities"))))))
      (testing "If the group is shared with more than 1000 entities, redeeming a valid token should not provide access to the group"
        (with-redefs [repo/count-redeeming-group-access (fn [lengg] 1000)]
          (with-db config
                   (let [token3 (util/encode-uuid (UUID/fromString "18cb49bf-2061-41f0-8ce9-2484db43af78"))
                         response (app (-> (mock/header (mock/request :post (str "/api/v1/group/redeem/" token3))
                                                        "web-token" artstor_cookie)
                                           (mock/content-type "application/json")))
                         data (parse-body (slurp (response :body)))]
                     (is (= (:status response) 400))
                     (is (= (:success data) false))
                     (is (= (:message data) "Cannot share to more than 1000 entities")))))))))

(deftest x-http-method-override-tests
  (let [app core/app
        group-coercer (coerce/coercer Group coerce/json-coercion-matcher)
        status-coercer (coerce/coercer RequestStatus coerce/json-coercion-matcher)]
    (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                  es-client/delete (fn [_ _ _ id] id)
                  es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (with-db config
               (testing "Test PUT to updated group using x-http-method-override"
                 (let [json-body (cheshire/generate-string {:name "My group x-http-method-override PUT"
                                                            :description "This is the first group UPDATED"
                                                            :sequence_number 10
                                                            :access [{:entity_type 100
                                                                      :entity_identifier "299277"
                                                                      :access_type 300}]
                                                            :items ["objectid5" "objectid6"]
                                                            :tags ["tag1" "tag2" "tag3"]})

                       ;post-put-response : response by calling post with x-http-method-override header
                       post-put-response (app (-> (mock/request :post "/api/v1/group/1" json-body)
                                               (mock/header "web-token" artstor_cookie)
                                               (mock/header "x-http-method-override" "PUT")
                                               (mock/content-type "application/json")))
                       post-put-body (parse-body (slurp (:body post-put-response)))
                       post-put-group (group-coercer post-put-body)

                       ;get-response : response by calling get on the same group
                       get-response (app (-> (mock/request :get "/api/v1/group/1")
                                          (mock/header "web-token" artstor_cookie)))
                       get-body (parse-body (slurp (:body get-response)))
                       get-group (group-coercer get-body)]

                   ;assertions for post-put-response
                   (is (= (:status post-put-response) 200))
                   (is (= "My group x-http-method-override PUT" (get post-put-group :name)))
                   (is (= "This is the first group UPDATED" (get post-put-group :description)))
                   (is (nil? (s/check Group post-put-group)))
                   ;assertions for get-response
                   (is (= (:status get-response) 200))
                   (is (= "My group x-http-method-override PUT" (get get-group :name)))
                   (is (= "This is the first group UPDATED" (get get-group :description)))
                   (is (nil? (s/check Group get-group)))))
               (testing "Test DELETE a particular group using x-http-method-override"
                 (let [response (app (-> (mock/request :post "/api/v1/group/1")
                                         (mock/header "X-HTTP-Method-Override" "DELETE")
                                         (mock/header "web-token" artstor_cookie)))]
                   (is (= (:status response) 200)))
                 (let [response (app (-> (mock/request :get "/api/v1/group/1")
                                         (mock/header "web-token" artstor_cookie)))
                       raw-body (slurp (:body response))
                       body (parse-body raw-body)
                       status-response (status-coercer body)]
                   (is (= (:status response) 404))
                   (is (= (nil? (s/check data/RequestStatus status-response))))))))))

(deftest test-any-unavailable-endpoint
  (let [app core/app]
    (testing "test unavailable endpoint"
      (let [response (app (-> (mock/request :get "/watchable")))]
        (is (= 404 (response :status)))))))

(deftest delete-assets-from-group-items
  (let [app core/app]
    (with-redefs [es-client/search (fn [_ _ _ query] {:hits { :total 0 :hits [] } :aggregations {:tags nil}})
                  logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  org.ithaka.clj-iacauth.core/extract-data (fn [_] {:auth true
                                                                    :authAccts [{:eid "personuuid" :type "Individual" :creds [{:type org.ithaka.clj-iacauth.artstor/username-cred-type :value "me-user"}]}
                                                                                {:eid "instuuid" :lid (str (+ org.ithaka.clj-iacauth.artstor/artstor-offset 1000)) :type "Institution"}]})
                  org.ithaka.clj-iacauth.service/iac-service (fn [_ _] {:status 200 :body (json/write-str [{:profileId 299277}])})]
      (with-db config
               (testing "Test update groups by removing a given objectid when exists"
                 (with-redefs [repo/get-groups-associated-with-object-ids (fn [_] {"objectid1" [{:object_id "objectid1", :group_id "1"}]})
                               repo/delete-groups-associated-with-object-ids (fn [_ _] {:success true :message "Objects deleted from all image groups"})]
                   (let [object-ids "[\"objectid1\"]"
                         response (app (-> (mock/request :put "/api/v1/group/items/delete" object-ids)
                                           (mock/content-type "application/json")
                                           (mock/header  "web-token" artstor_cookie)))]
                     (is (= (:status response) 200)))))
               (testing "Test update groups by removing a given objectid when doesn't exist"
                 (with-redefs [repo/get-groups-associated-with-object-ids (fn [_] {})
                               repo/delete-groups-associated-with-object-ids (fn [_ _] {:success false :message "Couldn't find any of the groups containing the object-id(s)"})]
                   (let [object-ids "[\"objectid1\"]"
                         response (app (-> (mock/request :put "/api/v1/group/items/delete" object-ids)
                                           (mock/content-type "application/json")
                                           (mock/header  "web-token" artstor_cookie)))]
                     (is (= (:status response) 404)))
                   (let [response (app (-> (mock/header (mock/request :put "/api/v1/group/items/delete") "web-token" artstor_cookie)))]
                     (is (= (:status response) 400)))))))))