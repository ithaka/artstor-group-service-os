(ns artstor-group-service.auth
  (:require [artstor-group-service.user :as user]
            [org.ithaka.clj-iacauth.ring.middleware :as iac-auth]
            [artstor-group-service.repository :as repo]
            [clojure.tools.logging :as logger]))

(def artstor-institution-id "1000")

(defn logged-in? [req]
  (and (-> req :artstor-user-info :username) (not (-> req :artstor-user-info :default-user))))

(defn ip-authorized? [req]
  (true? (-> req :artstor-user-info :default-user)))

(defn authenticated? [req]
  (or (logged-in? req)
      (ip-authorized? req)))

(defn user-action-allowed?
  "Determines if the given user may perform the requested action"
  [{:keys [profile_id institution_ids]} action-type group-id]
  (logger/debug {:eventtype "authorization" :action-type action-type :profile_id profile_id :institution_ids institution_ids :group-id group-id })
  (let [permissions (repo/get-group-access group-id)
        users-with-access (filter #(= 100 (% :entity_type)) permissions)
        user (first (filter #(= profile_id (% :entity_identifier)) users-with-access))
        insts-with-access (filter #(and (= 200 (% :entity_type)) (= 100 (% :access_type))) permissions)]
    (condp = action-type
      :read (or (or (not (nil? user))
                    (some #(contains? (set institution_ids) %) (map :entity_identifier insts-with-access)))
                (get (first permissions) :public true))
      :write (or (and (not (nil? user)) (> (user :access_type) 100)) (empty? permissions))
      :admin (or (and (not (nil? user)) (> (user :access_type) 200)) (empty? permissions)))))

(defn can-perform-search? [req]
  (let [level (keyword (get (req :query-params) "level" "all"))]
    (condp = level
      :private (logged-in? req)
      :shared (logged-in? req)
      (or (logged-in? req) (ip-authorized? req)))))

(defn can-read? [{{:keys [group-id]} :params :as req}]
  (let [user (user/extract-user-or-ip req)]
    (and (authenticated? req) (user-action-allowed? user :read group-id))))

(defn can-write? [{{:keys [group-id]} :params :as req}]
  (let [user (user/extract-user-or-ip req)]
    (and (logged-in? req) (user-action-allowed? user :write group-id))))

(defn is-admin? [{{:keys [group-id]} :params :as req}]
  (let [user (user/extract-user-or-ip req)]
      (and (logged-in? req) (user-action-allowed? user :admin group-id))))

(defn artstor-admin? [req]
  (and (logged-in? req) (some #(= artstor-institution-id %) ((user/extract-user-or-ip req) :institution_ids))))

(defn gets-full-access?
  "return the full access object only for admins"
  [group user]
  (user-action-allowed? user :admin (group :id)))

(defn can-change-group-access? [user group-id]
  (user-action-allowed? user :admin group-id))

(defn cors-headers [{:keys [headers]}]
  {"Access-Control-Allow-Origin"  (get headers "origin" "*")
   "Access-Control-Allow-Headers" "Origin, Accept, Content-Type, Cache-Control, Expires"
   "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
   "Access-Control-Allow-Credentials" "true"})

(defn preflight?
  "Returns true if the request is a preflight request"
  [request]
  (= (request :request-method) :options))

(defn add-cors
  "Allow requests from all origins - also check preflight"
  [handler]
  (fn [request]
    (if (preflight? request)
      {:status 200
       :headers (cors-headers request)
       :body "preflight complete"}
      (let [response (handler request)]
        (if (contains? #{:put :delete} (request :request-method))
          (update-in response [:headers]
                     merge (cors-headers request))
          response)))))

(defn add-empty-body-to-posts
  [handler]
  (fn [request]
    (if (and (contains? #{:post} (request :request-method)) (nil? (request :body)))
      (handler (assoc request :body "{}"))
      (handler request))))


(defn coerce-group-based-on-access-type
  "Transforms the group object based on the access-type of the user"
  [group user]
    (if (gets-full-access? group user)
      group
      (assoc group :access (repo/filter-access-object-by-user user (group :access)))))

(defn generate-web-token
  "Generate web token given profile id and ins id"
  ([user] (generate-web-token (user :profile-id) (user :institution-id) (user :default-user) (user :username)))
  ([profile-id ins-id] (generate-web-token profile-id ins-id true "default-user"))
  ([profile-id ins-id default-user user-name]
  (org.ithaka.clj-iacauth.token/generate {:profile-id profile-id :institution-id ins-id :default-user default-user :username user-name})) )