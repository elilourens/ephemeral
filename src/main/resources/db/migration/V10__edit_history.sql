-- Edit history: click "(edited)" to see prior versions. Rows cascade with the
-- message, so history obeys the same 7-day vanish (and dies on delete).
-- prev_content is encrypted at rest like messages.content.
create table message_edits (
    id           uuid primary key,                -- uuidv7 = when the EDIT happened
    message_id   uuid not null references messages (id) on delete cascade,
    prev_content text not null
);
create index message_edits_msg_idx on message_edits (message_id);
