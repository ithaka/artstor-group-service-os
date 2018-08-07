(ns artstor-group-service.util
  (require
    [clojure.data.codec.base64 :as b64]
    [compojure.api.middleware :as mw]
    [clojure.string :as string]
    [clojure.tools.logging :as logger]
    [ring.util.http-response :as ring]
    [clj-sequoia.service :refer [get-hosts get-env]]
    [clj-http.client :as http]
    [cheshire.core :as json]
    [org.ithaka.clj-iacauth.token :refer [generate]])
  (:import (java.nio ByteBuffer BufferUnderflowException)
           (java.util UUID)
           (com.fasterxml.jackson.core JsonParseException)))


(defn encode-uuid [uuid]
  (let [buf (ByteBuffer/allocate (* 2 Long/BYTES))
        _ (.putLong buf (.getMostSignificantBits uuid))
        _ (.putLong buf (.getLeastSignificantBits uuid))
        bytes (.array buf)
        shortened-uuid (first (string/split (String. (b64/encode bytes)) #"="))]
    (string/replace shortened-uuid "/" "_")))

(defn decode-uuid [short-uuid]
  (let [bytes (b64/decode (.getBytes (str (string/replace short-uuid #"_" "/") "==")))
        buf (ByteBuffer/wrap bytes)]
    (try
      (str (UUID. (.getLong buf) (.getLong buf)))
      (catch BufferUnderflowException e (do (logger/error e "Caught exception decoding uuid. Returning short-uuid") {} short-uuid)))))

(defn ok
  "Override of ring 200 OK (Success) to include captains log event."
  ([] (ok nil))
  ([body] (ring/ok body))
  ([body event]
   {:status 200
    :headers {}
    :body body
    :event event}))

(defn not-found
  "Override of ring 404 Not-Found (ClientError) to include captains log event."
  ([] (not-found nil))
  ([body] (ring/not-found body))
  ([body event]
   {:status 404
    :headers {}
    :body body
    :event event}))

(defn forbidden
  "Override of ring 403 Forbidden (ClientError) to include captains log event."
  ([] (forbidden nil))
  ([body] (ring/forbidden body))
  ([body event]
   {:status 403
    :headers {}
    :body body
    :event event}))

(defn dups [seq]
  (for [[id freq] (frequencies seq)                         ;; get the frequencies, destructure
        :when (> freq 1)]                                   ;; this is the filter condition
    id))

(defn distinct-case-insensitive [items]
  (->> items
       (group-by clojure.string/upper-case)
       (#(map % (map clojure.string/upper-case items)))
       (map first)
       (distinct)))

(defn case-insensitive-items [items]
  (->> items
       (group-by clojure.string/upper-case)
       (#(map % (map clojure.string/upper-case items)))
       (map first)))

(defn wrap-method-override
  "Ring middleware for method overriding (X-HTTP-Method-Override)"
  [handler]
  (fn [req]
    (let [x-http-method (get (-> req :headers) "x-http-method-override" "")]
      (if (contains? #{"delete" "put"} (string/lower-case x-http-method))
        (handler (if x-http-method (assoc req :request-method (keyword (string/lower-case x-http-method))) req))
        (handler req)))))

(defn resolve [app-name]
  (try
    (let [service (rand-nth (get-hosts (get-env) app-name))
          ]
      (str "http://" service ":8080/"))
    (catch Exception e
      (logger/error "Couldn't talk to eureka, building local string.")
      (str "http://" app-name ".apps.test.cirrostratus.org/"))))

(defn build-service-url [app-name path]
  (str (resolve app-name) path))

(defn legacy? [id]
  (< (count id) 12))

(defn get-account-info [prof-id]
  (let [url (build-service-url "iac-service" (str "account/" prof-id))
        response (http/get url {:query-params {:idType "externalId"}
                                :throw-exceptions false})]
    (if (= (response :status) 200)
      ((json/parse-string (response :body) true) :credentials)
      {:status false :message "Unable to find user with that profile-id"})))

(defn get-user-ext-accountid-from-profileid [profile-id]
  (let [url (build-service-url "iac-service" (str "profile/" profile-id))
        response (http/get url {})]
    (if (empty? response)
      {:status false :message "Unknown error"}
      ((json/parse-string (response :body) true) :userId))))

(defn build-group-admin-web-token [profile-id institution-id]
  (let [prof-id (get-user-ext-accountid-from-profileid profile-id)
        credentials (get-account-info prof-id)
        userpass (into {} (map #(get % :USERPASS) credentials))
        username (get userpass :username)]
    (generate {:profile-id profile-id :institution-id institution-id :default-user false :username username})))
