- [Building Android/Gradle apps on Replit](android-gradle-on-replit.md) — no SDK module exists; manual sdkmanager install + JDK 17 pin needed, no emulator available.
- [Fixing uploaded images with checkerboard baked into pixels](baked-checkerboard-background-removal.md) — detect via `identify` (no alpha channel), fall back to ImageMagick flood-fill when the AI background-removal service fails.

- [Verse identity must be globally unique](verse-identity-uniqueness.md) — key per-verse state by numericVerseId, not bare verse number, once multiple chapters can be on screen.
