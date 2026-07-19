-- Direct messages: a DM is a guild-less channel (type 'dm') with a fixed set of
-- participants. It reuses messages, attachments, reactions, read_state and the
-- voice/LiveKit layer (calls happen "in" the DM channel), exactly like a normal
-- channel — only membership + discovery differ.

alter table channels alter column guild_id drop not null;

-- canonical, unique key for a 1:1 DM (sorted "uuidA:uuidB") so opening a DM with
-- the same person is idempotent; null for guild channels.
alter table channels add column dm_key text unique;

create table dm_members (
    channel_id uuid not null references channels (id) on delete cascade,
    user_id    uuid not null references users (id) on delete cascade,
    primary key (channel_id, user_id)
);
create index dm_members_user_idx on dm_members (user_id);
