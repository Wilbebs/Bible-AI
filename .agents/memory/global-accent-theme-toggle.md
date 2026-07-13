---
name: Global accent theme switching without CompositionLocal
description: How the app-wide accent color (used across buttons, glow rings, pills) is switched from a picker UI with no prop-drilling.
---

The accent color theme is held as `var selectedAccentTheme by mutableStateOf(...)` directly on
the `Glass` singleton object (in `ui/theme/Glass.kt`), with `skyBlue`/`deepBlue`/`heavenColors`/
`buttonBrush()` etc. all deriving from it as computed getters rather than fixed vals.

**Why:** Every composable that reads one of those derived properties already does so inside
composition, so it recomposes automatically when the state changes — no `CompositionLocal`,
no passing a theme parameter through every call site. Threading a theme value through
`ChatBubble`, `ReaderScreen`, and every small composable that tints itself would have been a
much larger refactor for the same visible result.

**How to apply:** For any future "global, rarely-changing, app-wide setting" (accent color,
font scale, etc.) that many unrelated composables read, prefer object-level `mutableStateOf`
over prop-drilling or introducing a new CompositionLocal, unless the state needs to be scoped
differently per screen/subtree.
