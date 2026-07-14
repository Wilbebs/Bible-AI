- [Building Android/Gradle apps on Replit](android-gradle-on-replit.md) — no SDK module exists; manual sdkmanager install + JDK 17 pin needed, no emulator available.
- [Fixing uploaded images with checkerboard baked into pixels](baked-checkerboard-background-removal.md) — detect via `identify` (no alpha channel), fall back to ImageMagick flood-fill when the AI background-removal service fails.

- [Verse identity must be globally unique](verse-identity-uniqueness.md) — key per-verse state by numericVerseId, not bare verse number, once multiple chapters can be on screen.
- [Global accent theme toggle](global-accent-theme-toggle.md) — app-wide accent color lives as object-level mutableStateOf on Glass, not CompositionLocal/prop-drilling.
- [Compose TextField ↔ ViewModel sync](compose-textfield-viewmodel-sync.md) — local TextFieldValue + sequence-marked programmatic writes; never guard onValueChange against the VM snapshot.
- [Word selection is single-verse by design](verse-selection-scope.md) — cross-verse selection deferred; triple-tap covers whole-verse; treat spanning as its own project.
