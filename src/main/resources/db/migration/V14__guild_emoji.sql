-- Per-server custom emoji: an uploaded (encrypted) image with a :name:.
-- Rendered client-side wherever :name: appears; exempt from the orphan-upload
-- purge while referenced (same pattern as server icons).
create table guild_emoji (
    id            uuid primary key,
    guild_id      uuid not null references guilds (id) on delete cascade,
    name          text not null,
    attachment_id uuid not null references attachments (id) on delete cascade,
    unique (guild_id, name)
);
create index guild_emoji_guild_idx on guild_emoji (guild_id);
