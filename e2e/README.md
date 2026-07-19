# Browser e2e suites

Headless-Chromium tests that drive the real UI against a running app.

```bash
# 1. start the app (embedded Postgres, no Docker needed)
java -jar target/ephemeral.jar --spring.profiles.active=dev --server.port=8090

# 2. get playwright once
npm init -y && npm install playwright && npx playwright install chromium-headless-shell

# 3. run a suite (BASE defaults to http://localhost:8090)
node e2e/ui-check.mjs     # auth, chat, markdown, hover toolbar, context menus, no tooltips
node e2e/qol-check.mjs    # day dividers, code copy, ArrowUp-edit, Ctrl+K switcher, privacy tab
node e2e/dm-check.mjs      # two browsers: DM by username, live delivery, unread badge
node e2e/history-check.mjs # edit twice, click (edited), history modal shows versions
node e2e/retention-check.mjs # admin sets a 1-hour vanish timer; pill + toast reflect it
node e2e/groupdm-check.mjs  # 3 browsers: 1:1 -> add person -> NEW group, live msgs, owner kick UI, call ring
node e2e/groupcall-check.mjs # REAL 3-way group call + screenshare (needs livekit-server on :7880)
```

Each suite prints `PASS`/`FAIL` per check and `RESULT n/m`; a non-zero exit
means failure. `OUT=<dir>` saves screenshots. Voice/LiveKit paths log a
connection error unless a `livekit-server` is also running — that's expected
in a chat-only run.

If Chromium refuses to start on a minimal box (missing libnss3 etc.) and you
can't `sudo apt install`, extract the packages locally:
`apt-get download libnspr4 libnss3 libasound2t64 && dpkg-deb -x *.deb ext/`
then run with `LD_LIBRARY_PATH=ext/usr/lib/x86_64-linux-gnu`.
