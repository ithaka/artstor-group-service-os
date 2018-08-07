create table group_tokens (
  token varchar(64) not null PRIMARY KEY,
  access_type INTEGER NOT NULL,
  group_id varchar(64) not null,
  created_by BIGINT not null,
  created_on TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  expires TIMESTAMP WITH TIME ZONE
);

create index group_tokens_primary_idx on group_tokens (token);
create index group_tokens_created_by_idx on group_tokens (created_by);
create index group_tokens_group_id_idx on group_tokens (group_id);
