---
    name: Verse identity must be globally unique
    description: Any per-verse UI state (chat bubble, word-translation cache, highlight) must key off VerseData.numericVerseId, never a bare verse number.
    ---

    Once the reader shows a continuous, multi-chapter (or multi-book) scroll, a bare `verse` number (e.g. 3) is no longer unique — "verse 3" exists in every chapter on screen at once. Any state that identifies "which verse is this about" (chat bubble target, word-translation map keys, search highlight, selection range) must key off `VerseData.numericVerseId` (bookId*1_000_000 + chapter*1_000 + verse), not the bare verse number.

    **Why:** Before continuous scroll existed, only one chapter was ever visible, so matching by bare verse number happened to work. Extending the reader to scroll across chapter/book boundaries silently broke that assumption everywhere it was used (bubble-to-verse matching, per-verse word-translation lookups, search highlight) — these are easy to miss because each one still compiles and works fine in the single-chapter case.

    **How to apply:** When adding new per-verse features (e.g. bookmarks, notes, another cache), always identify the verse by `numericVerseId`, never by `verse` alone, even if the feature currently only appears to need a single chapter's context.
    