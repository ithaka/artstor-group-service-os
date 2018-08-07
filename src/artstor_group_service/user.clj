(ns artstor-group-service.user
  (:require
            [yesql.core :refer [defqueries]]
            [environ.core :refer [env]])
  (:import (com.mchange.v2.c3p0 DataSources)))

(def db-spec {:datasource (DataSources/pooledDataSource
                            (DataSources/unpooledDataSource (env :artstor-oracle-db-url)))})

;; This is a macro that reads the Oracle calls in the specified adl.sql file and generates functions based on the
;; comments in the adl.sql file.
(defqueries "artstor_group_service/sql/user.sql"
            {:connection db-spec})

(defn extract-user-or-ip
  "Attempts to extract user from clj-IAC library. If user isn't found, the ip address is used to look up institution."
  [req]
  (if (not (empty? (req :artstor-user-info)))
    (let [profile_id (str (get-in  req [:artstor-user-info :profile-id]))
          ins_id (str (get-in req [ :artstor-user-info :institution-id]))]
     {:profile_id  profile_id :institution_ids [ins_id]})))

(defn wrap-user-or-ip
  "Middleware version of extract-user-or-ip"
  [client-func]
  (fn [req]
    (let [user (extract-user-or-ip req)]
      (client-func (assoc req :user user)))))

(defn validate-group-access-info?
  "Check if institution or profile id(s) are valid"
  [group-access]
  nil)