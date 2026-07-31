# Advancements / future ideas

## AI reading window as a standalone app / overlay

Idea: generalize the tap-to-translate + AI chat window (currently scoped to this Bible reader)
into its own app or system-wide overlay that works over *any* text the user is reading online —
not just scripture. Ambitious, mechanism not decided yet (Android Accessibility Service overlay?
share-sheet target? browser extension?). Revisit once the Bible app's core AI window is stable.

Reading source, if built: prefer Android Accessibility Service text-node extraction as the
primary mechanism (reads an app's actual text, no OCR needed, most accurate) with on-device OCR
(ML Kit Text Recognition — free, instant, offline) as the fallback for apps that render text as
pixels (PDFs, images, custom-drawn readers, a physical book via camera). Deliberately not Cloud
Vision for this — a continuously-capturing overlay doing OCR on every screen would reintroduce
the same per-call network latency we removed from TTS by moving off a cloud/generative pipeline.
Cloud Vision could still make sense for a narrower, occasional case (e.g. "scan this physical
page" as a one-off high-accuracy capture) but not as the engine for continuous overlay reading.

## True mid-speech barge-in for Read Aloud mode

Read Aloud mode (one of partner reading's three modes) currently only listens for a question in
the gap *after* each verse finishes, not while the AI is actively speaking — genuinely
interrupting TTS mid-sentence needs a bidirectional streaming architecture (Gemini's Live API)
that this app's one-shot SpeechRecognizer + non-streaming TTS calls don't support. Revisit this
as part of whatever eventually moves partner reading onto the Live API — see the original
"advanced/uncertain feasibility" note on this from early in the partner-mode work.

## Cloud-shared TTS cache: bulk chapter fetch, not per-verse

If we ever build a shared/cloud cache for generated verse audio (instead of today's per-device
local cache), fetching should be per-chapter, not per-verse. Once a chapter's audio is fully
precomputed and sitting in shared storage, there's no "wasted generation" risk like there is with
live on-demand TTS — so pulling the whole chapter's worth of audio files in one request when the
chapter is opened avoids N separate round-trip delays for what's already static, done work.
