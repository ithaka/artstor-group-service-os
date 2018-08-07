
-- name: sql-get-group-by-id
-- Gets an image group by id including all the items, tags and sharing data.
select g.id, g.name, g.description, g.sequence_number, g.public, i.id as item_id, i.object_id,
  i.sequence_number as item_seq, g.creation_date, g.update_date from groups as g LEFT OUTER JOIN group_items as i on g.id = i.group_id
  where g.id = :id and g.deleted = false;

-- name: sql-get-group-sharing
-- Get all the sharing information for a group
select s.access_type, s.entity_identifier, s.entity_type
from group_sharing as s where s.group_id = :id;

-- name: sql-get-group-tags
-- Get all the tags associated with a group
select t.tag from group_tags as gt, tags as t where gt.group_id = :id and gt.tag_id = t.id limit 50;

-- name: get-group-sharing-by-id
 -- Used by Authorization.  Gets just the group public flag, sharing and access levels by group id.
select s.group_id, s.access_type, s.entity_identifier, s.entity_type, g.public
from group_sharing as s, groups as g where s.group_id = g.id and s.group_id = :id;

-- name: add-group!
insert into groups (id, name, description, sequence_number) values
  (:id, :name, :description, :sequence_number);

-- name: group-exists
select id from groups where id = :id;

--name: update-group!
update groups set name = :name, description = :description, sequence_number = :sequence_number, update_date = now() where id = :id;

--name: update-group-public-flag!
update groups set public = :public where id = :id;

-- name: remove-group!
update groups set deleted = true, update_date = now() where id = :id;

-- name: add-items!
insert into group_items (group_id, object_id, sequence_number) values (:group_id, :object_id, :sequence_number);

-- name: remove-all-items!
delete from group_items where group_id = :group_id;

--name: update-item-order
update group_items set sequence_number = :sequence_number where group_id = :group_id and object_id = :object_id;

-- name: add-group-sharing-info!
insert into group_sharing (group_id, entity_type, entity_identifier, access_type) values
  (:group_id, :entity_type, :entity_identifier, :access_type);

-- name: remove-group-sharing-info!
delete from group_sharing where group_id = :group_id;

-- name: remove-group-sharing-for-user!
delete from group_sharing where group_id = :group_id and entity_identifier = :entity_identifier;

-- name: get-group-ignore-deletion-status
-- Gets a group regardless of deletion status
select * from groups where id = :id;

-- name: add-tag!
-- Adds a tag if it doesn't exist already and returns a tag id either way
insert into tags (tag) select :tag where not exists (select * from tags where tag = :tag);

-- name: get-tag-ids-by-tag
-- Return all matching tags
select id from tags where tag in (:tags);

-- name: associate-tags!
insert into group_tags (group_id, tag_id) values (:group_id, :tag_id);

-- name: clear-tags!
delete from group_tags where group_id = :group_id;

-- name: get-all-group-ids
-- Return all active group ids
select id from groups where groups.deleted = 'f';

-- name: get-updated-group-ids
-- Return all active group ids update since the supplied date
select id from groups where groups.deleted = 'f' and update_date >= :updated_date;

-- name: get-token-info
-- Gets a token given it's id
select * from group_tokens where token = :token and (expires > now() OR expires is NULL);

-- name: create-token!
-- Stores a newly created token to the database
insert into group_tokens (token, group_id, access_type, created_by, expires) values
(:token, :group_id, :access_type, :created_by, :expires);

-- name: expire-token!
-- Expires a token by setting it's expiration date to 2000-01-01
update group_tokens set expires = '2000-01-01' where token = :token and created_by = :created_by

-- name: get-users-tokens
-- Gets all the tokens for a group/user combination
select token, created_on, access_type, expires from group_tokens where group_id = :group_id and
created_by = :created_by and (expires > now() OR expires is NULL);

-- name: find-groups-with-zoomed-images
-- Gets all the group ids for groups containing zoomed images
select DISTINCT group_id from groups, group_items where groups.id = group_items.group_id and groups.deleted = 'f' and object_id like '%VIR%';

-- name: get-existing-tags
-- Gets all pre-existing tags that begin with the passed-in string
select tag from tags where lower(tag) LIKE :tag limit :limit;

-- name: sql-get-group-admin
select * from group_sharing where entity_type = 100 and access_type = 300 and group_id = :group_id;

--name: sql-get-groups-of-given-object-id
select object_id, group_id from group_items where object_id in (:object_ids);

--name: delete-objects-from-groups!
delete from group_items where object_id in (:object_ids);