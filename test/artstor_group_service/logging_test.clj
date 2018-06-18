(ns artstor-group-service.logging-test
  (require
    [clojure.test :refer :all]
    [artstor-group-service.logging :as log]
   ))

(def a-test-group '{:name " African Art ",
                   :description "A Great description", :sequence_number 3,
                   :id "848313", :public true,
                   :access ({:access_type 300, :entity_identifier "367323", :entity_type 100}
                             {:access_type 100, :entity_identifier "1000", :entity_type 200}
                             {:access_type 200, :entity_identifier "33947", :entity_type 100}
                             {:access_type 200, :entity_identifier "246219", :entity_type 100}
                             {:access_type 200, :entity_identifier "307934", :entity_type 100}),
                   :items ("ARTSTOR_103_41822000266179" "ARTSTOR_103_41822000293496" "ARTSTOR_103_41822000293488" ),
                   :tags ("Africa, 1100 to 1980 CE" "AP® Art History Teaching Resources" )
                   })

(def qa001-user {:profile_id "299277", :institution_ids ["1000"]})
(def test-group-owner {:profile_id "367323", :institution_ids ["1000"]})

  (deftest build-group-event-message-test
    (testing "View Image Group non-owner"
      (let [result (log/build-group-event qa001-user a-test-group "artstor_read_group" true)]
        (is (= "artstor_view_image_group" (result :eventtype)))))
    (testing "View Image Group owner"
      (let [result (log/build-group-event test-group-owner a-test-group "artstor_read_group" true)]
        (is (= "artstor_read_group" (result :eventtype)))))
    (testing "Image Group search"
      (let [result (log/build-search-event "artstor_search_group" {:query "test" :from 0 :size 20 :level :all})]
        (is (= "artstor_search_group"  (result :eventtype))))))

(deftest test-determining-eventtype
  (testing "test determining event type mulitple requests"
    (is (= "artstor_search_group" (log/determine-event-type {:uri "/api/v1/group" :query-string "q=1241245545&tags=art" :request-method :get })))
    (is (= "artstor_create_group" (log/determine-event-type {:uri "/api/v1/group" :request-method :post })))
    (is (= "artstor_delete_group" (log/determine-event-type {:uri "/api/v1/group/1234-56789" :request-method :delete })))
    (is (= "artstor_read_group" (log/determine-event-type {:uri "/api/v1/group/1234-56789" :request-method :get })))
    (is (= "artstor_update_group" (log/determine-event-type {:uri "/api/v1/group/1234-56789" :request-method :put })))
    (is (= "artstor_copy_group" (log/determine-event-type {:uri "/api/v1/group/1234-56789/copy" :request-method :post })))
    (is (= "artstor_get_metadata" (log/determine-event-type {:uri "/api/v1/group/1234-56789/metadata" :request-method :get })))
    (is (= "artstor_get_metadata_legacy_format" (log/determine-event-type {:uri "/api/v1/group/1234-56789/secure/metadata/ART_1234_5679" :request-method :get })))
    (is (= "artstor_get_items_with_GET" (log/determine-event-type {:uri "/api/v1/group/1234-56789/items?object_ids=ART_123&object_ids=ART_456" :request-method :get })))
    (is (= "artstor_admin_update_group_public_flag" (log/determine-event-type {:uri "/api/v1/group/1234-56789/admin/public" :request-method :put })))
    (is (= "artstor_delete_pc_objects_from_groups" (log/determine-event-type {:uri "/api/v1/group/items/delete" :request-method :put })))
    (is (= "artstor_create_group_share_token" (log/determine-event-type {:uri "/api/v1/group/1234-56789/share" :request-method :post })))
    (is (= "artstor_redeem_group_share_token" (log/determine-event-type {:uri "/api/v1/group/1234-56789/redeem/1234566tokentokentoken" :request-method :post })))
    (is (= "artstor_invalidate_group_share_token" (log/determine-event-type {:uri "/api/v1/group/expire/1234566tokentokentoken" :request-method :delete })))
    (is (= "artstor_get_group_tokens" (log/determine-event-type {:uri "/api/v1/group/1234-56789/tokens" :request-method :get })))
    (is (= "artstor_search_group_tags" (log/determine-event-type {:uri "/api/v1/group/tags/suggest?q=1241245545&size=1" :request-method :get })))
    )
  (testing "test not yet used api"
      (is (= "artstor_undefined_group_api_event" (log/determine-event-type {:uri "/api/v1/somenewapi" :request-method :get }))))
    (testing "test dev forgot to define api"
      (is (= "artstor_undefined_group_api_event" (log/determine-event-type {:uri "/api/v1/group/1234-5678/iforgot" :request-method :get }))))
    (testing "test not an api call"
      (is (= :no-op (log/determine-event-type {:uri "/swagger.json" :request-method :get })))))