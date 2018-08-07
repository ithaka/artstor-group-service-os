-- Table to hold tags in a group
create table tags (
  id SERIAL NOT NULL PRIMARY KEY,
  tag VARCHAR(100) NOT NULL UNIQUE,
  creation_date TIMESTAMPTZ DEFAULT NOW()
);
create index tags_primary_idx on tags (id);
create index tag_idx on tags (tag);

alter table group_tags rename to old_group_tags;
alter index group_tags_primary_idx rename to old_group_tags_primary_idx; 

-- Table to hold tags in a group
create table group_tags (
  id SERIAL NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  tag_id INTEGER NOT NULL REFERENCES tags,
  update_date TIMESTAMPTZ DEFAULT NOW()
);

create index group_tags_primary_idx on group_tags (group_id, tag_id);
