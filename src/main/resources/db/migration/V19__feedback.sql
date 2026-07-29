-- In-app feedback, readable only by the instance operator. Creation time is
-- encoded in the UUIDv7 id; author survives account deletion as "(deleted)".
create table feedback (
    id      uuid primary key,
    user_id uuid references users (id) on delete set null,
    body    text not null
);
