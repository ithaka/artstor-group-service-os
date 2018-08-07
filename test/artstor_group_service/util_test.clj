(ns artstor-group-service.util-test
  (require
    [clojure.test :refer :all]
    [artstor-group-service.util :as util]
    [clojure.test.check :as tc]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.properties :as prop]
    [clojure.test.check.generators :as gen]))


(defspec round-trips-uuids
         100
         (prop/for-all [i gen/uuid]
                       (is (= (str i) (util/decode-uuid (util/encode-uuid i))))))
