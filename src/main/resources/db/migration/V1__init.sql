-- ephemeral: core schema
-- Message ids are UUIDv7 (time-ordered), generated in the app. created_at is
-- derived from the id's timestamp bits, so there is no created_at column.

create table users (
    id            uuid primary key,
    username      text not null unique,
    password_hash text not null,
    display_name  text not null
);

create table guilds (
    id       uuid primary key,
    name     text not null,
    owner_id uuid not null references users (id)
);

create table channels (
    id       uuid primary key,
    guild_id uuid not null references guilds (id) on delete cascade,
    name     text not null,
    type     text not null check (type in ('text', 'voice')),
    position int  not null default 0
);

create table memberships (
    guild_id uuid not null references guilds (id) on delete cascade,
    user_id  uuid not null references users (id) on delete cascade,
    role     text not null default 'member' check (role in ('admin', 'member')),
    primary key (guild_id, user_id)
);

create table messages (
    id         uuid primary key,             -- UUIDv7: encodes creation time
    channel_id uuid not null references channels (id) on delete cascade,
    author_id  uuid not null references users (id) on delete cascade,
    content    text not null,
    saved      boolean not null default false, -- denormalized exemption flag
    edited_at  timestamptz
);
-- supports keyset pagination: WHERE channel_id = ? AND id < ? ORDER BY id DESC
create index idx_messages_channel_id on messages (channel_id, id desc);

create table attachments (
    id          uuid primary key,
    message_id  uuid references messages (id) on delete cascade,   -- null until bound to a sent message
    owner_id    uuid not null references users (id) on delete cascade,
    filename    text not null,
    content_type text,
    size_bytes  bigint not null,
    storage_key text not null
);
create index idx_attachments_message_id on attachments (message_id);

create table saves (
    user_id    uuid not null references users (id) on delete cascade,
    message_id uuid not null references messages (id) on delete cascade,
    primary key (user_id, message_id)
);
