-- Custom server icons: an uploaded (encrypted) attachment referenced from the
-- guild. Uses the normal upload pipeline; the retention purge exempts icons
-- while referenced, and clearing/replacing/deleting self-heals via orphan
-- collection (icon_id -> null keeps the row valid, the sweep takes the blob).
alter table guilds add column icon_id uuid references attachments (id) on delete set null;
