-- Table to hold tags in a group
drop table tags;
drop index tags_primary_idx;
drop index tag_idx;
drop table group_tags;
drop index group_tags_primary_idx;


alter table old_group_tags rename to group_tags;
alter index old_group_tags_primary_idx rename to group_tags_primary_idx; 
