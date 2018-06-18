(ns artstor-group-service.search-test
  (:require [clojure.test :refer :all]
            [artstor-group-service.schema :refer :all]
            [cheshire.core :as cheshire]
            [schema.core :as s]
            [clojurewerkz.elastisch.rest.document :as doc]
            [schema.coerce :as coerce]
            [artstor-group-service.search :as search]))

(deftest indexing
  (testing "Calls to index with a valid document pass validation"
    (let [vd {:id "1" :name "A" :description "B" :sequence_number 1 :public true :creation_date "2018-05-09" :update_date "2018-05-09" :access [] :items [] :tags []}]
      (with-redefs [doc/put (fn [_ _ _ id rec] rec  )]
        (is (= vd (search/index vd))))))
  (testing "Calls to index with an empty document fail validation"
    (let [vd {}]
      (with-redefs [doc/put (fn [_ _ _ id rec] rec)]
        (is (thrown-with-msg? RuntimeException #"Value does not match schema" (search/index vd))))))
  (testing "Calls to index with a document with no id fail validation"
    (let [vd {:name "A" :description "B" :sequence_number 1 :access [] :items [] :tags []}]
      (with-redefs [doc/put (fn [_ _ _ id rec] rec)]
        (is (thrown-with-msg? RuntimeException #"Value does not match schema" (search/index vd))))))
  (testing "Calls to index with a document with no name fail validation"
    (let [vd {:id "4" :description "B" :sequence_number 1 :access [] :items [] :tags []}]
      (with-redefs [doc/put (fn [_ _ _ id rec] rec)]
        (is (thrown-with-msg? RuntimeException #"Value does not match schema" (search/index vd)))))))


(deftest deleting
  (testing "Deleting records from index"
    (let [vd {:id "1" :name "A" :description "B" :sequence_number 1 :access [] :items [] :tags []}]
      (with-redefs [doc/delete (fn [_ _ _ id] (is (= (vd :id) id)))]
        (search/delete (vd :id))))))

(deftest searching
  (testing "Simple paging"
    (let [q {:query {:bool {:must {:match_all {}}
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 1 :size 50 :sort [{"name.raw" "asc"}]}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search nil {:from 1 :size 50}))))
  (testing "Simple page size limits"
    (let [q {:query {:bool {:must {:match_all {}}
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 1 :size 100 :sort [{"name.raw" "asc"}]}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search nil {:from 1 :size 5000}))))
  (testing "Simple keyword search"
    (let [q {:query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {}))))
  (testing "Simple keyword search with wildcards"
    (let [q {:query {:bool {:must {:query_string {:query "h?ll*" :default_field "name"}}
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "h?ll*" {}))))
  (testing "Simple keyword search with character escaping"
    (let [q {:query {:bool {:must {:query_string {:query "\\\"\\{\\}\\[\\]\\^\\\\" :default_field "name"}}
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "\"{}[]^\\" {}))))
  (testing "Sort search with no terms"
    (let [q {:query {:bool {:must {:match_all {}}
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24 :sort [{"name.raw" "asc"}]}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search nil {}))))
  (testing "Keyword search with a tag"
    (let [q {:query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :filter [{:term {"tags" "tag"}}]
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {:tags ["tag"]}))))
  (testing "Keyword search with multiple tags"
    (let [q {:query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :filter [{:term {"tags" "tag"}} {:term {"tags" "tag2"}} {:term {"tags" "tag3"}}]
                            :should [{:term {:public true}}]
                            :minimum_should_match 1}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {:tags ["tag" "tag2" "tag3"]}))))
  (testing "Keyword search with multiple tags"
    (let [q {:query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :filter [{:term {:public true}}
                                     {:term {"tags" "tag"}}
                                     {:term {"tags" "tag2"}}
                                     {:term {"tags" "tag3"}}]}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {:tags ["tag" "tag2" "tag3"] :level :public}))))
  (testing "Keyword search with level set to institution with multiple tags"
    (let [q {:query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :filter [{:terms {:insts-with-access nil}}
                                     {:term {"tags" "tag"}}
                                     {:term {"tags" "tag2"}}
                                     {:term {"tags" "tag3"}}]}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {:tags ["tag" "tag2" "tag3"] :level :institution}))))
  (testing "Keyword search with level set to shared with multiple tags"
    (let [q {:_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :filter [{:term {:users-with-access 1234}}
                                     {:term {"tags" "tag"}}
                                     {:term {"tags" "tag2"}}
                                     {:term {"tags" "tag3"}}]
                            :must_not {:term {:owner 1234}}}}
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {:profile-id 1234 :tags ["tag" "tag2" "tag3"] :level :shared}))))
  (testing "Keyword search with level set to private with multiple tags"
    (let [q {:query {:bool {:must {:query_string {:query "hello" :default_field "name"}}
                            :filter [{:term {:owner 1234}}
                                     {:term {"tags" "tag"}}
                                     {:term {"tags" "tag2"}}
                                     {:term {"tags" "tag3"}}]}}
             :_source ["id", "name", "sequence_number", "access", "public", "items", "tags", "creation_date", "update_date"]
             :aggs {:tags {:terms {:field :tags :size 250}}} :from 0 :size 24}]
      (with-redefs [doc/search (fn [_ _ _ search_query]
                                 (is (= q search_query)))]
        (search/search "hello" {:profile-id 1234 :tags ["tag" "tag2" "tag3"] :level :private})))))
