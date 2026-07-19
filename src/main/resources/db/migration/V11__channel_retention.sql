-- Per-channel vanish timer (Signal-style): overrides the instance default
-- retention window for that channel's messages. Null = instance default.
alter table channels add column retention_ms bigint;
