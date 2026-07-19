# Liquid Glass experiment

The main UI restyled per Apple's "Adopting Liquid Glass" guidance
(github.com/macca-llm/iOS-26-Liquid-Glass-Development-Guide-for-Coding-Agents —
a mirror of Apple's adoption doc). All changes live in `lg.css`, loaded on top
of the production stylesheet; `app.js` is an unmodified copy.

Run it side-by-side with the main UI (same DB, different port):

    java -jar target/ephemeral.jar --server.port=8081 \
      --spring.web.resources.static-locations=file:experiments/liquid-glass/

Rules applied: glass only on the topmost functional layer (rail/bars/menus as
floating capsules), near-opaque calm content surface, content scroll-edge fade
beneath the bars, background (clouds) extending under the side panels,
concentric radii, gel-press controls, reduced-transparency/motion fallbacks.

Caveat when comparing on two ports: chat websockets don't fan out across the
two app processes — use one port per realtime test (calls DO work across, as
media flows through the shared LiveKit).
