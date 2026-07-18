# ephemeral

A self-hostable **Discord clone with disappearing messages**. Every message is
physically deleted **7 days** after it's posted (a sliding window) unless a user
**saves** it. Text channels, voice/video/screen share, file uploads, and two
roles (admin/member). Runs from a single `docker compose up`.

See [PLAN.md](PLAN.md) for the full design and the research behind it.

## Stack

- **Backend** — Java 21 + Spring Boot (REST + WebSocket + a scheduled retention job)
- **Database** — PostgreSQL (+ pgvector available for future semantic search over saved messages)
- **Voice/video/screen share** — [LiveKit](https://livekit.io) (self-hosted SFU); the app only mints access tokens
- **Frontend** — plain HTML/CSS/JS, no build step
- **IDs** — UUIDv7 message ids: the sort key, pagination cursor, and retention boundary in one column

## Run it (production-style, containers)

```bash
docker compose up --build
# open http://localhost:8080
```

Three services come up: `app`, `postgres`, `livekit`. Register a user, create a
server, and start chatting. Messages older than the retention window vanish on
the next purge; click the ⭐ to keep one forever.

To use it from other devices, set `LIVEKIT_URL` to an address the browser can
reach and enable `use_external_ip: true` in `livekit.yaml`:

```bash
LIVEKIT_URL=ws://<your-host-ip>:7880 docker compose up --build
```

## Run it (local dev, no Docker)

Needs a JDK 21 only — Postgres is provided by an **embedded** instance under the
`dev` profile (no install, no Docker):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# open http://localhost:8080
```

## Tests

Full end-to-end suite over real HTTP against an embedded Postgres (auth, roles,
messaging, pagination, files, retention purge, live WebSocket, LiveKit tokens):

```bash
mvn test
```

## Configuration (env vars)

| Var | Default | Meaning |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | JDBC connection |
| `RETENTION` | `7d` | message lifetime (e.g. `24h`, `72h`, `7d`) |
| `CLEANUP_INTERVAL` | `15m` | how often the purge job runs |
| `JWT_SECRET` | dev secret | HMAC secret for app auth tokens (**set in prod**, ≥32 bytes) |
| `LIVEKIT_URL` | `ws://localhost:7880` | LiveKit URL the **browser** connects to |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | dev values | must match `livekit.yaml` |
| `STORAGE_DIR` | `./data/uploads` | where uploaded files live |

## How the ephemeral engine works

Message ids are UUIDv7, so their creation time is encoded in the id. A scheduled
job deletes by primary-key range — `DELETE FROM messages WHERE id < <boundary> AND
saved = false` — which is index-only and cheap, and cascades to attachments (whose
blobs are unlinked too). Saving a message sets `saved = true`, so it falls out of
the purge and lives forever. Deletion is physical, not a soft hide.
