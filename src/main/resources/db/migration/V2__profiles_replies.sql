-- user profiles + status, and message replies
alter table users add column bio text;
alter table users add column status text not null default 'online' check (status in ('online', 'idle', 'dnd'));
alter table users add column custom_status text;

alter table messages add column reply_to_id uuid references messages (id) on delete set null;
create index idx_messages_reply_to on messages (reply_to_id);
