---
name: Word selection is single-verse by design
description: Decision — selection/translation bubble is scoped to one verse; cross-verse selection was considered and deferred.
---

Word-range selection (and the chat bubble it opens) is deliberately scoped to a single verse. Cross-verse selection ("select up to two verses") was requested once (July 2026) and deferred.

**Why:** the bubble state is keyed to one verse id with one contiguous word range; translation paths, live-translation caching, follow-up context, and the per-verse gesture handlers are all verse-scoped. Spanning verses would require reworking state, caches, prompts, and hit-testing across row boundaries — an invasive change for marginal reading value, since triple-tap already selects a whole verse instantly.

**How to apply:** if cross-verse selection ever becomes a real requirement, treat it as its own project (new selection model keyed by (verseId, index) pairs end-to-end), not a patch on the existing bubble state. Until then, keep new selection features within one verse and don't half-support spanning.
