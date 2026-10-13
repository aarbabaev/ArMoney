# Android design baseline

ArMoney's native Kotlin/Compose UI uses a fixed semantic theme inspired by the
[Mobbin Revolut palette](https://mobbin.com/colors/brand/revolut), consulted on
2026-10-13: Shark `#191C1F`, White `#FFFFFF`, Cornflower Blue `#7F84F6`.
The application keeps ArMoney branding. No Revolut assets, logos, fonts or screen
copies are included, and no external service or UI dependency is introduced.

## Tokens and components

`android/app/src/main/java/com/armoney/android/DesignSystem.kt` owns the palette,
light/dark semantic mappings, typography, spacing, shapes and reusable controls.
The system theme selects light or dark; wallpaper colors do not replace the palette.

| Role | Light | Dark |
| --- | --- | --- |
| Page | `#F5F6F8` | `#101214` |
| Cards / navigation | White | Shark |
| Primary action | Shark / White text | Cornflower / Shark text |
| Accent panel | Cornflower / Shark text | Cornflower / Shark text |
| Supporting text | `#51565F` | `#C1C5CD` |
| Status surface | `#E7E7FF` | `#343650` |
| Error surface | `#FFDADF` | `#650D20` |

Typography uses the platform sans-serif with scalable sp units: 36/44 display,
32/40 and 28/36 headings, 22/30 and 16/24 titles, and 16/24, 14/22, 12/18 body
sizes (font size / line height). Layout uses 8 dp gaps, 16 dp component spacing
and 24 dp page/card padding. Cards have 24 dp corners; primary and secondary
actions use pill shapes, flexible height and at least 48 dp touch targets.
Material components retain focus, pressed, disabled and selection feedback.

## Implemented screen coverage

- Sign-in: accent welcome panel and full-width browser login action. Enrollment
  and credential entry remain in the configured browser identity provider; this
  change does not style the external login/registration pages.
- Wallets: large real AED balance or explicit unavailable text, state badge,
  wallet reference, and wallet creation action.
- Transfers: recipient panel, full-width fields and review action, persistent
  uncertain-transfer warning/retry, status-labelled history and existing detail
  and confirmation dialogs.
- Inbox: notification cards with readable status, amount, timestamp and read/view
  actions; foreground-only delivery guidance remains visible.
- Profile: full-width name editor, phone ownership state, identity and sign-out
  guidance. Read-only phone semantics are retained.

Errors use a semantic error panel and polite accessibility announcement. Statuses
always include text; color alone never communicates success. Unknown balances
remain unavailable, pending payments remain pending, and no preview funds or
sample transactions are added. Decorative navigation glyphs are hidden from
accessibility while labelled navigation items remain exposed. Forms scroll and
respect the software keyboard; controls and text are not fixed to text heights.

## Verification and limits

The JVM contrast regression checks normal-text pairs against 4.5:1 in both themes,
including action, accent, card, status, error and supporting text roles. This is
numerical palette evidence, not a full accessibility audit. APK assembly, existing
JVM financial/session regressions and Android lint are the automated checks.

No emulator, device instrumentation, screenshots, browser sign-in or TalkBack
validation was run for this change. Owner authorization is required to start an
emulator. Visual acceptance remains unverified on small devices, large font
settings, landscape, keyboard transitions and real light/dark system bars. There
is no host Compose screenshot-rendering harness in this module; no mockup is
presented as a rendered application screenshot.

No HTTP, financial, identity, storage, recovery, schema or backend runtime contract
changes. `MainActivity.kt` consumes the existing model and callbacks. Architecture
trust/data-flow diagrams remain accurate; the architecture map may link this
baseline as Android presentation documentation without changing boundaries.
