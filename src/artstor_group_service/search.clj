(ns artstor-group-service.search
  (:require
    [aws-sig4.middleware :refer [build-wrap-aws-auth wrap-aws-date]]
    [clojurewerkz.elastisch.rest.document :as doc]
    [clojure.data.json]
    [clj-http.client :as http]
    [clojurewerkz.elastisch.rest :as es]
    [artstor-group-service.schema :as g]
    [environ.core :refer [env]]
    [schema.core :as s]
    [clojurewerkz.elastisch.query :as q]
    [clojure.reflect :as r]
    [clojure.string :as string]))



(def es-conn (es/connect (env :artstor-group-es-url)
                         {:connection-manager (clj-http.conn-mgr/make-reusable-conn-manager {:timeout 10})}))

(defn- get-creds
  "get AWS credentials dict"
  []
  (let [creds (.getCredentials (com.amazonaws.auth.DefaultAWSCredentialsProviderChain.))]
    (merge
      {:access-key (.getAWSAccessKeyId creds)
       :secret-key (.getAWSSecretKey creds)}
      ;If these are STS creds, get the token
      (when (some #(= 'getSessionToken (:name %)) (:members (r/reflect creds)))
        {:token (.getSessionToken creds)}))))


(defmacro with-auth [& body]
  `(http/with-additional-middleware [(build-wrap-aws-auth (merge (get-creds) {:region "us-east-1" :service "es"}))
                                     wrap-aws-date] ~@body))


(defn index [{:keys [id] :as group}]
  (let [idx-group (assoc group :insts-with-access
                               (map :entity_identifier (filter #(= 200 (% :entity_type)) (group :access)))
                               :users-with-access
                               (map :entity_identifier (filter #(= 100 (% :entity_type)) (group :access)))
                               :owner
                               (map :entity_identifier (filter #(and (= 300 (% :access_type))
                                                                     (= 100 (% :entity_type))) (group :access))))]
    (s/validate g/IndexableGroup idx-group)
    (do (with-auth (doc/put es-conn "imagegroups_v3" "groups" id idx-group))))
  group)

(defn delete [id]
  (do (with-auth (doc/delete es-conn "imagegroups_v3" "groups" id)))
  id)

(defn build-query [term {:keys [tags from size profile-id institution-ids level] :or
                               {tags [] from 0 size 24 level :all}}]
  (let [query (if (nil? term) (q/match-all) {:query_string {:query (string/replace term #"[{}\[\]^\"\\]" #(str "\\" %1))
                                                            :default_field "name"}})
        query (if (empty? tags)
                {:query {:bool {:must query}}}
                {:query {:bool {:must query :filter (map #(assoc {} :term {"tags" %}) tags)}}})
        query (condp = level
                :all (assoc-in query [:query :bool]
                               (merge (-> query :query :bool)
                                      {:should (filter identity
                                                       [(if profile-id {:term {:users-with-access profile-id}})
                                                        (if (not (empty? institution-ids))
                                                          {:terms {:insts-with-access institution-ids}})
                                                          {:term {:public true}}])
                                       :minimum_should_match 1}))
                :private (assoc-in query [:query :bool :filter]
                                   (merge (-> query :query :bool :filter)
                                          {:term {:owner profile-id}}))
                :shared (assoc-in query [:query :bool]
                                  (merge (-> query :query :bool)
                                         {:filter (merge (-> query :query :bool :filter)
                                                         {:term {:users-with-access profile-id}})
                                          :must_not {:term {:owner profile-id}}}))
                :institution (assoc-in query [:query :bool :filter]
                                       (merge (-> query :query :bool :filter)
                                              {:terms {:insts-with-access institution-ids}}))
                :public (assoc-in query [:query :bool :filter]
                                  (merge (-> query :query :bool :filter)
                                         {:term {:public true}})))
        query (assoc query :aggs {:tags {:terms {:field :tags :size 250}}})
        query (if (nil? term) (assoc query :sort [{"name.raw" "asc"}]) query)]
    (assoc query :from from :size (if (> size 100) 100 size)
                 :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"])))

;; Define a nice search interface by using ES features for facets, search and paging.
(defn search [term options]
  (let [query (build-query term options)]
    (let [res (with-auth (doc/search es-conn "imagegroups_v3" "groups" query))
          total (-> res :hits :total)
          recs (map :_source (-> res :hits :hits))
          tags (get (get (-> res :aggregations) :tags) :buckets)]
      {:success true :total total :groups recs :tags tags})))
