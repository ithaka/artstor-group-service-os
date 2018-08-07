(ns artstor-group-service.service.metadata
  (:require
    [yesql.core :refer [defqueries]]
    [clojure.string :as string]
    [environ.core :refer [env]]
    [clj-http.client :as http]
    [artstor-group-service.repository :as repo]
    [artstor-group-service.util :as util]))

(defn build-query-string-group-metadata [object_ids legacy xml]
  (let [str_object_ids (string/join "&" (map #(str "object_ids=" %) object_ids))
        str_legacy (str "&legacy=" legacy)
        str_xml (str "&xml=" xml)]
    (str str_object_ids str_legacy str_xml "&internal=true")))

(defn get-group-metadata [web-token object_ids legacy xml]
  "To get the meta data of a group from the group service given a group-id"
  (let [url (util/build-service-url "artstor-metadata-service" (str "api/v1/metadata?" (build-query-string-group-metadata object_ids legacy xml)))
        response (http/get url {:headers {"web-token" web-token} :throw-exceptions false})] response))

(defn get-object-legacy-metadata [web-token object-id]
  "To get the legacy formatted metadata of an item"
  (let [url (util/build-service-url "artstor-metadata-service" (str "api/v1/metadata/legacy/" object-id ))
        response (http/get url {:headers {"web-token" web-token} :throw-exceptions false})] response))

(defn build-query-string-group-items [object_ids]
  (str (string/join "&" (map #(str "object_id=" %) object_ids)) "&internal=true"))

(defn get-group-items [web-token object_ids]
  "To get the item data of a group from the group service given a group-id"
  (let [url (util/build-service-url "artstor-metadata-service" (str "/api/v1/items?" (build-query-string-group-items object_ids)))
        response (http/get url {:headers {"web-token" web-token} :throw-exceptions false})] response))

(defn get-metadata [group-id object_ids legacy xml institute_id]
  (if-let [found-group (repo/get-group group-id)]
    (let [group-web-token  (util/build-group-admin-web-token (repo/get-group-owner group-id) institute_id)]
      (get-group-metadata group-web-token object_ids legacy xml))
    ({:success false :message "I couldn't find a group/item with that id."})))

(defn get-legacy-metadata [group-id object-id institute_id]
  (if-let [found-group (repo/get-group group-id)]
    (let [group-web-token  (util/build-group-admin-web-token (repo/get-group-owner group-id) institute_id)]
      (get-object-legacy-metadata group-web-token object-id))
    ({:success false :message "I couldn't find a group/item with that id."})))

(defn get-items [group-id object_ids institute_id]
  (if-let [found-group (repo/get-group group-id)]
    (let [group-web-token  (util/build-group-admin-web-token (repo/get-group-owner group-id) institute_id)]
      (get-group-items group-web-token object_ids))
    ({:success false :message "I couldn't find a group/item with that id."})))