-- Perf: cover query patterns whose leading PK column doesn't match the filter.
-- message_mention PK is (message_id, user_id) but the mentions inbox filters by
-- user_id alone -> seq scan; saves PK is (user_id, message_id) but the unsave
-- path (and the message-delete cascade) filters by message_id alone -> seq scan.
create index if not exists message_mention_user_idx on message_mention (user_id);
create index if not exists saves_message_idx on saves (message_id);
