(ns artstor-group-service.repository
  (:require
    [yesql.core :refer [defqueries]]
    [clojure.java.jdbc :as jdbc]
    [clojure.string :as string]
    [ring.swagger.schema :as rs]
    [clj-time.coerce :as time]
    [environ.core :refer [env]]
    [clojure.tools.logging :as logger]
    [artstor-group-service.search :as search]
    [artstor-group-service.schema :as g]
    [artstor-group-service.schema :as data]
    [artstor-group-service.util :as util]
    [artstor-group-service.logging :as lgx])
  (:import (com.mchange.v2.c3p0 DataSources)
           (java.util UUID)
           (java.sql PreparedStatement)))

(def db-spec {:datasource (DataSources/pooledDataSource
                            (DataSources/unpooledDataSource (env :artstor-group-db-url)))})

;; This is a macro that reads the group sql calls in the specified sql/group.sql file and
;; generates functions based on the comments in the file.
(defqueries "artstor_group_service/sql/group.sql"
            {:connection db-spec})

(extend-type java.util.Date
  jdbc/ISQLParameter
  (set-parameter [v ^PreparedStatement stmt idx]
    (.setTimestamp stmt idx (time/to-sql-time v))))

(defn destruct-sql-result [fields selector result]
  (let [recs (map first (map second (group-by selector result)))]
    (map #(select-keys % fields) recs)))

(defn exists? [id]
  (doall (println (empty? (group-exists {:id id}))))
  (not (empty? (group-exists {:id id}))))

(defn find-group-by-id [id]
  (let [rec (sql-get-group-by-id {:id id})]
    (if (seq rec)
      (let [group (first (destruct-sql-result [:name :description :sequence_number :id :public :creation_date :update_date] :id rec))
            items (destruct-sql-result [:object_id :item_seq] :item_id rec)
            shares (sql-get-group-sharing {:id id})
            tags (sql-get-group-tags {:id id})]
        (assoc group :access shares
                     :items (filter identity (map :object_id (sort-by :item_seq items)))
                     :tags (filter identity (map :tag tags)))))))

(defn get-group-access [id]
  (if-let [rec (get-group-sharing-by-id {:id id})]
    (seq rec)))

(defn scrub-whitespace-chars [input-string]
  (let [unicode-whitespace-chars #"[\u00a0\u1680\u180e\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u200b\u202f\u205f\u3000\ufeff]"
        no-unicode (string/replace input-string unicode-whitespace-chars " ")
        trimmed-string (clojure.string/trim no-unicode)]
    (string/replace trimmed-string #"\s{2,}" " ")))

(defn find-all-tags [tags]
  (let [scrubbed-tags (map #(scrub-whitespace-chars %) tags)]
  (doall (map #(add-tag! {:tag (scrub-whitespace-chars %)}) scrubbed-tags))          ; Try to add all the tags, ones that exist will silently fail
  (if (seq scrubbed-tags)
    (get-tag-ids-by-tag {:tags scrubbed-tags}))))

(defn get-group [id] (find-group-by-id id))

(defn get-groups
  "Uses Elastic Search to find groups, then trims out security access details"
  [term options]
  (let [search-results (search/search term options)
        viewable-groups (map #(assoc (dissoc % :insts-with-access :users-with-access) :access [])
                             (search-results :groups))]
    (assoc search-results :groups viewable-groups)))

(defn delete! [id]
  (jdbc/with-db-transaction
    [tx db-spec]
    (if (> (remove-group! {:id id}) 0)
      (do (search/delete id) id))))

(defn add! [new-group {:keys [profile_id]}]
  (let [id (str (UUID/randomUUID))
        group (rs/coerce! g/Group (assoc new-group :id id :public false))
        new-access (filter #(not (= profile_id (% :entity_identifier))) (group :access))
        admin-user {:entity_type 100 :entity_identifier profile_id :access_type 300}
        group (assoc group :access (conj new-access admin-user))
        deduped-items (util/distinct-case-insensitive (get group :items))
        group (assoc group :items deduped-items)]
    (jdbc/with-db-transaction
      [tx db-spec]
      (add-group! group)
      (doall (map #(add-group-sharing-info! (assoc % :group_id (group :id))) (group :access)))
      (doall (map #(add-items! (assoc % :group_id (group :id)))
                  (map-indexed (fn [s o] {:object_id o :sequence_number s}) (take 1000 (group :items)))))
      (if-let [tag_ids (seq (map :id (find-all-tags (take 50 (group :tags)))))]
        (doall (map #(associate-tags! (assoc % :group_id (group :id))) (map (fn [o] {:tag_id o}) tag_ids))))
      (search/index (get-group id))
      (get-group id))))

(defn copy! [src-group-id new-name user]
  (if-let [src-group (get-group src-group-id)]
    ;; Need to add access portion back when we have it.
    (add! {:description (src-group :description) :items (src-group :items) :tags []
           :access [{:entity_type 100 :entity_identifier (user :profile_id) :access_type 300}]
           :sequence_number (src-group :sequence_number) :name new-name} user)))

; Admin function to set the public flag
(defn update-public-flag! [id group]
  "Update group flag only and return the updated group."
    (jdbc/with-db-transaction
      [tx db-spec]
      (do (update-group-public-flag! {:id id :public (group :public)})
          (dissoc (search/index (get-group id)) :insts-with-access :users-with-access))))

(defn ensure-admin-exists [new-group existing-group]
  (if (some #(= 300 (% :access_type)) (new-group :access))
    new-group
    (let [group-admins (filter #(= 300 (% :access_type)) (existing-group :access))]
      (assoc new-group :access (flatten (conj (new-group :access) group-admins))))))

; To update a group we basically remove all the attributes of the group and recreate them instead of trying to pick
; apart what has changed.
(defn update! [id group]
  "Update a group and return the updated group."
  (let [group (rs/coerce! g/NewGroup group)
        group (ensure-admin-exists group (get-group id))
        deduped-items (util/distinct-case-insensitive (get group :items))
        group (assoc group :items deduped-items)]
    (jdbc/with-db-transaction
      [tx db-spec]
      (do (update-group! (assoc group :id id))                                               ; clearing phase
          (remove-group-sharing-info! {:group_id id})
          (remove-all-items! {:group_id id})
          (clear-tags! {:group_id id}))
      (doall (map #(add-group-sharing-info! (assoc % :group_id id)) (group :access)))        ; inserting phase
      (doall (map #(add-items! (assoc % :group_id id))
                  (map-indexed (fn [s o] {:object_id o :sequence_number s}) (take 1000 (group :items)))))
      (let [tag_ids (map :id (find-all-tags (take 50 (group :tags))))]
        (doall (map #(associate-tags! (assoc % :group_id id)) (map (fn [o] {:tag_id o}) tag_ids))))
      (dissoc (search/index (get-group id)) :insts-with-access :users-with-access))))

; Tokens!
;
; Tokens can be generated and redeemed to supply access to image groups.
(defn redeem-token [profile-id token]
  (if-let [{:keys [group_id access_type]} (first (get-token-info {:token (str token)}))]
    (let [access (filter #(= (str profile-id) (% :entity_identifier)) (get-group-access group_id))]
      (if (or (empty? access) (> access_type ((first access) :access_type)))
        (jdbc/with-db-transaction
          [tx db-spec]
          (do
            (remove-group-sharing-for-user! {:group_id group_id :entity_identifier (str profile-id)})
            (add-group-sharing-info! {:group_id group_id :entity_type 100 :entity_identifier profile-id
                                      :access_type access_type})
            (dissoc (search/index (get-group group_id)) :insts-with-access :users-with-access)))
        (get-group group_id)))))

(defn generate-token
  ([profile-id group-id] (generate-token profile-id group-id 100 nil))
  ([profile-id group-id access-type] (generate-token profile-id group-id access-type nil))
  ([profile-id group-id access-type expiration-timestamp]
   (let [token (str (UUID/randomUUID))]
      (jdbc/with-db-transaction
        [tx db-spec]
        (do
          (create-token! {:token token :group_id group-id :access_type access-type :expires expiration-timestamp
                          :created_by profile-id}))
        token))))

(defn delete-token! [profile-id token]
  (jdbc/with-db-transaction
    [tx db-spec]
    (do
      (expire-token! {:created_by profile-id :token token}))))

(defn get-tokens [profile-id group-id]
  (jdbc/with-db-transaction
    [tx db-spec]
    (do
      (get-users-tokens {:created_by profile-id :group_id group-id}))))

(defn filter-access-object-by-user
  "Trims the access object to only those from the supplied user"
  [{:keys [profile_id institution_ids]} group-access]
  (let [users-with-access (filter #(= 100 (% :entity_type)) group-access)
        insts-with-access (filter #(= 200 (% :entity_type)) group-access)]
    (if-let [matched-user (first (filter #(= profile_id (% :entity_identifier)) users-with-access))]
      [matched-user]
      (if-let [matched-inst (first (filter #(contains? (set institution_ids) (% :entity_identifier)) insts-with-access))]
        [matched-inst]
        []))))

(defn count-group-shares [group_id]
  (count (get-group-access group_id)))

(defn get-group-from-token [token]
  (let [{:keys [group_id]} (first (get-token-info {:token (str token)}))]
    (get-group group_id)))

(defn count-redeeming-group-access [redeeming-group]
  (count (redeeming-group :access)))

(defn get-tags-that-start-with [partial-tag-name limit]
  (let [tags (get-existing-tags {:tag (string/lower-case (str partial-tag-name "%")) :limit limit})]
        (map :tag tags)))

(defn get-valid-tag-count [size]
  (let [number-to-return (if (integer? size) size 50)
        number-to-return (if (> number-to-return 50) 50 number-to-return)
        number-to-return (if (< number-to-return 1) 1 number-to-return)]
        number-to-return))

(defn get-group-owner [id] ((first (sql-get-group-admin {:group_id id})):entity_identifier))

(defn get-groups-associated-with-object-ids [object-ids]
  (group-by :object_id (sql-get-groups-of-given-object-id {:object_ids object-ids})))

(defn delete-groups-associated-with-object-ids [object-ids group-ids]
  (jdbc/with-db-transaction
    [tx db-spec]
    (do
      (delete-objects-from-groups! {:object_ids object-ids})))
  (doall (map #(search/index (get-group %)) group-ids))
  {:success true :message "Objects deleted from all image groups"})

(defn get-deduped-group [group]
  (let [items (get group :items)
        id (get group :id)
        items-case-insensitive (util/case-insensitive-items items)
        duplicate-items (util/dups items-case-insensitive)]
    (if (empty? duplicate-items)
      group
      (do (logger/error {:message "Duplicate items found in an image group" :group_id id :duplicate_items duplicate-items})
          (assoc group :items (util/distinct-case-insensitive items-case-insensitive))))))
