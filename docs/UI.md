# ephemeral — UI design notes

The look is **Liquid Glass**: Apple's iOS 26 / macOS Tahoe material (WWDC 2025),
which is glassmorphism *plus* real optical refraction. The chrome (rail, sidebar,
headers, composer, menus, modals) is frosted, refractive glass floating over a
vivid backdrop; the reading surface stays calm and high-contrast.

## Principles (and the vibe-coding traps they avoid)

1. **Glass goes on the chrome, never on long-form text.** Refraction warps only
   the *backdrop behind* an element, so message text (which sits on top) never
   distorts. Big scrolling panels get cheap `blur()`; small floating controls get
   the real `feDisplacementMap` refraction.
2. **Contrast is non-negotiable.** Body text ≥ 4.5:1, secondary ≥ 4.5:1 where it
   carries meaning. On translucent panels we keep a tint floor (`rgba` alpha ≥ .5)
   so text is legible even before the blur lands, plus a faint text-shadow.
3. **One spacing scale.** 4 / 8 / 12 / 16 / 24 / 32 px. Space *around* a group is
   larger than space *within* it, so the eye can parse structure.
4. **Consistent radii & one accent.** Azure `#57a9ff`. Rounded 12–20px on panels.
5. **Motion is subtle and opt-out.** A slow backdrop drift + short 90–150ms
   transitions; everything collapses under `prefers-reduced-motion` and
   `prefers-reduced-transparency`.
6. **Every interactive element has hover + `:focus-visible` + a ≥40px target.**

## The glass recipe

- **Backdrop** (`#glass-bg`): a 5-stop radial mesh, slowly drifting — this is
  what the glass refracts. The default is the **light** palette: soft pastel
  blue / cyan / periwinkle / mint on white, with dark slate text on the frosted
  white panels.
- **Refraction filter** (`#glass-refract` in `index.html`): `feTurbulence`
  (fractal noise) → `feGaussianBlur` → `feDisplacementMap`. Size-independent, so
  it applies to any control without per-element maps.
- **Panels:** `backdrop-filter: blur(20px) saturate(175%)`, tint
  `rgba(20,24,38,.52)`, 1px white-alpha border, inset top highlight (the
  specular sheen), big soft drop shadow.
- **Floating controls:** the above **+ `url(#glass-refract)`** for edge
  refraction. Chromium renders the refraction; Safari/Firefox fall back to
  blur-only automatically.

## Verification loop

Rendered with Playwright (Chromium) at 1440×900 across auth, chat, voice, member
list, modals, and popovers; checked with: zoom to 50% (hierarchy), squint
(what stands out), tab order (focus), contrast sampling. Ship the best result.
