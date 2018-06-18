(ns artstor-group-service.auth-test
  (:require [clojure.test :refer :all]
            [artstor-group-service.auth :as auth]
            [artstor-group-service.repository :as repo]
            [ragtime.jdbc :as jdbc]
            [ragtime.repl :refer [migrate rollback]]
            [environ.core :refer [env]]
            [schema.core :as s]
            [artstor-group-service.user :as user]))

(def config {:datastore (jdbc/sql-database {:connection-uri (env :artstor-group-db-url)})
             :migrations (jdbc/load-resources "test-migrations")})

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(def MrBill { :profile_id "299277" :username "qa@artstor.org" :institution_ids ["1000"] })
(def Will { :profile_id "Will" :username "will@artstor.org" :institution_ids ["2000"] })
(def Professor { :profile_id "123456" :username "prof@artstor.org" :institution_ids ["10001"] })
(def BadDog { :profile_id "111111" :username "woof@artstor.org" :institution_ids ["1234567"] })





(deftest authorization-test
  (with-db config
           (testing "Performing can MrBill read authorization test"
             (let [canRead (auth/user-action-allowed? MrBill :read 123)]
               (println "Does MrBill know how to read==" canRead)
               (is canRead)
               )))
  (with-db config
           (testing "Performing can MrBill read his own Public Group test"
             (let [canRead (auth/user-action-allowed? MrBill :read 294484)]
               (println "Does MrBill know how to read his own public group==" canRead)
               (is canRead)
               )))
  (with-db config
         (testing "Performing can MrBill write authorization test"
           (let [canDo (auth/user-action-allowed? MrBill :write 123)]
             (println "Does MrBill know how to write==" canDo)
             (is canDo)
             )))
  (with-db config
           (testing "Performing can MrBill delete authorization test"
             (let [canDo (auth/user-action-allowed? MrBill :admin 123)]
               (println "Does MrBill know how to delete==" canDo)
               (is canDo)
               )))
  (with-db config
           (testing "Performing can Professor write authorization test"
             (let [canDo (auth/user-action-allowed? Professor :write 123)]
               (println "Is Professor allowed to write==" canDo)
               (is (false? canDo))
               )))
  (with-db config
           (testing "Performing can Professor read group shared at institution level"
             (let [canDo (auth/user-action-allowed? Professor :read 123)]
               (println "Is Professor allowed to read group123==" canDo)
               (is canDo)
               )))

  (with-db config
           (testing "Performing can Professor read MrBill's Public Group test"
             (let [canRead (auth/user-action-allowed? Professor :read 294484)]
               (println "Does Professor know how to read MrBill's public group==" canRead)
               (is canRead)
               )))
  (with-db config
           (testing "Performing can Professor can delete authorization test"
             (let [canDo (auth/user-action-allowed? Professor :write 123)]
               (println "Is Professor allowed to delete==" canDo)
               (is (false? canDo))
               )))

  (with-db config
           (testing "Performing can BadDog write authorization test"
             (let [canDo (auth/user-action-allowed? BadDog :write 123)]
               (println "Is BadDog allowed to write==" canDo)
               (is (false? canDo))
               )))
  (with-db config
           (testing "Performing can BadDog read authorization test"
             (let [canDo (auth/user-action-allowed? BadDog :read 123)]
               (println "Is BadDog allowed to read group123==" canDo)
               (is (false? canDo))
               )))
  (with-db config
           (testing "Performing can BadDog can delete authorization test"
             (let [canDo (auth/user-action-allowed? BadDog :write 123)]
               (println "Is BadDog allowed to delete==" canDo)
               (is (false? canDo))
               ))))

    ;;[{:keys [profile_id institution_ids]} action-type group-id]

(def my-group-changes {:name "New Group Name" :description "Fun!" :sequence_number 100
      :access [{:entity_type 100 :entity_identifier "Will" :access_type 200},{:entity_type 100 :entity_identifier "299277" :access_type 300}]
      :items ["objectid3" "objectid4"]
      :tags ["tag1" "tag2"]})

(deftest access-object-authorization-test

  (with-db config
           (testing "Performing can Admin (MrBill) update access object authorization test"
             (let [canDo (auth/can-change-group-access? MrBill 123)]
               (println "Is MrBill allowed to update==" canDo)
               (is canDo))))

  (with-db config
           (testing "Performing can Write only (Will) update access object authorization test"
             (let [canDo (auth/can-change-group-access? Will 123)]
               (println "Is Will allowed to update when he should not==" canDo)
               (is (not canDo)))))

  (with-db config
           (testing "Performing does Will's access object only have Will's stuff in it"
             (let [found-group-access (repo/find-group-by-id 123)
                   filtered-group (auth/coerce-group-based-on-access-type found-group-access Will)]
               (println "Will's filtered group==" filtered-group)
               (is (= (filtered-group :access) [{:access_type 200, :entity_identifier "Will", :entity_type 100}])))))

  (with-db config
           (testing "Performing does MrBill's Admin access object have Everything in it"
             (let [found-group-access (repo/find-group-by-id 123)
                   filtered-group (auth/coerce-group-based-on-access-type found-group-access MrBill)]
               (println "MrBill's filtered group==" filtered-group)
               (is (= (filtered-group :access) [{:access_type 300, :entity_identifier "299277", :entity_type 100}
                                                {:access_type 100, :entity_identifier "Sai", :entity_type 100}
                                                {:access_type 200, :entity_identifier "Will", :entity_type 100}
                                                {:access_type 100, :entity_identifier "10001", :entity_type 200}
                                                {:access_type 300, :entity_identifier "Artstor", :entity_type 200}])))))

  (with-db config
           (testing "Performing does Professor's access object only have Institution stuff in it"
             (let [found-group-access (repo/find-group-by-id 123)
                   filtered-group (auth/coerce-group-based-on-access-type found-group-access Professor)]
               (println "Professor's filtered group==" filtered-group)
               (is (= (filtered-group :access) [{:access_type 100, :entity_identifier "10001", :entity_type 200}])))))
  )
