-- text-channel + voice-channel features: topics, slow mode, and voice user limits

-- A short description shown in the channel header (text channels).
alter table channels add column topic text;

-- Per-channel post cooldown in seconds (0 = off). Admins are exempt.
alter table channels add column slow_mode_seconds int not null default 0;

-- Max concurrent members in a voice channel (0 = unlimited). Admins bypass.
alter table channels add column user_limit int not null default 0;
