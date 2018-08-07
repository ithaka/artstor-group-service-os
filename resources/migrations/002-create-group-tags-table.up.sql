-- Table to hold tags in a group
create table group_tags (
  id SERIAL NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  tag_id VARCHAR(100) NOT NULL,
  update_date TIMESTAMPTZ DEFAULT NOW()
);
create index group_tags_primary_idx on group_tags (group_id, tag_id);
-- unique (group_id, tag_id)