-- User profile media: custom avatar + profile banner (encrypted uploads, same
-- pattern as server icons) and an optional profile embed URL (rendered only
-- through the provider allowlist client-side).
alter table users add column avatar_id uuid references attachments (id) on delete set null;
alter table users add column banner_id uuid references attachments (id) on delete set null;
alter table users add column profile_embed text;
