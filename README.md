# ephemeral

A self-hostable **Discord-style chat where everything disappears**. Every
message is physically deleted **7 days** after it's posted (a sliding window);
authors can **save** their own messages to keep them. Text channels, DMs,
voice/video/screen share, file uploads, reactions/replies/pins/search, two
roles (admin/member). Message text and files are **encrypted at rest**. Runs
from a single `docker compose up`.

- [DEPLOY.md](DEPLOY.md) — hosting it for your friends (Tailscale / VPS + TLS / LAN)
- [PLAN.md](PLAN.md) — full design, research, and the verification log
- [docs/UI.md](docs/UI.md) — the liquid-glass UI design notes
- [docs/CODEMAP.md](docs/CODEMAP.md) — grep-oriented index of the whole codebase

## Stack

| | |
|---|---|
| Backend | Java 21 + Spring Boot — REST + WebSocket + scheduled retention purge |
| Database | PostgreSQL 16 (pgvector image; plain SQL via JDBC, Flyway migrations) |
| Voice/video | [LiveKit](https://livekit.io) self-hosted SFU; the app only mints join tokens |
| Frontend | plain HTML/CSS/JS, no build step, LiveKit SDK vendored |
| Privacy | UUIDv7 ids drive retention; AES-256-GCM at rest; TLS/WireGuard in transit |

## Quick start

```bash
cp .env.example .env          # then set JWT_SECRET + ENCRYPTION_KEY (openssl rand -base64 32)
docker compose up --build     # open http://localhost:8080
```

Local dev without Docker (embedded Postgres, JDK 21 only):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Tests — full e2e suite over real HTTP (auth, roles, messaging, DMs, files,
retention, encryption at rest, WebSocket, LiveKit tokens):

```bash
mvn test
```

## How the ephemeral engine works

Message ids are UUIDv7, so creation time is encoded in the id. A scheduled job
deletes by primary-key range — `DELETE FROM messages WHERE id < boundary AND
saved = false` — index-only and cheap, cascading to attachments and their
on-disk blobs (with an orphan-blob reconciliation sweep as backstop). Saving
sets `saved = true` and only the **author** can save their own message — nobody
else can exempt your words from deletion. Deletion is physical, not a soft hide.

## What's encrypted

Message text and uploaded files are AES-256-GCM encrypted before they touch the
database/disk (`ENCRYPTION_KEY`); the search index keeps only word stems
(`SEARCH_INDEX=false` for none). Transit is TLS (Caddy profile) or your
tailnet's WireGuard; WebRTC media is SRTP-encrypted by design. This is not
E2EE — the host can read messages — see [DEPLOY.md](DEPLOY.md) for the honest
threat model.

## Configuration (env vars)

| Var | Default | Meaning |
|---|---|---|
| `JWT_SECRET` | dev value | auth-token signing secret, ≥32 bytes (**set in prod**) |
| `ENCRYPTION_KEY` | derived from `JWT_SECRET` | at-rest key, base64 32 bytes (**set in prod**) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | JDBC connection |
| `RETENTION` / `CLEANUP_INTERVAL` | `7d` / `15m` | message lifetime / purge cadence |
| `LIVEKIT_URL` | `ws://localhost:7880` | LiveKit URL the **browser** connects to |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | dev values | app ↔ LiveKit shared secret |
| `SEARCH_INDEX` | `true` | `false` = keep no search index at all |
| `STORAGE_DIR` | `./data/uploads` | where (encrypted) uploads live |
| `TENOR_KEY` | empty | enables the GIF picker |
| `DOMAIN` | — | tls profile: Caddy fetches certs for this domain |
