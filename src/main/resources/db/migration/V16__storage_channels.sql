-- Storage channels: a file locker inside a server. Items form a folder tree;
-- files bind an (encrypted) upload. DELIBERATE EXCEPTION to the 7-day vanish:
-- storing files is the channel's whole point, so its attachments are exempt
-- from the orphan purge while referenced (like icons/emoji/avatars).
alter table channels drop constraint channels_type_check;
alter table channels add constraint channels_type_check check (type in ('text', 'voice', 'dm', 'storage'));

create table storage_items (
    id            uuid primary key,                                             -- uuidv7 = created_at
    channel_id    uuid not null references channels (id) on delete cascade,
    parent_id     uuid references storage_items (id) on delete cascade,         -- null = channel root
    owner_id      uuid references users (id) on delete set null,                -- null after account deletion (admin-only delete)
    kind          text not null check (kind in ('folder', 'file')),
    name          text not null,
    attachment_id uuid references attachments (id) on delete cascade            -- files only
);
create index storage_items_tree_idx on storage_items (channel_id, parent_id);
