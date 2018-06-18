(ns artstor-group-service.schema
  (:require [schema.core :as s]
            [clojure.spec :as sc]
            [artstor-group-service.user :as user]))

(def group-access-types
  {:100 "Read" :200 "Write" :300 "Admin"})                  ; intended to be used for error messages

(s/defschema GroupAccess
  {:entity_type (s/enum 100 200)                            ; 100 = User, 200 = Institution
   :entity_identifier s/Str
   :access_type (s/enum 100 200 300)})                      ; 100 = Read, 200 = Write, 300 = Admin

(s/defschema Group
  {:id s/Str
   :name s/Str
   :description (s/maybe s/Str)
   :sequence_number (s/maybe s/Int)
   :access [(s/maybe GroupAccess)]
   :public s/Bool
   :items [(s/maybe s/Str)]
   :tags [(s/maybe s/Str)]
   (s/optional-key :creation_date) s/Any
   (s/optional-key :update_date) s/Any
   })

(s/defschema NewGroup (dissoc Group :id :public ))

(s/defschema GroupSearch (dissoc Group :description))

(s/defschema IndexableGroup
  (assoc Group :insts-with-access [(s/maybe s/Str)]
               :users-with-access [(s/maybe s/Str)]
               :owner [(s/maybe s/Str)]))

(s/defschema RequestStatus
  {:success s/Bool :message s/Str})

(s/defschema NewToken
  {(s/optional-key :access_type) (s/enum 100 200)
   (s/optional-key :expiration_time) s/Inst})

(s/defschema Token
  {:token s/Str
   :access_type s/Int
   :created_on s/Inst
   :expires (s/maybe s/Inst)})


(sc/def ::name (sc/and string? #(>= (count %) 3)))
(sc/def ::tags (sc/and (sc/* string?) (sc/* #(>= (count %) 1)) #(<= (count %) 50)))
(sc/def ::items (sc/and (sc/* string?) (sc/* #(>= (count %) 1)) #(<= (count %) 1000)))
(sc/def ::access  (sc/and (sc/* map?)
                        (sc/* #( if (= (% :entity_type) 200 )
                                      (= (% :access_type) 100)
                                      true))))

;;Not using this yet since it is duplicate of prismatic schema.  May want to re-visit when goto clojure 1.9+
(sc/def ::group (sc/keys :req [::name ::tags ::items]))

(defmulti error-message (fn [problem spec data] problem ))
(defmethod error-message ::access [problem spec data] "Invalid permissions")


(defn group-errors?
  "Validates only fields we want since spec was being too strict"
  [group]
  (or (sc/explain-data ::name (group :name))
      (sc/explain-data ::tags (group :tags))
      (sc/explain-data ::items (group :items))
      (sc/explain-data ::access (group :access))))

(defn group-validator?
  "Validates group semantics and access identifiers"
  [group]
  (or (group-errors? group)
      (user/validate-group-access-info? (get group :access))))

(s/defschema ArtstorUser
  {:profile-id s/Str
   :institution-id s/Str
   :default-user s/Bool
   :username  s/Str})

;;Used for Captain's Log Messages
(sc/def ::eventtype string?)
(sc/def ::event (sc/keys :req [::eventtype]))

(defn has-eventtype?
  "Validates we have an eventtype"
  [event]
  (sc/explain-data ::eventtype (event :eventtype)))
