# Research: notifications, email, activity presence, desktop app

Question from the operator: how could ephemeral do notifications & email, richer
activity presence, and would any of it need a desktop app?

TL;DR — **no desktop app needed** for notifications. The browser gives us two
tiers (open-tab notifications now, Web Push for closed-tab later); a PWA
manifest makes phones first-class. Email is the weakest-value item for a
friends-instance and the only one needing third-party infrastructure. A desktop
app only becomes necessary for game-activity detection and global push-to-talk.

## 1. Notifications

### Tier 0 — Notification API while a tab is open (an afternoon of work)
The WS client already receives every `message`/mention event live. Call
`Notification.requestPermission()` from a settings toggle, then show a system
notification when `document.hidden` and a mention/DM arrives (we already play a
ping — same trigger). Zero backend changes, works everywhere today, dies when
the tab closes.

### Tier 1 — Web Push (the real answer; already PLAN.md §26 deferred item 7)
Works with the tab **closed**; browser wakes a service worker.

- **Client**: PWA service worker + `PushManager.subscribe()` with a VAPID public
  key → subscription (endpoint on Google/Mozilla/Apple's push service + keys).
- **Server**: store subscriptions per user (new table), send via the Web Push
  protocol when the user has **no live WS session** (RealtimeService already
  knows: `userSessions`). Java: `nl.martijndwars:web-push` + BouncyCastle.
  Payloads are end-to-end encrypted (RFC 8291) — the push relay can't read them.
- **VAPID keys are free** — no accounts with Google/Mozilla/Apple, no FCM
  console, fits self-hosting.
- **Requires HTTPS** (Cloudflare tunnel / Caddy VPS both qualify) and, on iOS
  (16.4+), the PWA must be installed to the home screen. Android is seamless.
- Trigger set: @mentions, DMs, friend requests, server invites, join-request
  (admins). Given the vanish ethos, consider a "You have new messages" generic
  body as a privacy option.

**PWA manifest** (icon, name, `display: standalone`) is a prerequisite for iOS
push and makes ephemeral installable on phones and desktops — dock icon,
standalone window, no app store. The Badging API (Chromium) even puts unread
counts on the installed icon.

## 2. Email

Blunt assessment: lowest value, highest setup, and the app **doesn't collect
email addresses at all today** — it would need an optional, verified email
column plus a verification flow before anything can be sent responsibly.

- **Outbound path**: `spring-boot-starter-mail` → any SMTP relay. Self-hosted
  Postfix is a deliverability trap (SPF/DKIM/DMARC, IP reputation, VPSes block
  port 25). Realistic: a transactional provider — Amazon SES (~$0.10/1k, domain
  verification), Brevo (~300/day free), Resend (100/day free). The Cloudflare
  domain helps: its DNS hosts the SPF/DKIM records; note Cloudflare Email
  Routing is **inbound-only** and can't send.
- **What it's actually for, ranked**: password reset (today a forgotten password
  = account lost — the only strong argument), offline digests ("3 mentions
  while you were away"), invite/friend-request notices. Web Push covers the
  last two better and with less privacy surface.

Recommendation: skip email until password reset matters; then do
SES/Brevo + verified-email column in one wave.

## 3. Activity presence

- **Already shipped**: online/idle/dnd + custom status over WS
  (`presence_update`), shown as dots everywhere including the new Friends tab.
- **Auto-idle** (easy win, client-only): flip to `idle` after N minutes without
  input/visibility, back on activity. No schema, no API changes — the client
  already pushes status. The Chromium-only Idle Detection API is optional
  polish; a simple activity timer is portable.
- **"Playing X" / game detection**: impossible from a browser — it requires
  reading the local process list. That's the one feature that genuinely needs
  native code (this is why Discord's desktop client exists). Options if ever
  wanted: a desktop wrapper (below) or a tiny opt-in native helper that POSTs
  the foreground app name to a presence endpoint.
- **Middle ground with no native code**: "Listening to Spotify" via Spotify's
  Web API (OAuth, server polls currently-playing) — server-side only, works
  from any browser.

## 4. Desktop app — needed?

| Capability | Browser/PWA | Desktop app |
|---|---|---|
| Push notifications (tab closed) | ✅ Web Push (browser running; iOS needs installed PWA) | ✅ |
| Taskbar/dock icon + unread badge | ✅ installed PWA + Badging API | ✅ |
| Autostart / tray | ⚠️ browser-dependent | ✅ |
| Global push-to-talk hotkey (app unfocused) | ❌ browsers forbid global key capture | ✅ |
| Game/process activity detection | ❌ | ✅ |

So: **notifications do not justify a desktop app** — PWA + Web Push covers
desktop and Android fully, iOS acceptably. Build a desktop app only when global
PTT or game activity become must-haves. When that day comes, **Tauri v2** is
the fit: it can wrap the already-served web UI (no build step in this repo, no
bundler), produces a few-MB binary, and its Rust side handles tray, global
shortcuts, autostart and process scanning. Electron works too (it's what
Discord uses) but is ~10× heavier for the same shell.

## Suggested build order

1. Tier-0 notifications toggle (Notification API on mention/DM) — trivial.
2. Auto-idle presence — trivial, client-only.
3. PWA manifest + service worker → installable app.
4. Web Push (VAPID keys in `AppProperties`, subscriptions table, send-on-offline
   from RealtimeService's user-session map) — the substantial one.
5. (If ever) email = password reset via SES/Brevo; (if ever) Tauri wrapper for
   PTT/game activity.
