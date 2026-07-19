# Deploying ephemeral

How to put your instance where your friends can reach it. Three options, easiest
first. In every case, start with:

```bash
git clone <this repo> && cd ephemeral
cp .env.example .env
openssl rand -base64 32   # → JWT_SECRET in .env
openssl rand -base64 32   # → ENCRYPTION_KEY in .env  (KEEP A COPY — losing it loses all content)
openssl rand -base64 32   # → LIVEKIT_API_SECRET in .env
```

Hardware: any 1–2 GB RAM box (a $5 VPS, a Raspberry Pi 4/5, an old laptop). The
stack idles around 600 MB (JVM ~350 MB + Postgres + LiveKit).

## Option A — Tailscale (recommended for a friend group)

Zero certificates, zero open ports, everything WireGuard-encrypted in transit.

1. Install [Tailscale](https://tailscale.com) on the host and on each friend's
   device; invite them to your tailnet (Tailscale's free plan covers this, or
   use [Headscale](https://github.com/juanfont/headscale) to self-host that too).
2. In `.env` set `LIVEKIT_URL=ws://<tailscale-ip-or-magicdns-name>:7880`
3. `docker compose up --build -d`
4. Friends open `http://<tailscale-name>:8080`.

Transit encryption comes from WireGuard; nothing is exposed to the internet.

## Option B — public VPS with a domain (Caddy auto-TLS)

1. Point a DNS A record (e.g. `chat.example.com`) at the VPS.
2. In `.env` set `DOMAIN=chat.example.com` and `LIVEKIT_URL=wss://chat.example.com`
   and `LIVEKIT_EXTERNAL_IP=true`.
3. Open ports 80, 443 (TCP), 7881 (TCP) and 50000–50100 (UDP) in the VPS firewall.
4. `docker compose --profile tls up --build -d`

Caddy fetches/renews certificates and fronts the app **and** LiveKit signaling
on one domain (`/rtc` is proxied to LiveKit). Voice/video media flows directly
over the UDP range as SRTP, which WebRTC already encrypts.

## Option C — LAN only

`docker compose up --build -d`, then share `http://<lan-ip>:8080` and set
`LIVEKIT_URL=ws://<lan-ip>:7880` in `.env`. Fine for a house; browsers treat
plain-HTTP origins as insecure, so mic/camera prompts only work on
`localhost` or HTTPS — use option A or B if you want voice off-box.

> **Note:** the production jar excludes the embedded-Postgres binaries (−79 MB).
> Local dev without Docker therefore uses `mvn spring-boot:run
> -Dspring-boot.run.profiles=dev` (embedded PG on the classpath), not
> `java -jar` with the `dev` profile.

## Day-2 operations

- **Update:** `git pull && docker compose up --build -d` (Flyway migrates the DB
  automatically on boot).
- **Back up:** volumes `pgdata` (database) and `uploads` (files), plus your
  `.env`. Content is AES-encrypted at rest with `ENCRYPTION_KEY` — a stolen disk
  or DB dump doesn't reveal messages, but backups are useless without the key.
- **Logs:** `docker compose logs -f app`
- **Health:** `curl localhost:8080/api/health` → `{"status":"ok"}`; compose
  restarts unhealthy containers (`restart: unless-stopped` everywhere).
- **Retention:** `RETENTION=7d` is the disappearing-message window; the purge
  job runs every `CLEANUP_INTERVAL`. Shrink both to taste.

## What's encrypted, exactly

| Layer | Mechanism |
|---|---|
| Message text + uploaded files at rest | AES-256-GCM (app-side, `ENCRYPTION_KEY`) |
| Search index | word stems only, computed pre-encryption; `SEARCH_INDEX=false` for none at all |
| HTTP/WebSocket in transit | TLS via Caddy (option B) or WireGuard (option A) |
| Voice/video/screenshare media | SRTP/DTLS — WebRTC encrypts this by design |
| Passwords | BCrypt hashes |

Not end-to-end encryption: the server (i.e. whoever runs the box) can read
messages while serving them. For a self-hosted friend-group app the trust model
is "we trust the person hosting"; E2EE would break server search and reliable
deletion. What at-rest encryption buys you: a lost disk, a stolen backup, or a
nosy datacenter can't read anything.

## Dogfooding checklist (before inviting friends)

- [ ] `.env` has real `JWT_SECRET`, `ENCRYPTION_KEY`, `LIVEKIT_API_SECRET`, `DB_PASSWORD`
- [ ] `ENCRYPTION_KEY` is backed up somewhere that isn't the server
- [ ] Voice works from a second device (proves LiveKit ports/URL are right)
- [ ] A test message survives `docker compose restart app` (proves the DB volume)
- [ ] Retention: post → wait past `RETENTION` → message is gone
- [ ] First registered account creates the server; you're admin
