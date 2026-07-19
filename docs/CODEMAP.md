# Code map — where everything lives

Grep-oriented index for humans and AI agents. One line per unit; read the file
only after this table says it's the right one.

## Backend (`src/main/java/com/ephemeral/`) — Spring Boot, plain SQL via NamedParameterJdbcTemplate

| Package / file | Owns |
|---|---|
| `auth/` | JWT login. `AuthController` register/login/me; `AuthService` BCrypt + user rows; `JwtService` HS256 mint/parse; `AuthFilter` bearer filter → `@CurrentUser AuthUser` |
| `config/AppProperties` | every `ephemeral.*` setting (retention, keys, livekit, storage) |
| `config/DevDatabaseInitializer` | `dev` profile: boots zonky embedded Postgres in-process |
| `config/SchedulingConfig` | wires the retention + orphan sweeps onto a scheduler thread |
| `crypto/CryptoService` | AES-256-GCM at rest: `enc:v1:` strings, `EPHC` blob streams, key derivation |
| `dm/` | DMs: guild-less channels + `dm_members`. 1:1 deduped by `dm_key`; group DMs (3–10, owner kicks/transfer, add-to-1:1 spawns a NEW group); calls ride the per-channel LiveKit room; ring via `dm_call` |
| `dto/` | one record per API shape; `MessageDto` is the big one (reactions/reply/attachments/mentions) |
| `file/StorageService` | encrypted blob store on disk, key = attachment id |
| `file/FileController` | `POST /api/uploads` (decoupled from send), `GET /api/files/{id}` (decrypts, sets plaintext length) |
| `gif/GifController` | Tenor proxy so the API key never reaches the browser |
| `guild/GuildService` | guilds, channels, memberships, roles, **all access checks** (`requireChannelMember` handles guild + DM + admin-only) |
| `guild/GuildController` | REST for guilds/channels/members/roles/kick/join/leave |
| `guild/ModerationService` + `ModerationController` | bans (kick + can't rejoin), admin voice mute/deafen/disconnect (WS `voice_force`, honest-client) |
| `guild/AuditService` | per-server admin log (`audit_log`, 30-day sweep), `GET /api/guilds/{id}/audit-log` |
| `health/` | `GET /api/health` |
| `message/MessageService` | send/edit/react/pin/save/delete/list; encrypts content, computes `content_tsv` + `has_link` from plaintext at write time; author-only save |
| `message/RetentionService` | **the product**: purge `id < boundary AND saved=false AND pinned=false`, cascade blob unlink, orphan-blob reconciliation sweep |
| `read/` | per-user `read_state` (last read id + mention counts), `POST /api/channels/{id}/ack` |
| `realtime/ChatWebSocketHandler` | WS auth (`?token=`), inbound ops: subscribe(_guild), typing, voice_state |
| `realtime/RealtimeService` | fan-out: guild events → guild subscribers; DM events → participants' user sessions; presence/voice → all |
| `search/SearchService` | Postgres FTS over `content_tsv`, viewer-scoped, `from:`/`in:`/`has:` filters |
| `unfurl/SafeUrlFetcher` | SSRF guard: re-validates every redirect hop against private/reserved ranges |
| `unfurl/UnfurlService` | OG/twitter-card parse (jsoup), 15-min cache → `GET /api/unfurl?url=` |
| `user/` | profiles (`GET /api/users/{id}`, `PATCH /api/users/me`), presence (WS-derived), settings jsonb, account deletion |
| `util/Ids` | UUIDv7: `newId()`, `timestampOf(id)`, retention `boundary(now - retention)` |
| `voice/` | LiveKit: `LiveKitTokenService` mints HS256 join tokens (role → grants), `VoicePresenceService` who's-in-which-room + mute/deafen/screen state, webhook receiver |
| `web/` | `ApiException` (status + message) + the JSON exception handler |

## Frontend (`src/main/resources/static/`) — no build step

- `index.html` — static shell: auth screen, app grid (rail / sidebar / chat / members), composer, voice view, glass backdrop + SVG refraction filter
- `app.js` — everything, in section order: state → API fetch helpers → WS client → markdown/emoji → rendering (rail/channels/messages/members) → composer (mentions, emoji, voice notes) → DMs → voice (LiveKit) → context menus → modals → search → settings → boot. Grep `function <name>` — it's all top-level functions, no classes.
- `style.css` — design tokens in `:root` (liquid-glass, azure accent), then sections mirroring the DOM: rail, sidebar, messages, composer, voice, menus, modals, search, light-theme overrides at the end
- `vendor/livekit-client.umd.min.js` — vendored LiveKit browser SDK (no CDN)

## Database (`src/main/resources/db/migration/`)

V1 users/guilds/channels/memberships/messages/attachments/saves · V2 profiles+replies
· V3 reactions/pins/read_state/mentions · V4 admin_only + settings jsonb + FTS
· V5 topic/slowmode/user_limit · V6 voice-message metadata · V7 DMs (`dm_members`,
guild-less channels) · V8 `type='dm'` check · V9 encryption at rest (app-computed
`content_tsv`, `has_link`) · V10 edit history · V11 per-channel `retention_ms`
· V12 group DMs (`dm_owner_id`) + `guild_bans` + `audit_log`

## Tests

- `src/test/java` — `EphemeralE2ETest` (30 tests over real HTTP + embedded PG: auth,
  roles, messages, saves, files, retention + per-channel timers, WS, LiveKit
  tokens, encryption, edit history, group DMs, bans, audit log),
  `RetentionScheduledTest`, `SafeUrlFetcherTest`, `UnfurlApiTest`
- `e2e/*.mjs` — headless-Chromium UI suites (see `e2e/README.md`)

## WS event types (server → client)

`ready` `message` `message_updated` `message_deleted` `typing` `voice_presence`
`voice_presence_snapshot` `presence_update` `presence_snapshot` `dm_updated`
`dm_call` (ring) `voice_force` (admin enforcement)
