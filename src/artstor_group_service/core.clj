(ns artstor-group-service.core
  (:require [clojure.spec :as spec]
            [schema.core :as s]
            [compojure.api.sweet :refer :all]
            [compojure.api.meta :refer [restructure-param]]
            [compojure.api.exception :as ex]
            [buddy.auth.accessrules :as baa]
            [ring.util.http-response :refer [bad-request]]
            [ring.middleware.cookies :as rcookie]
            [ring.logger :as ring-logger]
            [captains-common.core :as captains]
            [org.ithaka.clj-iacauth.ring.middleware :refer :all]
            [artstor-group-service.schema :as data]
            [artstor-group-service.repository :as repo]
            [artstor-group-service.service.metadata :as metadata]
            [artstor-group-service.auth :as auth]
            [artstor-group-service.user :as user]
            [artstor-group-service.util :as util :refer [not-found forbidden]]
            [artstor-group-service.logging :as grplog])
  (:import (java.util UUID)))

(defn access-error [req val]
  (forbidden (or val {:success false :message "Access denied"})))

(defn wrap-rule [handler rule]
  (-> handler
      (baa/wrap-access-rules {:rules [{:pattern #".*"
                                       :handler rule}]
                              :on-error access-error})))

(defmethod restructure-param :auth-rules
  [_ rule acc]
  (update-in acc [:middleware] conj [wrap-rule rule]))

(defmethod restructure-param :current-user
  [_ binding acc]
  (update-in acc [:letks] into [binding `(user/extract-user-or-ip ~'+compojure-api-request+)]))

(def app
  (->>
    (api
      {:exceptions
                   {:handlers
                    {::ex/request-parsing    (grplog/with-request-logging ex/request-parsing-handler)
                     ::ex/request-validation (grplog/with-request-logging ex/request-validation-handler)}}
       :swagger
                   {:ui   "/"
                    :spec "/swagger.json"
                    :data {:info  {:title       "ANG Group Service API"
                                   :description "This service exposes CRUD methods for dealing with groups."}
                           :tags  [{:name "group" :description "Services for creating, updating, reading, and deleting groups"}]}}}
      (context "/api/v1/group" []
        :tags ["group"]
        (GET "/" []
          :auth-rules auth/can-perform-search?
          :return {:success s/Bool :total s/Int :groups [data/GroupSearch]
                   :tags (s/maybe [{:key s/Str :doc_count s/Int}])}
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :query-params [{q :- s/Str nil} {tags :- [s/Str] []} {from :- s/Int 0} {size :- s/Int 24}
                         {level :- (s/enum :all :public :institution :shared :private) :all}]
          :summary "Gets all Groups available for the currently logged in user"
          :current-user user
          :responses {200 {:schema      {:success s/Bool :total s/Int :groups [data/GroupSearch]
                                         :tags (s/maybe [{:key s/Str :doc_count s/Int}])}
                           :description "Search all groups and return matches"}
                      400 {:description "Invalid parameters supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      500 {:description "Server Error handling the request"}}
          (let [add-param {:tags tags :from from :size size  :level level}]
            (captains/ok (repo/get-groups q (merge add-param {:profile-id (user :profile_id)
                                                     :institution-ids (user :institution_ids)}))
                (grplog/build-search-event user (assoc add-param :query (if (nil? q) "" q))))))
        (GET "/:group-id" [group-id]
          :auth-rules auth/can-read?
          :current-user user
          :return data/Group
          :path-params [group-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :query-params [{internal :- s/Bool false}]
          :summary "Returns a group object"
          :responses {200 {:schema      data/Group
                           :description "A lovely group object"}
                      400 {:description "Invalid group-id supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if-let [found-group (repo/get-group group-id)]
            (let [deduped-group (repo/get-deduped-group found-group)]
              (captains/ok (auth/coerce-group-based-on-access-type deduped-group user)
                  (grplog/build-group-event user deduped-group "artstor_read_group" true {:internal internal})))
            (not-found {:success false :message "I couldn't find a group with that id."}
                (grplog/build-group-event user {:id group-id} "artstor_read_group" false
                                          {:artstor-error "I couldn't find a group with that id." :internal internal}))))
        (POST "/" []
          :auth-rules auth/logged-in?
          :current-user user
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :return data/Group
          :body [group data/NewGroup]
          :summary "Returns a freshly minted group object"
          :responses {200 {:schema      data/Group
                           :description "A lovely group object"}
                      400 {:description "Invalid form data supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      500 {:description "Server Error handling the request"}}
          (if-let [errors (data/group-validator? group)]
            (bad-request errors)
            (let [newlygroup (repo/add! group user)]
              (captains/ok newlygroup (grplog/build-create-event user newlygroup)))))
        (POST "/:group-id/copy" []
          :auth-rules auth/can-read?
          :current-user user
          :return data/Group
          :path-params [group-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :body [group-name {:name s/Str}]
          :summary "Returns a freshly minted group object copied from the provided group id"
          :responses {200 {:schema      data/Group
                           :description "A lovely group object"}
                      400 {:description "That's not a valid name"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "No group found to copy"}
                      500 {:description "Server Error handling the request"}}
          (if-let [errors (spec/explain-data ::data/name (group-name :name))]
            (bad-request errors)
            (if-let [new-group (repo/copy! group-id (group-name :name) user)]
              (captains/ok new-group (grplog/build-group-event user new-group "artstor_copy_group" true {:source_group_id group-id} ))
              (not-found {:success false :message "I couldn't find a group to copy."}
                         (grplog/build-group-event user {:id group-id} "artstor_copy_group" false {:source_group_id group-id :artstor_error "I couldn't find a source group to copy."})))))
        (GET "/:group-id/metadata" [group-id]
          :auth-rules auth/can-read?
          :current-user user
          :path-params [group-id :- String]
          :query-params [{object_ids :- [s/Str] []} {legacy :- s/Bool true} {xml :- s/Bool false}]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Gets the metadata for all the specified items the user has access too.  (150 max) with group id"
          :responses {200 {:description "Find items by object_id and return metadata matches"}
                      400 {:description "Invalid item/group identifiers supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The item-id/group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if(repo/get-group group-id)
            (metadata/get-metadata group-id object_ids legacy xml (str (first (:institution_ids user))))
            (not-found {:success false :message "I couldn't find a group/item with that id."})))
        (GET "/:group-id/secure/metadata/:object-id" [group-id object-id]
          :auth-rules auth/can-read?
          :current-user user
          :path-params [group-id :- String object-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Accepts object-id in url and returns the metadata in legacy json format for the specified item."
          :responses {200 {:description "Find item by object_id and return legacy formatted metadata"}
                      400 {:description "Invalid item/group identifiers supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The item-id/group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if(repo/get-group group-id)
            (metadata/get-legacy-metadata group-id object-id (str (first (:institution_ids user))))
            (not-found {:success false :message "I couldn't find a group/item with that id."})))
        (GET "/:group-id/items" [group-id]
          :auth-rules auth/can-read?
          :current-user user
          :path-params [group-id :- String]
          :query-params [{object_ids :- [s/Str] []}]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Gets the Items for all the specified items the user has access too.  (150 max) with group id"
          :responses {200 {:description "Find items by object_id and return item details."}
                      400 {:description "Invalid item/group identifiers supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The item-id/group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if-let [found-group (repo/get-group group-id)]
            (metadata/get-items group-id object_ids (str (first (:institution_ids user))))
            (not-found {:success false :message "I couldn't find a group/item with that id."})))
        (PUT "/:group-id" [group-id]
          :auth-rules auth/can-write?
          :current-user user
          :return data/Group
          :path-params [group-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :body [group data/NewGroup]
          :summary "Updates a group object"
          :responses {200 {:schema      data/Group
                           :description "A lovely updated group object"}
                      400 {:description "Invalid form data supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if (repo/find-group-by-id group-id)
            (if (or (auth/can-change-group-access? user group-id)
                    (empty? (group :access)))
              (if-let [errors (data/group-errors? group)]
                (bad-request errors)
                (captains/ok (auth/coerce-group-based-on-access-type (repo/update! group-id group) user)
                    (grplog/build-group-event user (assoc group :id group-id) "artstor_update_group" true)))
              (forbidden {:success false :message "Insufficient Privileges"}
                (grplog/build-group-event user (assoc group :id group-id) "artstor_update_group" false {:artstor-error "Insufficient Privileges"})))
            (not-found {:success false :message "I couldn't find a group with that id."}
                    (grplog/build-group-event user {:id group-id} "artstor_update_group" false {:artstor-error "I couldn't find a group with that id."}))))
        (PUT "/:group-id/admin/public" [group-id]
          :auth-rules auth/artstor-admin?
          :current-user user
          :return data/Group
          :path-params [group-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :body [public-flag  {:public s/Bool}]
          :summary "For Artstor admin use only: Updates the group's public value."
          :responses {200 {:schema      data/Group
                           :description "Only updates public field. Other fields are ignored."}
                      400 {:description "Only send the value for public:"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if (repo/find-group-by-id group-id)
            (captains/ok (auth/coerce-group-based-on-access-type (repo/update-public-flag! group-id public-flag) user)
                (grplog/build-group-event user {:id group-id} "artstor_update_group_public" true {:public_flag public-flag}))
            (not-found {:success false :message "I couldn't find a group with that id."}
                (grplog/build-group-event user {:id group-id} "artstor_update_group_public" false {:public_flag public-flag :artstor-error "I couldn't find a group with that id."}))))
        (PUT "/items/delete" []
          :auth-rules auth/logged-in?
          :current-user user
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :body [object_ids [s/Str]]
          :summary "Deletes objects from groups"
          :responses {200 {:description "Objects deleted from image groups"}
                      400 {:description "Invalid object-id(s) supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The groups associated with the supplied objects could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if (empty? object_ids)
            (bad-request)
            (let [result (repo/get-groups-associated-with-object-ids object_ids)]
              (if (not (empty? result))
                (let [data (into [] (flatten (map #(get-in result [%]) object_ids)))
                      group-ids (map #(get % :group_id) data)]
                  (captains/ok (repo/delete-groups-associated-with-object-ids object_ids group-ids)
                      (grplog/build-delete-objects-event user result "artstor_delete_pc_objects_from_groups" {:object_ids (vec object_ids)})))
                (not-found {:success true :message "Couldn't find any of the groups containing the object-id(s)"}
                           (grplog/build-delete-objects-event user "" "artstor_delete_pc_objects_from_groups" {:artstor-error "I couldn't find groups associated with given objects"}))))))
        (DELETE "/:group-id" [group-id]
          :auth-rules auth/is-admin?
          :current-user user
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :return {:success s/Bool :message s/Str}
          :path-params [group-id :- String]
          :summary "Deletes a group"
          :responses {200 {:description "Group has been deleted"}
                      400 {:description "Invalid group-id supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if-let [id (repo/delete! group-id)]
            (captains/ok {:success true :message (str "Your group (" id ") has been deleted.")}
                (grplog/build-group-event user {:id group-id} "artstor_delete_group" true))
            (not-found {:success false :message "I couldn't find a group with that id."}
                (grplog/build-group-event user {:id group-id} "artstor_delete_group" false {:artstor-error "I couldn't find a group with that id."}))))
        (POST "/:group-id/share" [group-id]
          :auth-rules auth/is-admin?
          :current-user user
          :path-params [group-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :body [token (s/maybe data/NewToken)]
          :summary "Creates a token for sharing the specified group"
          :responses {200 {:schema {:success s/Bool :token s/Str}
                           :description "One hot and fresh token"}
                      400 {:description "Invalid data supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The group-id supplied could not be found"}
                      500 {:description "Server Error handling the request"}}
          (if (>= (repo/count-group-shares group-id) 1000)
            (bad-request {:success false :message "Cannot share to more than 1000 entities"})
            (if-let [token (repo/generate-token (Long/parseLong (get user :profile_id)) group-id
                                                (get token :access_type 100) (get token :expiration_time))]
              (captains/ok {:success true :token (util/encode-uuid (UUID/fromString token))} {:group_id group-id})
              (not-found {:success false :message "I couldn't find a group with that id."}))))
        (POST "/redeem/:token" [token]
          :auth-rules auth/logged-in?
          :current-user user
          :return data/Group
          :path-params [token :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Redeems a token for access to the specified group"
          :responses {200 {:schema {:success s/Bool :group data/Group}
                           :description "A lovely group for you to use."}
                      400 {:description "Invalid token supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The token was not found or it expired."}
                      500 {:description "Server Error handling the request"}}
          (if-let [redeeming-group (repo/get-group-from-token (util/decode-uuid token))]
            (if (>= (repo/count-redeeming-group-access redeeming-group) 1000)
              (bad-request {:success false :message "Cannot share to more than 1000 entities"})
              (if-let [group (repo/redeem-token (Long/parseLong (get user :profile_id)) (util/decode-uuid token))]
                (captains/ok {:success true :group (auth/coerce-group-based-on-access-type group user)} {:group_id (get group :id "")})
                (not-found {:success false :message "That token has apparently gone stale.  We're so sorry."})))
            (not-found {:success false :message "That token has apparently gone stale.  We're so sorry."})))
        (DELETE "/expire/:token" [token]
          :auth-rules auth/logged-in?
          :current-user user
          :return data/Group
          :path-params [token :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Invalidates a token so it can no longer be used"
          :responses {200 {:schema data/RequestStatus
                           :description "A boolean indicator of success"}
                      400 {:description "Invalid token supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The token was not found"}
                      500 {:description "Server Error handling the request"}}
          (let [res (repo/delete-token! (Long/parseLong (get user :profile_id)) (util/decode-uuid token))]
            (if (> res 0)
              (captains/ok {:success true :message "I tossed your token in the bin."})
              (not-found {:success false :message "Your token has apparently gone missing.  We're so sorry."}))))
        (GET "/:group-id/tokens" [group-id]
          :auth-rules auth/is-admin?
          :current-user user
          :return [{:success s/Bool :tokens [data/Token]}]
          :path-params [group-id :- String]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Returns any tokens that are set on the group"
          :responses {200 {:schema {:success s/Bool :tokens [data/Token]}
                           :description "A list of tokens on this group"}
                      400 {:description "Invalid group-id supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "The group was not found"}
                      500 {:description "Server Error handling the request"}}
          (let [tokens (repo/get-tokens (Long/parseLong (get user :profile_id)) group-id)]
            (if (seq tokens)
              (captains/ok {:success true :tokens (map #(assoc % :token (util/encode-uuid (UUID/fromString (% :token)))) tokens)} {:group_id group-id})
              (not-found {:success false :message "I'm sorry Dave, there are no tokens here."}))))
        (GET "/tags/suggest" []
          :auth-rules auth/logged-in?
          :current-user user
          :return [{:success s/Bool :tags [s/Str]}]
          :query-params [{q :- s/Str nil} {size :- s/Int nil}]
          :header-params [{web-token :- (s/maybe s/Str) ""}]
          :summary "Returns any tags that match the group search query"
          :responses {200 {:schema {:success s/Bool :tags [s/Str]}
                           :description "A list of tags"}
                      400 {:description "Invalid query supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      404 {:schema data/RequestStatus :description "no matches found"}
                      500 {:description "Server Error handling the request"}}
          (let [number-to-return  (repo/get-valid-tag-count size)
                tags              (repo/get-tags-that-start-with q number-to-return)]
            (if (seq tags)
              (captains/ok {:success true :tags tags})
              (not-found {:success false :message "I'm sorry Dave, there are no tags here."})))))
      (context "/internal/generate" []
        :tags ["web-token"]
        (POST "/" []
          :return  [{:success s/Bool :tags [s/Str]}]
          :body [data data/ArtstorUser]
          :summary "Returns a web token"
          :responses {200 {:schema    {:success s/Bool :token s/Str}
                           :description "Generate a Web token"}
                      400 {:description "Invalid form data supplied"}
                      401 {:description "Unauthorised"}
                      403 {:description "Access denied"}
                      500 {:description "Server Error handling the request"}}
          (let [wt  (artstor-group-service.auth/generate-web-token data)]
            (if (nil? wt)
              (bad-request)
              (captains/ok {:success true :token wt})))))
      (ANY "/*" []
        :responses {404 {:schema data/RequestStatus}}
        (not-found {:success false :message "My human masters didn't plan for this eventuality.  Pity."})))
    (auth/add-cors)
    (auth/add-empty-body-to-posts)
    (captains/wrap-web-logging {:event-type-func grplog/determine-event-type})
    (user/wrap-user-or-ip)
    (with-auth {:exclude-paths [#"/index.html"
                                #"/swagger.json"
                                #".\.js"
                                #".*.js"
                                #"/images/.*"
                                #"/lib/.*"
                                #"/css/.*"
                                #"/conf/.*"
                                #"/internal/.*"
                                #"/"
                                #"/watchable"]})
    (util/wrap-method-override)
    (ring-logger/wrap-with-logger)
    (rcookie/wrap-cookies)))

