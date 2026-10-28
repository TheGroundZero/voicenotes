<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Colour themes: design

Status: built and tried on a phone (2026-10-28): the schemes, `Settings.theme`, the contrast tests, the Appearance screen and the wiring; the maintainer's decisions on the open questions are at the end. Written 2026-10-28 on branch `feat/colour-themes`. Roadmap item 4, "for fun,
last". The contrast numbers below were computed (WCAG 2.x, relative luminance), not estimated.

## What exists today

`MainActivity.kt` builds the Material 3 colour scheme from the wallpaper (`dynamicLightColorScheme` or
`dynamicDarkColorScheme`) and picks light or dark from the system. There is no appearance setting, and no colour is written
by hand in the screens: everything reads `MaterialTheme.colorScheme.*` (primary, onSurfaceVariant, error,
errorContainer, secondaryContainer, ...). That is what makes themes cheap: one place builds the scheme.

## Goals and non-goals

- Keep the default exactly as it is now ("Follow the system": wallpaper colours, light or dark).
- Add a handful of fixed schemes the user picks in the app: **Light**, **Dark**, **Paper** (a warm light scheme),
  **High contrast** (one card, follows the system's light or dark), **Matrix** and **Dracula**.
- Every text colour is readable: at least 4.5:1 for normal text and 3:1 for icons and outlines everywhere, 7:1 in High
  contrast. A unit test enforces it, so a later palette tweak cannot silently break readability.
- No new permission, no network, no new library, no change to what is stored per note. The choice is one preference.
- Not goals: user-defined colours, per-screen themes, changing fonts, theming the system's Quick Settings tile, or (for now) making the
  home-screen widget follow the in-app theme.

## The schemes

Each scheme is defined by about eight **seed colours** (background, surface, text, primary, secondary, tertiary, error and a
quiet outline). The code derives the other Material 3 roles (containers, `onXContainer`, `surfaceContainer*`,
`onSurfaceVariant`, `outlineVariant`, ...) by blending seeds toward the background in fixed steps. Hand-picking all
thirty-odd roles per scheme would be error-prone; deriving them also lets the test check the whole scheme.

| Scheme | Mode | Background | Text | Primary | Notes |
|---|---|---|---|---|---|
| System (default) | follows system | wallpaper | wallpaper | wallpaper | today's behaviour, unchanged |
| Light / Dark | fixed | wallpaper colours, but ignoring the system's light/dark switch | | | for people whose system setting flips with the schedule |
| Paper | light | `#F6F1E4` (card `#EBE3D0`) | `#2B2620` (13.3:1; 11.7:1 on the card) | ink blue `#2F4B7C` (7.7:1) | secondary text `#5E5546` (6.5:1), secondary brown `#7A4B1E` (6.5:1), tertiary green `#4B6B3A` (5.4:1), error `#9B1C1C` (7.2:1), outline `#7D7360` (4.1:1; `#8C8270` gives 3.4:1, which is enough, but 4.1:1 reads better) |
| High contrast light | light | `#FFFFFF` | `#000000` (21:1) | `#0033CC` (9.0:1) | secondary text `#3A3A3A` (11.4:1), error `#B00000` (7.4:1) |
| High contrast dark | dark | `#000000` | `#FFFFFF` (21:1) | `#FFD60A` (14.9:1) | secondary text `#D0D0D0` (13.6:1), error `#FF6B6B` (7.6:1) |
| Matrix | dark | `#000000` | `#7CFF9A` (14.9:1 on `#0A140C`) | `#00FF41` (15.4:1) | secondary `#00B82E` (7.9:1), error `#FF4D4D` (6.4:1), container `#003B0F` |
| Dracula | dark | `#282A36` | `#F8F8F2` (13.4:1) | purple `#BD93F9` (5.9:1) | secondary pink `#FF79C6` (6.0:1), tertiary cyan `#8BE9FD` (10.3:1), error `#FF5555` (4.5:1), card `#44475A` |

Adjusted accents (step 1, built): a palette's own colours are only the starting point. An accent is lightened (or darkened) just
far enough to be readable on the background **and** on the palette's card colour, because the app shows primary, secondary,
tertiary and error colours as text on cards (for example "default" labels and error messages). Colours that are readable already
stay exactly as in the table; the others change as follows (code: `ThemeSchemes.kt`, `derive`):

| Scheme | Changed accents (palette colour -> used) |
|---|---|
| Paper, Matrix | none |
| High contrast light | tertiary `#006B2E` -> `#005C28`, error `#B00000` -> `#A20000` |
| High contrast dark | error `#FF6B6B` -> `#FF8686` |
| Dracula | primary `#BD93F9` -> `#C9A7F8`, secondary `#FF79C6` -> `#FD95D0`, error `#FF5555` -> `#FC9D9A` (no real red reaches 4.5:1 on Dracula's card colour `#44475A`; the pale salmon is the price of readable error text there), outline `#6272A4` -> `#8995B8` |

Findings from the numbers:

- **Dracula's own "comment" colour `#6272A4` cannot be used for text**: 3.0:1 on the background and 1.9:1 on the card
  colour. The app's secondary text (`onSurfaceVariant`) is therefore derived by blending the text colour toward the
  background (about 70 % text), not taken from the palette.
- Purple on the card colour is only 3.8:1: fine for icons and focus rings, not for small text. The derivation rule picks
  `onPrimaryContainer` accordingly, and the contrast test would catch a mistake.
- Dracula's red is exactly at the 4.5:1 limit; error text stays at normal weight and size, and the test uses the same limit.
- Matrix is a neon-on-black scheme: bright green on black is very readable, but large areas of it can glare. Large
  surfaces use the dark green container and the near-black `#0A140C`, and the bright green is kept for text, icons and the
  primary button.

Licensing of the palettes: the Dracula repository is MIT-licensed and presents its palette as open source ("Color Palette
(OSS)"); it states no rule about the name, and Dracula PRO is a separate paid product whose colours must not be used. The
app uses only the OSS palette, credits it in About and `third_party/README.md`, and calls the scheme "Dracula" in a
descriptive way. **To confirm before release** (the maintainer's call): that the name "Dracula" in the menu is acceptable;
"Dracula-style" is the fallback. Matrix is a colour idea, not a product. High contrast is ours.

## Choosing a theme (user interface)

- A new menu entry, **Appearance**, between Labels and Triggers (the three-dot menu stays the place for everything that
  is not about notes). It opens a screen with one card per scheme, each showing a tiny sample (a note card, the record
  button and a label chip) drawn in that scheme's colours, so the choice is visible before it is made.
- The selected scheme applies at once (state in `MainActivity`, like the other settings) and is stored in the existing
  `settings` preferences as a short id (`system`, `light`, `dark`, `hc-light`, `hc-dark`, `matrix`, `dracula`).
  An unknown id falls back to `system`.
- High contrast is a single card that follows the system's light or dark (decided); Paper is light and Matrix and Dracula are dark, whatever the system says.

## System bars, widget and other surfaces

- `enableEdgeToEdge()` picks the status and navigation bar icon colours from the *system* theme. With a fixed dark scheme
  on a light system (or the other way round) the icons would be unreadable. The activity has to pass an explicit
  `SystemBarStyle` for the chosen scheme.
- The home-screen widget draws its own backgrounds (`widget_bg_idle`, `widget_bg_recording`). It keeps following the
  system and wallpaper; making widgets follow the in-app theme is possible later but needs the theme in a place
  `RecordWidget` can read without the activity.
- The Quick Settings tile, notifications and Android Auto are drawn by the system or by the car and are not themed.
- Screenshots for the store stay as they are (system theme); one extra screenshot of the Appearance screen is optional.

## Tests and checks

- **Contrast test** (plain JVM unit test, no device): for every scheme, every `onX` on its `X` and on the surfaces they are
  used on is at least 4.5:1 (7:1 in High contrast), and `primary`, `outline` and `error` on the background are at least 3:1.
- A test that every scheme id round-trips and that unknown ids fall back to `system` (built, `ThemeSchemesTest`; I checked that the contrast test fails when the accent adjustment is switched off).
- On a phone (done 2026-10-28): all seven themes through the notes list, Appearance, Models,
  a confirmation dialog and Privacy; large font (1.3) in Dracula and High contrast; landscape in High contrast; the status bar
  icons follow the theme (dark icons on Light and Paper on a phone in dark mode). **Not yet looked at:** the record button while
  recording, the Backup and Labels screens, landscape in the other themes, and the window background during a cold start (it
  may show the system colour for a moment before the first frame).

## Risks

- Material components use their own tints (disabled states, switch tracks, ripple, text-selection handles) that are derived
  from the scheme; a custom scheme can leave one of them hard to see. The phone pass above is for that.
- Fixed schemes ignore the user's wallpaper colours and the system's dark-mode schedule, by design; "System" stays the
  default so nobody gets surprised.
- More schemes mean more to keep readable when screens change: the contrast test is the safeguard.

## Decisions of the maintainer (2026-10-28)

1. The set gets one more scheme: **Paper**, a light scheme (palette above). Solarized and Nord stay possible later, both MIT.
2. **High contrast follows the system** (one card, light or dark).
3. The menu says **"Dracula"**, with the credit to the Dracula theme (MIT) in About and `third_party/README.md`.
4. The **widget does not follow the in-app theme**, at least not now: a low-priority item for later (it needs the theme to be readable
   outside the activity). It stays in the roadmap's list of gaps.

## Steps (each its own pull request)

1. `ThemeSchemes.kt` (seeds, derivation, `Settings.theme`) with the contrast and round-trip tests; no UI yet.
2. The Appearance screen and `MainActivity` wiring, including the system bars.
3. Phone pass, fixes, About credit, screenshots, changelog.
