# Advancements / future ideas

## AI reading window as a standalone app / overlay

Idea: generalize the tap-to-translate + AI chat window (currently scoped to this Bible reader)
into its own app or system-wide overlay that works over *any* text the user is reading online —
not just scripture. Ambitious, mechanism not decided yet (Android Accessibility Service overlay?
share-sheet target? browser extension?). Revisit once the Bible app's core AI window is stable.

## Cloud-shared TTS cache: bulk chapter fetch, not per-verse

If we ever build a shared/cloud cache for generated verse audio (instead of today's per-device
local cache), fetching should be per-chapter, not per-verse. Once a chapter's audio is fully
precomputed and sitting in shared storage, there's no "wasted generation" risk like there is with
live on-demand TTS — so pulling the whole chapter's worth of audio files in one request when the
chapter is opened avoids N separate round-trip delays for what's already static, done work.
