(ns artstor-group-service.logging
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as cljlog]
    [clojure.spec :as spec]
    [clj-sequoia.datastructure :as ds]
    [clj-sequoia.logging :as cptlog]
    [ring.util.codec :as codec]
    [ring.util.http-response :as ring]
    [org.ithaka.clj-iacauth.core :refer [get-session-id]]
    [cheshire.core :as cheshire]
    [artstor-group-service.schema :as schema]))

(defn determine-event-type
  "Determines the type of event based on the Request Method and uri passed in"
  [req]
  (let [uri (req :uri)]
    (if (str/starts-with? uri "/api")
      (cond
        (and (= uri "/api/v1/group") (= (req :request-method) :get)) "artstor_search_group"
        (and (= uri "/api/v1/group") (= (req :request-method) :post)) "artstor_create_group"
        (and (str/starts-with? uri "/api/v1/group") (= (req :request-method) :delete) (= 5 (count (str/split uri #"/")))) "artstor_delete_group"
        (and (str/starts-with? uri "/api/v1/group/") (= (req :request-method) :get) (= 5 (count (str/split uri #"/")))) "artstor_read_group"
        (and (str/starts-with? uri "/api/v1/group/") (= (req :request-method) :put) (= 5 (count (str/split uri #"/")))) "artstor_update_group"
        (and (str/starts-with? uri "/api/v1/group") (str/ends-with? uri "copy") (= (req :request-method) :post)) "artstor_copy_group"
        (and (str/starts-with? uri "/api/v1/group") (str/ends-with? uri "metadata") (= (req :request-method) :get)) "artstor_get_metadata"
        (and (str/starts-with? uri "/api/v1/group") (str/includes? uri "/secure/metadata/") (= (req :request-method) :get)) "artstor_get_metadata_legacy_format"
        (and (str/starts-with? uri "/api/v1/group") (str/includes? uri "items") (= (req :request-method) :get)) "artstor_get_items_with_GET"
        (and (str/starts-with? uri "/api/v1/group") (str/ends-with? uri "/admin/public") (= (req :request-method) :put)) "artstor_admin_update_group_public_flag"
        (and (str/starts-with? uri "/api/v1/group") (str/ends-with? uri "/items/delete") (= (req :request-method) :put)) "artstor_delete_pc_objects_from_groups"
        (and (str/starts-with? uri "/api/v1/group") (str/ends-with? uri "share") (= (req :request-method) :post)) "artstor_create_group_share_token"
        (and (str/starts-with? uri "/api/v1/group") (str/includes? uri "/redeem/") (= (req :request-method) :post)) "artstor_redeem_group_share_token"
        (and (str/starts-with? uri "/api/v1/group") (str/includes? uri "/expire/") (= (req :request-method) :delete)) "artstor_invalidate_group_share_token"
        (and (str/starts-with? uri "/api/v1/group") (str/ends-with? uri "tokens") (= (req :request-method) :get)) "artstor_get_group_tokens"
        (and (str/starts-with? uri "/api/v1/group") (str/includes? uri "/tags/suggest") (= (req :request-method) :get)) "artstor_search_group_tags"
        :else "artstor_undefined_group_api_event")
      :no-op  ;default uses ring logging only, does not log to captains
      )))


(defn build-loggable-user-pieces
  "Builds the user portion of the captains log event "
  [user]
  {:profileid (get user :profile_id)
   :institution_id (if-let [institutions (get user :institution_ids)] (first institutions) "")
   })

(defn build-search-event
  "Builds log event message for new group create action"
  [user query]
  (let [search-message {:eventtype "artstor_search_group" :query (codec/form-encode query) }]
    (merge search-message (build-loggable-user-pieces user))))

(defn build-create-event
  "Builds log event message for new group create action"
  [user newlygroup]
  (let [create-group-event-message {:eventtype "artstor_create_group" :group_id (get newlygroup :id)}]
    (merge create-group-event-message (build-loggable-user-pieces user))))

(defn is-group-owner?
  "Expects the full non-coerced access object of the group"
  [{:keys [access]} {:keys [profile_id]}]
  (let [owners (filter #(and (= 100 (% :entity_type))(= 300 (% :access_type))) access)
        matched-owner (filter #(= profile_id (% :entity_identifier)) owners)]
    (if-not (empty? matched-owner) true false)))

(defn tag-internal-events
  "looks for the internal flag and adjusts eventtype accordingly"
  [{:keys [internal]} eventtype]
  (if internal (str eventtype "_internal") eventtype))

(defn build-group-event
  "Builds log event message for group read action"
  ([user group event-type stat]
   (build-group-event user group event-type stat nil))
  ([user group event-type stat extras]
    (let [massaged-event-type (if (and (= "artstor_read_group" event-type) (not (is-group-owner? group user))) "artstor_view_image_group" event-type)
          group-event-message {:eventtype (tag-internal-events extras massaged-event-type)
                               :group_id (get group :id) :status stat}]
      (merge group-event-message (build-loggable-user-pieces user) extras))))

(defn build-one-delete-pc-object-message
  [one-pcobject-with-groups]
  { :object_id (first one-pcobject-with-groups) :group_ids (map #(:group_id %) (second one-pcobject-with-groups))})

(defn build-delete-objects-event
  "Builds log event for groups where its objects are deleted"
  ([user pcobj-groups-data event-type]
   (build-delete-objects-event user pcobj-groups-data event-type nil))
  ([user pcobj-groups-data event-type extras]
  (let [del-items (map #(build-one-delete-pc-object-message %) pcobj-groups-data)
        group-event-message {:eventtype event-type :deleted_objects del-items}]
    (merge group-event-message (build-loggable-user-pieces user) extras))))


(defn with-request-logging
  [handler]
  (fn [^Exception e data req]
    (try
      (do
        (.reset (req :body))
        (cljlog/error (str "Invalid " (req :request-method) " " (req :uri) " - " (.getMessage e) "\n" (slurp (:body req)) "\n" data)))
      (catch Exception e
        (cljlog/error "Failed to log http error.")))
    (handler e data req)))

