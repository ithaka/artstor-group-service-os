(ns artstor-group-service.repository-test
  (:require [clojure.test :refer :all]
            [clojurewerkz.elastisch.rest.document :as es-client]
            [ragtime.jdbc :as jdbc]
            [ragtime.repl :refer [migrate rollback]]
            [environ.core :refer [env]]
            [artstor-group-service.schema :refer :all]
            [cheshire.core :as cheshire]
            [schema.core :as s]
            [ring.mock.request :as mock]
            [schema.coerce :as coerce]
            [artstor-group-service.repository :as repo])
  (:import (org.h2.jdbc JdbcBatchUpdateException)))

(def config {:datastore (jdbc/sql-database {:connection-uri (env :artstor-group-db-url)})
             :migrations (jdbc/load-resources "test-migrations")})

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(def group-data
  '(
     {:id "1ec1adbf-b7f9-40bb-a4d4-f2940d211731" :name "Some random group" :description "My new group" :sequence_number 0
      :share_id 3 :access_type 200 :entity_identifier "Will" :entity_type 100 }
     {:id "1ec1adbf-b7f9-40bb-a4d4-f2940d211731" :name "Some random group" :description "My new group" :sequence_number 0
      :share_id 5 :access_type 300 :entity_identifier "Artstor" :entity_type 200 }
     {:id "1ec1adbf-b7f9-40bb-a4d4-f2940d211731" :name "Some random group" :description "My new group" :sequence_number 0
      :share_id 7 :access_type 200 :entity_identifier "Bill" :entity_type 100 }
     {:id "1ec1adbf-b7f9-40bb-a4d4-f2940d211731" :name "Some random group" :description "My new group" :sequence_number 0
      :share_id 8 :access_type 300 :entity_identifier "Ithaka" :entity_type 200 }
     {:id "1ec1adbf-b7f9-40bb-a4d4-f2940d211731" :name "Some random group" :description "My new group" :sequence_number 0
      :share_id 9 :access_type 200 :entity_identifier "Sai" :entity_type 100 }
     )
  )

(deftest test-destruct-sql-result
  (testing "destruct-sql-result-data"
    (let [group (repo/destruct-sql-result [:id :name :description :sequence_number :creation_date :update_date] :id group-data)
          shares (repo/destruct-sql-result [:access_type :entity_identifier :entity_type] :share_id group-data)]
      (is (= 1 (count group)))
      (is (= "Some random group" ((first group) :name)))
      (is (= 5 (count shares)))
      (is (= {:access_type 200 :entity_identifier "Will" :entity_type 100} (first shares))))))

(deftest test-database-crud-operations
  ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)]
    (with-db config
       (testing "Retrieve group-sharing info"
         (let [share (repo/get-group-access 1)]
           (println "Test Share data for group 1=" share)
           )))
    (with-db config
      (testing "Retrieve a single group"
        (let [group (repo/get-group 1)]
          (is (= "First Group" (group :name)))
          (is (contains? group :creation_date))
          (is (not (nil? (group :creation_date))))
          (is (contains? group :update_date))
          (is (not (nil? (group :update_date))))
          (is (nil? (s/check Group group))))))
    (with-db config
             (testing "Retrieve a group doesn't return nulls for missing items or tags"
               (let [group (repo/get-group 3)]
                 (is (= "Third Group" (group :name)))
                 (is (nil? (s/check Group group)))
                 (is (contains? group :creation_date))
                 (is (not (nil? (group :creation_date))))
                 (is (contains? group :update_date))
                 (is (not (nil? (group :update_date))))
                 (is (not (= [nil] (group :items))))
                 (is (not (= [nil] (group :tags)))))))
    (with-db config
      (testing "Delete a group"
        (repo/delete! 1)
        (is (nil? (repo/get-group 1)))
        (is (get (first (repo/get-group-ignore-deletion-status {:id 1})) :deleted))))
    (with-db config
      (testing "Create a group"
        (let [user { :profile_id "Will" :institution_ids [1000]}
              new-group (repo/add! {:name "Another group" :description "A tasty new group" :sequence_number 1
                                    :access [{:entity_type 100 :entity_identifier "Will" :access_type 200}]
                                    :items ["objectid2" "objectid1" "objectid2" "objectid1" "objectid1"]
                                    :tags ["    tag1" "tag2" "tag3"]} user)
              id (get new-group :id)]
          (is (nil? (s/check Group new-group)))
          (is (= 3 (count (new-group :tags))))
          (is (= ["tag1" "tag2" "tag3"] (new-group :tags)))
          (is (= ["objectid2" "objectid1"] (new-group :items)))
          (is (= 2 (count (new-group :items))))
          (is (= new-group (repo/get-group id)))
          (is (contains? new-group :creation_date))
          (is (not (nil? (new-group :creation_date))))
          (is (contains? new-group :update_date))
          (is (not (nil? (new-group :update_date))))
          (is (= [{:entity_type 100 :entity_identifier "Will" :access_type 300}] (new-group :access))))))
    (with-db config
             (testing "Copy a group"
               (let [user { :profile_id "Will" :institution_ids [1000]}
                     new-group (repo/copy! 1 "A copied group" user)
                     id (get new-group :id)]
                 (is (nil? (s/check Group new-group)))
                 (is (= 0 (count (new-group :tags))))
                 (is (= 1 (count (new-group :access))))
                 (is (= new-group (repo/get-group id))))))
    (with-db config
             (testing "Update public flag of group"
               (let [updated-group (repo/update-public-flag! "1" {:name "Ignore changing the name" :public true })]
                 (is (= (updated-group :public) ((repo/get-group "1") :public)))
                 (is (= (updated-group :public) true))
                 (is (= "First Group" (get updated-group :name))))))
    (with-db config
      (testing "Update a group"
        (let [updated-group (repo/update! "1" {:name "Changing the name" :description "Fun!" :sequence_number 100
                                               :access [{:entity_type 100 :entity_identifier "Will" :access_type 200}]
                                               :items ["objectid4" "objectid4" "objectid3" "objectid4" "objectid3"]
                                               :tags ["tag1" "tag2"]})]
          (is (nil? (s/check Group updated-group)))
          (is (= updated-group (repo/get-group "1")))
          (is (= 2 (count (updated-group :tags))))
          (is (= 2 (count (updated-group :items))))
          (is (contains? updated-group :creation_date))
          (is (not (nil? (updated-group :creation_date))))
          (is (contains? updated-group :update_date))
          (is (not (nil? (updated-group :update_date))))
          (is (not (= (updated-group :creation_date) (updated-group :update_date))))
          (is (= ["objectid4" "objectid3"] (updated-group :items)))
          (is (= "Changing the name" (get updated-group :name))))))))

(deftest test-item-ordering-contract
  ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)]
    (with-db config
             (testing "Create a group with items in a particular order"
               (let [user { :profile_id "Will" :institution_ids [1000]}
                     new-group (repo/add! {:name "Another group" :description "A tasty new group" :sequence_number 1
                                           :access [{:entity_type 100 :entity_identifier "Will" :access_type 200}]
                                           :items ["objectid3" "objectid6" "objectid1" "objectid4" "objectid2" "objectid6" "objectid2"]
                                           :tags ["tag1" "tag2" "tag3"]} user)
                     id (get new-group :id)]
                 (is (nil? (s/check Group new-group)))
                 (is (= ["objectid3" "objectid6" "objectid1" "objectid4" "objectid2"]
                        ((repo/get-group id) :items)))))
             (testing "Update order in a group"
               (let [updated-group
                     (repo/update! "1" {:name "Changing the name" :description "Fun!" :sequence_number 100
                                        :access [{:entity_type 100 :entity_identifier "Will" :access_type 200}]
                                        :items ["five item" "one item" "four item" "two item" "three item" "one item" "four item" "two item"]
                                        :tags ["tag1" "tag2"]})]
                 (is (= ["five item" "one item" "four item" "two item" "three item"]
                        ((repo/get-group "1") :items))))))))

(deftest test-maximum-entries-constraints
  ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)]
    (with-db config
             (testing "create a group with more then 1000 items and expect only a 1000 will be saved"
               (let [user { :profile_id "Will" :institution_ids [1000]}
                     new-group (repo/add! {:name "Another group" :description "A tasty new group" :sequence_number 1
                                           :access [{:entity_type 100 :entity_identifier "Will" :access_type 200}]
                                           :items (map str (range 1050))
                                           :tags ["tag1" "tag2" "tag3"]} user)
                     id (get new-group :id)]
                 (is (nil? (s/check Group new-group)))
                 (is (= 1000 (count ((repo/get-group id) :items)))))))
    (with-db config
             (testing "create a group with more then 50 tags and expect only a 50 will be saved"
               (let [user { :profile_id "Will" :institution_ids [1000]}
                     new-group (repo/add! {:name "Another group" :description "A tasty new group" :sequence_number 1
                                           :access [{:entity_type 100 :entity_identifier "Will" :access_type 200}]
                                           :items ["one" "two" "three"]
                                           :tags (map str (range 60))} user)
                     id (get new-group :id)]
                 (is (nil? (s/check Group new-group)))
                 (is (= 50 (count ((repo/get-group id) :tags)))))))))


(deftest test-token-generation-expiration-and-redemption
  ; Redefine elasticsearch clients calls to prevent trying to actually talk to ES.
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)]
    (with-db config
       (testing "Test creating a token and redeeming it, simplest case with long profile-id"
         (let [my-token (repo/generate-token (Long/parseLong "3581231615") 1)]
           (is (= (type my-token) java.lang.String))
           ; Try redeeming it
           (let [group (repo/redeem-token 12345 my-token)]
             (is (nil? (s/check Group group)))
             (is (not-empty (filter #(= (% :entity_identifier) "12345") (group :access))))))))
    (with-db config
      (testing "Test redeeming an existing token"
        (let [my-token "9bb3315a-df55-4b3b-bdbc-7421e6d79961"
              group (repo/redeem-token 12345 my-token)]
          (is (nil? (s/check Group group)))
          (is (not-empty (filter #(= (% :entity_identifier) "12345") (group :access)))))))
    (with-db config
      (testing "Test redeeming an invalid token"
        (let [my-token "not-a-real-token"
              group (repo/redeem-token 12345 my-token)]
          (is (nil? group)))))
    (with-db config
      (testing "Test listing group tokens for a user"
        (let [tokens (repo/get-tokens 299277 "1")]
          (is (> (count tokens) 0)))))
    (with-db config
      (testing "Test listing group tokens for a user who is not the admin"
        (let [tokens (repo/get-tokens 12313 "1")]
          (is (= (count tokens) 0) "User who is not admin should never see tokens"))))
    (with-db config
      (testing "Test creating a token and redeeming it, simplest case"
        (let [my-token (repo/generate-token 299277 1)]
          (is (= (type my-token) java.lang.String))
          ; Try redeeming it
          (let [group (repo/redeem-token 12345 my-token)]
            (is (nil? (s/check Group group)))
            (is (not-empty (filter #(= (% :entity_identifier) "12345") (group :access))))))))
    (with-db config
      (testing "Assigning a lower level of access via token redemption should not update access level"
        (let [my-token (repo/generate-token 299277 1)]
          (is (= (type my-token) java.lang.String))
          (let [group (repo/redeem-token 299277 my-token)]
            (is (nil? (s/check Group group)))
            (let [access (first (filter #(= (% :entity_identifier) "299277") (group :access)))]
              (is (= 300 (access :access_type))))))))
    (with-db config
      (testing "Assigning a higher level of access via token redemption works"
        (let [my-token (repo/generate-token 299277 1 200)]
          (is (= (type my-token) java.lang.String))
          (let [group (repo/redeem-token "will" my-token)]
            (is (nil? (s/check Group group)))
            (let [access (filter #(= (% :entity_identifier) "will") (group :access))]
              (is (= 1 (count access)))
              (is (= 200 ((first access) :access_type))))))))
    (with-db config
      (testing "Test creating a token and redeeming it specifying access-type"
        (let [my-token (repo/generate-token 299277 1 200)]
          (is (= (type my-token) java.lang.String))
          ; Try redeeming it
        (let [group (repo/redeem-token 12345 my-token)]
          (is (nil? (s/check Group group)))
          (is (not-empty (filter #(= (% :entity_identifier) "12345") (group :access))))))))
    (with-db config
      (testing "Test creating a token and redeeming it specifying access-type and expiration"
        (let [my-token (repo/generate-token 299277 1 200 "2025-01-01")]
          (is (= (type my-token) java.lang.String))
          ; Try redeeming it
        (let [group (repo/redeem-token 12345 my-token)]
          (is (nil? (s/check Group group)))
          (is (not-empty (filter #(= (% :entity_identifier) "12345") (group :access))))))))
    (with-db config
      (testing "Test creating a token with an invalid date"
        (is (thrown? JdbcBatchUpdateException (repo/generate-token 299277 1 200 "not-a-timestamp")))))
    (with-db config
      (testing "Test redeeming an expired token"
        (let [my-token (repo/generate-token 299277 1 200 "2000-01-01")]
          (is (= (type my-token) java.lang.String))
          ; Try redeeming it
          (is (nil? (repo/redeem-token 6789 my-token))))))
    (with-db config
      (testing "Test expiring a valid token"
        (let [res (repo/delete-token! 299277 "9bb3315a-df55-4b3b-bdbc-7421e6d79961")]
          (is (= 1 res))
          (is (nil? (repo/redeem-token 6789 "9bb3315a-df55-4b3b-bdbc-7421e6d79961"))))))
    (with-db config
      (testing "Test expiring an invalid token"
        (is (= 0 (repo/delete-token! 299277 "not-a-real-token")))))
    (with-db config
      (testing "Test expiring an someone else's token"
        (is (= 0 (repo/delete-token! 299277 "token2")))))
    (with-db config
      (testing "redeeming a token twice does not duplicate access information"
        (repo/redeem-token 99999 "9bb3315a-df55-4b3b-bdbc-7421e6d79961")
        (repo/redeem-token 99999 "9bb3315a-df55-4b3b-bdbc-7421e6d79961")
        (let [group (repo/get-group 1)]
          (is (nil? (s/check Group group)))
          (is (= 1 (count (filter #(= (% :entity_identifier) "99999") (group :access))))))))))

(deftest test-group-must-contain-admin
  (with-redefs [es-client/put (fn [_ _ _ id rec] rec)
                es-client/delete (fn [_ _ _ id] id)]
    (with-db config
       (testing "ensure-admin-exists adds the original admin to an empty group access block"
          (let [my-group (repo/ensure-admin-exists {} {:access [{:access_type 300}]})]
             (is (= {:access [{:access_type 300}]} my-group))))
       (testing "ensure-admin-exists adds the orginal admin if the new group doesn't have one"
          (let [my-group (repo/ensure-admin-exists {:access [{:access_type 100} {:access_type 200}]}
                                                   {:access [{:access_type 300}]})]
             (is (= {:access [{:access_type 100} {:access_type 200} {:access_type 300}]} my-group))))
       (testing "ensure-admin-exists returns the new group unchanged if it contains an admin"
          (let [my-group (repo/ensure-admin-exists {:access [{:access_type 300 :entity_identifier "new"}]}
                                                   {:access [{:access_type 300 :entity_identifier "existing"}]})]
             (is (= {:access [{:access_type 300 :entity_identifier "new"}]} my-group)))))))

(deftest test-retrieving-tags
  (with-db config
    (testing "testing some stuff out"
      (is (= 3 (count (repo/get-tags-that-start-with "fi" 20))))
      (is (= 2 (count (repo/get-tags-that-start-with "fi" 2))))
      (is (=  "First not so magical tag" (first (repo/get-tags-that-start-with "first not so" 3))))
      (is (= "second-magical-tag" (first (repo/get-tags-that-start-with "Second" 3)))))
    (testing "get valid tag count"
      (is (= 50 (repo/get-valid-tag-count nil)))
      (is (= 7 (repo/get-valid-tag-count 7)))
      (is (= 50 (repo/get-valid-tag-count 50)))
      (is (= 50 (repo/get-valid-tag-count 100)))
      (is (= 1 (repo/get-valid-tag-count 0)))
      (is (= 1 (repo/get-valid-tag-count -1)))
      )))

(deftest test-whitespace-characters-in-tags
  (testing "does whitespace scrubber work?"
    (is (= "" (repo/scrub-whitespace-chars " ")))
    (is (= "abcde" (repo/scrub-whitespace-chars "    abcde")))
    (is (= "this" (repo/scrub-whitespace-chars "this\u00a0")))
    (is (= "that" (repo/scrub-whitespace-chars "\u1680that")))
    (is (= "the other" (repo/scrub-whitespace-chars "the\u180eother")))
    (is (= "the quick" (repo/scrub-whitespace-chars "the\u2000quick")))
    (is (= "brown fox" (repo/scrub-whitespace-chars "brown\u2001fox")))
    (is (= "jumped over" (repo/scrub-whitespace-chars "jumped\u2002over")))
    (is (= "the lazy" (repo/scrub-whitespace-chars "the\u2003lazy")))
    (is (= "dog" (repo/scrub-whitespace-chars "\u2004dog")))
    (is (= "buzzards gotta" (repo/scrub-whitespace-chars "buzzards\u2005gotta")))
    (is (= "eat same" (repo/scrub-whitespace-chars "eat\u2006same")))
    (is (= "as worms" (repo/scrub-whitespace-chars "as\u2007worms")))
    (is (= "I'm your huckleberry" (repo/scrub-whitespace-chars "I'm\u2008your\u2008huckleberry")))
    (is (= "dyin aint" (repo/scrub-whitespace-chars "dyin\u2009aint")))
    (is (= "much of" (repo/scrub-whitespace-chars "much\u200aof")))
    (is (= "a living" (repo/scrub-whitespace-chars "a\u200bliving")))
    (is (= "allow me" (repo/scrub-whitespace-chars "allow\u202fme")))
    (is (= "to present" (repo/scrub-whitespace-chars "to\u205fpresent")))
    (is (= "a pair" (repo/scrub-whitespace-chars "a\u3000pair")))
    (is (= "of fellow sophisticates" (repo/scrub-whitespace-chars "of\ufefffellow\ufeffsophisticates")))
    (is (= "Turkey Creek Jack Johnson and Texas Jack Vermillion"
          (repo/scrub-whitespace-chars " Turkey\u1680Creek\u2002Jack\u1680Johnson\u2007 and\u2009\u1680Texas Jack\u205f\u205f Vermillion ")))))

(deftest test-integration-of-whitespace-scrubber-with-find-all-tags
  (with-db config
    (testing "integrate whitespace scrubber into add-tag!"
      (let [tags ["this\u180etag", "that\u2006tag", "the\u202fother\ufefftag" "double  space  tag"]
            _ (repo/find-all-tags tags)
            double-space-tag (repo/get-tags-that-start-with "double space tag" 2)
            this-tag (repo/get-tags-that-start-with "this tag" 2)
            that-tag (repo/get-tags-that-start-with "that tag" 2)
            the-other-tag (repo/get-tags-that-start-with "the other tag" 2) ]
      (is (= "double space tag" (first double-space-tag)))
      (is (= "this tag" (first this-tag)))
      (is (= "that tag" (first that-tag)))
      (is (= "the other tag" (first the-other-tag)))
      )))
  (with-db config
    (testing "integrate whitespace scrubber into add-tag! and check for weird errors"
      (let [tags ["     this\u180etag", "that\u2006tag", "the\u202fother\ufefftag" "double  space  tag"]
            _ (repo/find-all-tags tags)
            double-space-tag (repo/get-tags-that-start-with "double space tag" 2)
            this-tag (repo/get-tags-that-start-with "this tag" 2)
            that-tag (repo/get-tags-that-start-with "that tag" 2)
            the-other-tag (repo/get-tags-that-start-with "the other tag" 2) ]
      (is (= "double space tag" (first double-space-tag)))
      (is (= "this tag" (first this-tag)))
      (is (= "that tag" (first that-tag)))
      (is (= "the other tag" (first the-other-tag)))
      )))
  (with-db config
    (testing "duplicate tags ignored"
      (let [tags ["this\u180etag\u180ewith unicode characters" "this\u180etag\u180ewith\u180eunicode\u180echaracters"]
            _ (repo/find-all-tags tags)
            queried-tags (repo/get-tags-that-start-with "this tag with unicode characters" 2)]
            (is (= "this tag with unicode characters" (first queried-tags)))
            (is (= 1 (count queried-tags)))
            ))))

(deftest test-get-deduped-group
  (testing "test deduping group"
    (let [group1 {:name "Another group" :id "123-asd-123-asd" :description "A tasty new group" :sequence_number 1
                 :access [{:entity_type 100 :entity_identifier "sbethi" :access_type 200}]
                 :items ["objectid2" "objectid1" "objectid2" "objectid1" "OBJECTID1"]
                 :tags ["tag1" "tag2" "tag3"]}
          group2 {:name "Another group" :id "123-asd-123-asd" :description "A tasty new group" :sequence_number 1
                  :access [{:entity_type 100 :entity_identifier "sbethi" :access_type 200}]
                  :items ["objectid2" "objectid1"]
                  :tags ["tag1" "tag2" "tag3"]}]
      (is (= (get (repo/get-deduped-group group1) :items) ["objectid2" "objectid1"]))
      (is (= (get (repo/get-deduped-group group2) :items) ["objectid2" "objectid1"])))))