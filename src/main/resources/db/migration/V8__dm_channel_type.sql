-- V7 introduced DM channels (type 'dm'), but the original CHECK on channels.type
-- only permitted 'text' / 'voice'. Widen it to include 'dm'.
alter table channels drop constraint channels_type_check;
alter table channels add constraint channels_type_check check (type in ('text', 'voice', 'dm'));
