-- emoji reactions, pinned messages, @mentions, and per-channel read state
create table message_reaction (
    message_id uuid not null references messages (id) on delete cascade,
    user_id    uuid not null references users (id) on delete cascade,
    emoji      text not null,
    primary key (message_id, user_id, emoji)
);
create index idx_reaction_message on message_reaction (message_id);

alter table messages add column pinned boolean not null default false;
alter table messages add column pinned_at timestamptz;

create table message_mention (
    message_id uuid not null references messages (id) on delete cascade,
    user_id    uuid not null references users (id) on delete cascade,
    primary key (message_id, user_id)
);

create table read_state (
    user_id      uuid not null references users (id) on delete cascade,
    channel_id   uuid not null references channels (id) on delete cascade,
    last_read_id uuid,
    mention_count int not null default 0,
    primary key (user_id, channel_id)
);
