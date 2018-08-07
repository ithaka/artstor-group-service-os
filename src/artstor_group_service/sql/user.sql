
-- name: get-user-institution-id
-- This call is to the Oracle database
select institution_id from user_profile where profileid = :profile_id;


-- name: get-matching-ip-addresses
-- Get all the ip address entries that match the specified class A network
select * from iplookup where ip_address = :ip_address OR ip_address = :class_c OR ip_address = :class_b;


-- name: sql-validate-profile-ids
-- This call is to check if the user-id exists
select count(profileid) as count from user_profile where profileid in (:profile_ids);

-- name: sql-validate-institution-ids
-- This call is to check if the user-id exists
select count(institution_id) as count from institutions where institution_id in (:institution_ids);

