-- admin-only channels, per-user persisted settings, and message full-text search

-- Channels only admins can see / read / post in.
alter table channels add column admin_only boolean not null default false;

-- Arbitrary per-user client settings (mutes, notification + voice prefs), so
-- they survive restarts and follow the account.
alter table users add column settings jsonb not null default '{}'::jsonb;

-- Full-text search over message content. A STORED generated column stays in
-- sync automatically on insert/update; the GIN index makes @@ queries fast.
alter table messages add column content_tsv tsvector
    generated always as (to_tsvector('english', content)) stored;
create index idx_messages_tsv on messages using gin (content_tsv);
