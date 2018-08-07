-- Ensure this is H2
CALL 5*5;
-- Table to hold group descriptive information
DROP TABLE IF EXISTS groups;
create table groups (
  id varchar(64) NOT NULL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(4096),
  sequence_number INTEGER,
  public BOOLEAN DEFAULT FALSE NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  creation_date TIMESTAMP DEFAULT NOW(),
  update_date TIMESTAMP DEFAULT NOW()
);

-- Table to hold items in a group
DROP TABLE IF EXISTS group_items;
create table group_items (
  id IDENTITY NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  object_id VARCHAR(100) NOT NULL,
  sequence_number INTEGER DEFAULT 0,
  update_date TIMESTAMP DEFAULT NOW()
);
create index group_items_primary_idx on group_items (group_id, object_id);
-- unique (group_id, object_id)


-- Table to hold sharing constraints on groups
--   entity_type (100 - User, 200 - Institution)
--   entity_identifier - Holds the profile_id in case of user, or the institution_id.
--   access_type (100 - Read, 200 - Write, 300 - Admin??)
DROP TABLE IF EXISTS group_sharing;
create table group_sharing (
  id IDENTITY NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  entity_type INTEGER NOT NULL DEFAULT 100,
  entity_identifier VARCHAR(100) NOT NULL,
  access_type INTEGER NOT NULL DEFAULT 100
);
create index group_sharing_lookup_idx on group_sharing (group_id, entity_type, entity_identifier);

DROP TABLE IF EXISTS tags;
create table tags (
  id IDENTITY NOT NULL PRIMARY KEY,
  tag VARCHAR(100) NOT NULL UNIQUE,
  creation_date TIMESTAMP DEFAULT NOW()
);

-- Table to hold tags in a group
DROP TABLE IF EXISTS group_tags;
create table group_tags (
  id IDENTITY NOT NULL PRIMARY KEY,
  group_id VARCHAR(64) NOT NULL,
  tag_id INTEGER NOT NULL,
  update_date TIMESTAMP DEFAULT NOW(),
  FOREIGN KEY (tag_id) REFERENCES tags
);
create index group_tags_primary_idx on group_tags (group_id, tag_id);
-- unique (group_id, tag_id)

-- Table to simulate the user_profile table in Oracle DB
DROP TABLE IF EXISTS user_profile;
create table user_profile (
  profileid INTEGER not null PRIMARY KEY,
  institution_id INTEGER NOT NULL
);

-- Table to simulate the user_profile table in Oracle DB
DROP TABLE IF EXISTS group_tokens;
create table group_tokens (
  token varchar(64) not null PRIMARY KEY,
  access_type INTEGER NOT NULL,
  group_id varchar(64) not null,
  created_by BIGINT not null,
  created_on TIMESTAMP DEFAULT NOW(),
  expires TIMESTAMP
);


-- Table to simulate the institutions table in Oracle DB
DROP TABLE IF EXISTS institutions;
create table institutions (
	institution_id number not null
);

