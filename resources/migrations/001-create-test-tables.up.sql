-- Table to hold group descriptive information
create table groups (
  id varchar(64) NOT NULL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  sequence_number INTEGER,
  public BOOLEAN DEFAULT FALSE NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  creation_date TIMESTAMPTZ DEFAULT NOW(),
  update_date TIMESTAMPTZ DEFAULT NOW()
);

-- Table to hold items in a group
create table group_items (
  id SERIAL NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  object_id VARCHAR(100) NOT NULL,
  sequence_number INTEGER,
  update_date TIMESTAMPTZ DEFAULT NOW()
);
create index group_items_primary_idx on group_items (group_id, object_id);
-- unique (group_id, object_id)


-- Table to hold sharing constraints on groups
--   entity_type (100 - User, 200 - Institution)
--   entity_identifier - Holds the profile_id in case of user, or the institution_id.
--   access_type (100 - Read, 200 - Write, 300 - Admin??)
create table group_sharing (
  id SERIAL NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  entity_type INTEGER NOT NULL DEFAULT 100,
  entity_identifier VARCHAR(100) NOT NULL,
  access_type INTEGER NOT NULL DEFAULT 100
);
create index group_sharing_lookup_idx on group_sharing (group_id, entity_type, entity_identifier);

