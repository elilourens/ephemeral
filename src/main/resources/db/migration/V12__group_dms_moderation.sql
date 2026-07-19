-- Group DMs: dm_key stays the 1:1 dedup key (null for groups); groups get an
-- owner (creator) who can remove members, transferred on leave.
alter table channels add column dm_owner_id uuid references users (id) on delete set null;

-- Server bans: kicked-and-can't-return. Checked on join and on member-add.
create table guild_bans (
    guild_id  uuid not null references guilds (id) on delete cascade,
    user_id   uuid not null references users (id) on delete cascade,
    banned_by uuid references users (id) on delete set null,
    reason    text,
    primary key (guild_id, user_id)
);

-- Admin audit log: every moderation action and server change, newest first by
-- uuidv7 id. Swept with the guild (cascade) and by a 30-day retention sweep.
create table audit_log (
    id             uuid primary key,
    guild_id       uuid not null references guilds (id) on delete cascade,
    actor_id       uuid references users (id) on delete set null,
    action         text not null,
    target_user_id uuid,
    detail         text
);
create index audit_log_guild_idx on audit_log (guild_id, id desc);
