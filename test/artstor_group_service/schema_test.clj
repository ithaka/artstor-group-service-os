(ns artstor-group-service.schema-test
  (:require [clojure.test :refer :all]
            [artstor-group-service.schema :as schema]
            [clojure.spec :as sc]
            [clojure.spec.test :as stest]
            [clojure.string :as string]))


(deftest test-group-schema-specs
  ;;some-old-fashioned-style-of-testing-tests
  (testing "Good Group Name"
    (is (= "Success!\n" (sc/explain-str :artstor-group-service.schema/name "Good Group Name"))))
  (testing "Bad Group Name"
    (is (= "val: \"nm\" fails spec: :artstor-group-service.schema/name predicate: (>= (count %) 3)\n"
           (sc/explain-str :artstor-group-service.schema/name "nm")))
    (is (= "val: nil fails spec: :artstor-group-service.schema/name predicate: string?\n"
           (sc/explain-str :artstor-group-service.schema/name nil))))
  (testing "Good Tags"
    (is (= "Success!\n" (sc/explain-str :artstor-group-service.schema/tags ["M" "e0J1" "3JI" "a3"]))))
  (testing "Bad Tags"
    (is (= "In: [0] val: \"\" fails spec: :artstor-group-service.schema/tags predicate: (>= (count %) 1)\n"
           (sc/explain-str :artstor-group-service.schema/tags ["" "crap"]))))
  (testing "Good Items"
    (is (= "Success!\n" (sc/explain-str :artstor-group-service.schema/items ["M" "e0J1" "3JI" "a3"]))))
  (testing "Bad Items"
    (is (= "In: [0] val: \"\" fails spec: :artstor-group-service.schema/items predicate: (>= (count %) 1)\n"
           (sc/explain-str :artstor-group-service.schema/items ["" "more crap"]))))
  (testing "Too many items"
    (is (string/includes? (sc/explain-str :artstor-group-service.schema/items (map str (range 1001)))
                          "(<= (count %) 1000)")))
  (testing "Too many tags"
    (is (string/includes? (sc/explain-str :artstor-group-service.schema/tags (map str (range 51)))
                          "(<= (count %) 50)")))
  (testing "No Group Errors?"
    (is (= nil (schema/group-errors? {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 100}] }))))
  (testing "Bad Group Name & Tags"
    (is (not (nil? (schema/group-errors? {:name "Go" :tags ["song" ""] :items ["item1"] }))))
    (is (not (nil? (schema/group-errors? {:name "Go" :tags ["song" ""] :items ["item1"] })))))
  (testing "Too many items causes error message"
    (is (not (nil? (schema/group-errors? {:name "Group" :tags ["song"] :items (map str (range 1001))})))))
  (testing "Too many tags cause error message"
    (is (not (nil? (schema/group-errors? {:name "Group" :tags (map str (range 51)) :items ["one"]}))))) )

(deftest test-group-access-specs
  (testing "Institution can read"
    (let [group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 100}] }]
        (is (= nil (schema/group-errors? group)))))
  (testing "Institution cannot write"
    (let [group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 200}] }]
        (is (not (nil? (schema/group-errors? group))))))
  (testing "Institution cannot administer"
    (let [group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 300}] }]
        (is (not (nil? (schema/group-errors? group))))))
  (testing "multiple valid permissions should be allowed"
    (let [group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 100 :entity_identifier "entity 1"}] }
          group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 100 :entity_identifier "entity 2"}] } ]
        (is (= nil (schema/group-errors? group)))))
  (testing "multiple permissions  - one being invalid - should fail"
    (let [group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 100 :entity_identifier "entity 1"}] }
          group {:name "Good Group Name" :tags ["song" "sung"] :items ["item1"] :access [{:entity_type 200 :access_type 200 :entity_identifier "entity 2"}] } ]
        (is (not (nil? (schema/group-errors? group)))))))

