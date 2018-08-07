(ns artstor-group-service.user-test
  (:require [clojure.test :refer :all]
            [ragtime.jdbc :as jdbc]
            [ragtime.repl :refer [migrate rollback]]
            [environ.core :refer [env]]
            [cheshire.core :as cheshire]
            [schema.core :as s]
            [schema.coerce :as coerce]
            [ring.mock.request :as mock]
            [artstor-group-service.user :as user]))


(def valid-access-profile-only [{ :entity_type 100 :entity_identifier "299277" :access_type 100 }])
(def valid-access-institute-only [{ :entity_type 200 :entity_identifier "1000" :access_type 100 }])
(def valid-access [{ :entity_type 100 :entity_identifier "299277" :access_type 100 }
                   { :entity_type 200 :entity_identifier "1000" :access_type 100 }])
(def invalid-access-profile-only [{ :entity_type 100 :entity_identifier "2992770" :access_type 100 }])
(def invalid-access-institute-only [{ :entity_type 200 :entity_identifier "10000" :access_type 100 }])
(def invalid-access [{ :entity_type 100 :entity_identifier "2992770" :access_type 100 }
                   { :entity_type 200 :entity_identifier "10000" :access_type 100 }])

(def config {:datastore (jdbc/sql-database {:connection-uri (env :artstor-oracle-db-url)})
             :migrations (jdbc/load-resources "test-migrations")})

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(deftest test-validate-group-access-info
  (testing "valid institute only access entry"
    (with-db config
             (is (= nil (user/validate-group-access-info? valid-access-profile-only)))
             (is (= nil (user/validate-group-access-info? valid-access-institute-only)))
             (is (= nil (user/validate-group-access-info? valid-access)))
             (is (= nil (get (user/validate-group-access-info? invalid-access-profile-only) :success)))
             (is (= nil (get (user/validate-group-access-info? invalid-access-institute-only) :success)))
             (is (= nil (get (user/validate-group-access-info? invalid-access) :success))))))


