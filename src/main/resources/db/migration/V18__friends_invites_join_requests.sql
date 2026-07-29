-- Social consent layer: friendships, invite-to-accept, request-to-join.

-- Friends: one row per unordered pair (user_lo < user_hi keeps the pair
-- canonical); requester records who asked, status flips to accepted on consent.
create table friendships (
    user_lo   uuid not null references users (id) on delete cascade,
    user_hi   uuid not null references users (id) on delete cascade,
    requester uuid not null references users (id) on delete cascade,
    status    text not null default 'pending' check (status in ('pending', 'accepted')),
    primary key (user_lo, user_hi),
    check (user_lo < user_hi)
);
create index friendships_hi_idx on friendships (user_hi);

-- Server invites: "Add People" now asks — membership is created only when the
-- invitee accepts. UUIDv7 id carries the creation time.
create table guild_invites (
    id         uuid primary key,
    guild_id   uuid not null references guilds (id) on delete cascade,
    inviter_id uuid references users (id) on delete set null,
    invitee_id uuid not null references users (id) on delete cascade,
    unique (guild_id, invitee_id)
);
create index guild_invites_invitee_idx on guild_invites (invitee_id);

-- Join requests: discovery is browse -> request -> an admin approves.
create table guild_join_requests (
    guild_id uuid not null references guilds (id) on delete cascade,
    user_id  uuid not null references users (id) on delete cascade,
    primary key (guild_id, user_id)
);
create index guild_join_requests_user_idx on guild_join_requests (user_id);
