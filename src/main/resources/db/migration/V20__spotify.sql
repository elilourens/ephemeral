-- Spotify "listening to" presence: one linked account per user. The refresh
-- token is stored app-encrypted (enc:v1:, same scheme as message bodies).
create table spotify_accounts (
    user_id       uuid primary key references users (id) on delete cascade,
    refresh_token text not null
);
