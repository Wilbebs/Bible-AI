# Bible Translate App

A trilingual (English / Spanish / Portuguese) Bible reader for Android with
tap-to-translate and a scoped AI study-chat bubble. Built as a **light,
functional prototype** — data flow, translation accuracy, and interaction
correctness were the priority; visual design was deliberately left plain
(stock Material 3) because the UI is being handed off to **Replit** for a
real design pass.

This README is written for whoever picks this up next (including a fresh
Claude/AI session or a human dev) so the "why" behind the architecture isn't
lost in translation.

> ⚠️ **Before this goes anywhere near production:** API keys (Gemini + Cloud
> Translation) are currently embedded in the compiled app at build time
> (`local.properties` → `BuildConfig`), with no in-app UI to view/change
> them. That solves "no user-facing key field," but **not** a determined
> extractor — `BuildConfig` values sit in plain text in the APK and are
> trivially recoverable by decompiling it (e.g. with `jadx`), so anyone who
> pulls the APK could lift the key and rack up billed usage on it. Not
> urgent for internal prototyping/testing, but a real backend proxy (server
> holds the keys, app never sees them) is required before any public
> release. Full detail in §6.

---

## 1. What this app does

- Displays the full Bible in three public-domain translations: **KJV**
  (English), **Reina-Valera 1909** (Spanish), **Almeida 1911** (Portuguese).
- Tap or drag across words in the verse text → an instant translation
  popup/bubble appears.
- Tap a verse's "T" button → see that verse's real text in the other two
  languages (a straight DB lookup, always free/instant).
- The translation popup is a **scoped mini-chatbot**: after the initial
  word/verse translation, the user can ask follow-up questions ("why is this
  word translated that way?", "explain this verse", "show Hebrew/Aramaic"),
  entirely scoped to that one verse, with its own short-lived conversation
  history.
- Words inside the AI's replies (and the initial translation itself) are
  themselves tappable — tapping one prefills "What does X mean in this
  verse?" in the follow-up box, and multiple taps accumulate into one
  question ("What do X, Y, Z mean...").
- Tapping a single word (from either the verse text or an AI reply) also
  shows a small **word-info card**: the word bolded/larger, a plain-language
  pronunciation guide, and a real dictionary-style definition — not just a
  translation.
- Everything is scoped per-book to compare **three different translation
  strategies** (see §3) — this was an intentional experiment, not an
  accident of scope creep.

---

## 2. Why this exists / what problem it's solving

Bible apps with real interlinear/word-study tooling exist, but they're
usually locked to modern (often copyrighted) translations and don't offer
lightweight, conversational, multi-language study in one place. The goal
here was: given three independently-translated, public-domain texts that
already share a translation era/philosophy (KJV/RV1909/Almeida1911 are all
Textus-Receptus-based, formal-equivalence translations from roughly the same
period), can tap-to-translate and AI-assisted study be made fast, cheap, and
accurate — and which technical approach (precompute vs. live API, Gemini vs.
a dedicated translate API) actually wins on cost and quality in practice,
not in theory?

---

## 3. The three translation strategies (this is the core architectural decision)

Each of the first three books of the Bible runs a **different** strategy on
purpose, so they could be compared head-to-head with real usage and real
dollars:

| Book | Strategy | How it works | Verdict |
|---|---|---|---|
| **Genesis** | Precomputed (Gemini) | A one-time offline batch script (`scripts/precompute_word_translations.mjs`) called Gemini once per verse per direction (6 directions × ~1,533 verses), with the source verse + its official target-language translation as context, asking for a word-by-word breakdown. Stored in a local SQLite table (`word_translations.db`) bundled with the app. Taps are then instant, offline, $0 marginal cost. | 98.9% coverage, ~$14 total. Best when a book will be read/tapped a lot — cost is paid once regardless of usage. |
| **Exodus** | Live, Cloud Translation API | Every tap makes a live call to Google Cloud Translation API v2, translating just the tapped word(s) **in isolation** (no verse context — that API has no context/alignment feature). | Fast, but isolated-word translation is measurably worse quality than context-aware translation (wrong articles/word choice on ambiguous words), **and** turned out *more* expensive per-call than Gemini once you're sending real volume, because of per-character billing dynamics. This was a genuine finding, not an assumption — see the cost comparison in-session for the numbers. |
| **Leviticus** | Live, Gemini (with verse context) | Every tap makes a live Gemini call, but with the full verse text + its official translation as context, same as the precompute script's prompt just run at tap-time instead of ahead-of-time. | **This is the approach we're standardizing on.** Quality matches the precomputed approach (same prompt strategy), cost per tap is very low (~$0.00005–0.0001), and a local cache (`LiveTranslationCache`) means repeat taps on the same word/verse/direction never re-hit the API. No upfront cost, scales with actual usage instead of book size. |

**Decision for future books:** default to the **Leviticus pattern** (live
Gemini, verse-context, cached) unless there's a specific reason to
precompute a book ahead of time (e.g. guaranteeing offline availability for
a specific book). Genesis and Exodus are being left as-is intentionally —
they're the reference/comparison points, not mistakes to "fix."

---

## 3a. Additional Bible text data (built, not yet wired into the UI)

`scripts/build_bible_dbs.mjs` now fetches 10 texts total (all from the
getbible v2 API, all confirmed license-checked, not just assumed PD). The
original three (`kjv.db`, `rv1909.db`, `almeida1911.db`) are unchanged and
still power the reading/translate-to language pickers as before. The 7 new
`.db` files exist in `assets/bibles/` but **nothing in the Kotlin code
reads them yet** — that wiring (extending the language picker, deciding how
original-language texts interact with tap-translate/chat) is left for
whoever's driving the UI next (Replit), since `BibleLanguage` and the reader
UI are actively being redesigned there and this file didn't want to fight
that.

| File | Text | Language | License |
|---|---|---|---|
| `web.db` | World English Bible | English (modern) | Public Domain |
| `bbe.db` | Bible in Basic English | English (~1000-word vocab) | Public Domain |
| `wlc_hebrew.db` | Westminster Leningrad Codex | Hebrew (OT only) | Public Domain |
| `tischendorf_greek.db` | Tischendorf 8th Ed. | Greek (NT only) | Public Domain |
| `peshitta_aramaic.db` | Peshitta | Aramaic/Syriac (NT only) | Public Domain |
| `vulgate_latin.db` | Clementine Vulgate | Latin (full OT+NT) | Public Domain |
| `biblia_livre_pt.db` | Bíblia Livre | Portuguese (modern) | **CC BY 3.0 Brazil — not PD**, deliberate exception (user-approved), commercial use OK, attribution required |

**Why no simpler Spanish:** every modern easy-Spanish translation found
(TLA, PDT, NVI, etc.) is copyrighted by a Bible society. The only
public-domain Spanish texts available are old/formal register (1858 NT-only,
1569) — no genuine upgrade over RV1909, so none was added.

**⚠️ Data-integrity gotcha for whoever wires this in:** the app's existing
cross-translation logic assumes all Bibles share the same 66-book
`book_id` numbering (Genesis=1 ... Revelation=66) so the same
`(book_id, chapter, verse)` lands on the same verse across languages.
`vulgate_latin.db` has **73 books** (Catholic canon, includes the
Apocrypha/Deuterocanon) — its `book_id`s will diverge from every other
bundled Bible partway through the Old Testament. Don't use Vulgate as a
lookup target in the existing verse-alignment code without either trimming
it to the 66-book subset first or building a separate mapping layer.
`wlc_hebrew.db` (39 books, OT only) and `tischendorf_greek.db` /
`peshitta_aramaic.db` (27 books, NT only) are each internally consistent
subsets of the standard 66 and don't have this problem — they just don't
cover the *other* testament, which is expected (there's no Hebrew NT or
Greek/Aramaic OT to bundle).

---

## 4. Architecture

**Stack:** Kotlin, Jetpack Compose (Material 3), MVVM (`ReaderViewModel` +
`StateFlow`), min SDK 28, no backend server (yet — see §7).

### Data layer (`app/src/main/java/com/logos/bibletranslate/data/`)
- `BibleAssetDatabase.kt` — copies bundled read-only SQLite Bible files
  (`kjv.db`, `rv1909.db`, `almeida1911.db`, `assets/bibles/`) to internal
  storage on first launch (SQLite can't open a DB straight out of an APK),
  then opens them read-only. Raw `SQLiteDatabase`, not Room — Room's
  `createFromAsset` expects its own metadata table that hand-built DBs
  don't have.
- `BibleRepository.kt` — book/chapter listing, chapter batch-load, single
  verse lookup (used by the verse-translate "T" dialog).
- `WordTranslationRepository.kt` — reads the precomputed `word_translations.db`
  table (Genesis), gracefully returns an empty map if that asset doesn't
  exist for a given book (Leviticus/Exodus/anything else un-precomputed).
- `VerseTokenizer.kt` — whitespace tokenizer. **Must stay identical** to the
  tokenization in `scripts/precompute_word_translations.mjs` — word index
  `i` in the precomputed table only means anything if both sides tokenize
  verses the same way.
- `GeminiLiveTranslateClient.kt` — Leviticus's live, context-aware word/phrase
  translation.
- `GoogleTranslateLiveClient.kt` — Exodus's live, word-isolated translation
  via Cloud Translation API v2.
- `LiveTranslationCache.kt` — persisted SQLite cache (own DB, writable) for
  both live-translate clients above, keyed by (verse, direction, word
  range). Prevents re-billing repeat taps.
- `VerseChatClient.kt` — the study-chat bubble's Gemini client: multi-turn
  conversation (verse-scoped system prompt), language auto-detection
  (`sendMessageWithDetection`), and single-word lookup (`fetchWordInfo` —
  pronunciation + real definition).
- `VerseChatCache.kt` — persisted cache for chat replies, keyed by
  (verse, direction, exact conversation-prefix hash, question).
- `VerseChatModels.kt` — `ChatMessage`, `ChatRole`, exchange cap constant.
- `ApiKeys.kt` — reads Gemini/Translate API keys from `BuildConfig`, which
  is generated from `local.properties` (gitignored) at compile time. **No
  in-app UI for keys** — this was an explicit decision (see §6).

### UI layer (`app/src/main/java/com/logos/bibletranslate/ui/reader/`)
- `ReaderScreen.kt` — top bar (book/chapter picker, reading + translate-to
  language toggles), verse list.
- `VerseRow.kt` — renders one verse as tappable word spans. Has two gesture
  modes decided *per-gesture* (not per-recomposition, to avoid breaking a
  drag mid-flight): normal drag-to-select (when no bubble is open on this
  verse) vs. tap-to-toggle-into-question (when a bubble is already open on
  this verse — see the tap-word-autofill behavior below).
- `ChatBubble.kt` — the whole scoped mini-chat UI: resizing (compact →
  wider → internally-scrolling message list past ~70% screen height),
  tappable words in both the initial translation and AI replies, suggested
  chips (cycles through Hebrew/Aramaic → Greek → Latin one at a time
  instead of showing all three at once), the word-info card, the compact
  all-3-language dropdown, Start Over / Close controls.
- `ReaderViewModel.kt` — all the state and orchestration. This file is the
  one to read first to understand real behavior; it's heavily commented
  with *why*, not *what*.
- `CompactLanguageToggle.kt` — shared EN/ES/PT segmented control (used in
  the top app bar; the in-bubble selector uses a dropdown variant of the
  same idea to save width).
- `VerseTranslateDialog.kt` — the per-verse "T" button dialog (plain DB
  lookup, not AI-generated, always free).

### Scripts (`scripts/`, run with Node — not part of the Android build)
- `build_bible_dbs.mjs` — builds the three bundled Bible SQLite files from
  the getbible.net v2 API (one-time, already run — the `.db` files are
  committed as app assets).
- `precompute_word_translations.mjs` — Genesis's one-time Gemini batch
  precompute. Resumable (skips already-`ok` rows), tracks real token cost,
  has a circuit breaker for hard quota errors vs. transient rate limits.
- `precompute_translate_api.mjs` — same idea but via Cloud Translation API;
  written for the Exodus experiment, **not currently run for the full
  book** (Exodus ended up live-only, see §3) — kept for reference/reuse if
  a future book wants the batch-Translate-API path.
- `spot_check_word_translations.mjs` — random-samples precomputed rows for
  manual quality review.

---

## 5. Key non-obvious decisions (read before changing things)

- **Tokenization must match exactly** between `VerseTokenizer.kt` (Kotlin)
  and `tokenize()` in `precompute_word_translations.mjs` (Node). If these
  drift, Genesis's precomputed word lookups silently misalign.
- **Precompute prompt format won considerably over a leaner one.** An
  earlier attempt at a token-cheaper output schema (bare array of strings,
  then `{i, t}` short-key objects) saved ~40% in tokens but measurably hurt
  accuracy (92.7% vs. 99.5% first-pass success) because the model could
  silently drift out of alignment on long/complex verses without an
  anchor. The current schema (`word_index`/`original_word`/`translated_word`,
  full field names) trades tokens for reliability on purpose.
- **Live-call gesture routing is decided once per gesture, not per
  recomposition** (`VerseRow.kt`, `rememberUpdatedState`). This was
  necessary because the moment a translation bubble opens, the *same*
  ongoing drag gesture that opened it would otherwise get mis-routed into
  "add word to question" mode mid-drag.
- **API keys are build-time only, no in-app Settings UI.** Originally built
  a full Settings screen with `EncryptedSharedPreferences`; explicitly
  removed per direction — keys should be inaccessible/uncontrollable by
  whoever runs the installed app. Currently baked in via `local.properties`
  → `BuildConfig` at compile time (still technically extractable from a
  decompiled APK — acceptable for a prototype, **not** for a public release;
  see §7).
- **Genesis/Exodus are not "unfinished features."** Leaving them on their
  original strategies (rather than migrating them to the Leviticus
  pattern) is deliberate — they're the empirical baseline the "use
  live Gemini" decision was based on.

---

## 6. Known limitations / things Replit (or whoever) should know

- **🔑 API key exposure — not urgent, but must fix before production.**
  Keys are embedded in the compiled app via `BuildConfig` (see the warning
  at the top of this file). This is fine for internal testing/prototyping
  — nobody testing the app has a reason to decompile it — but a
  decompiled APK trivially exposes the raw key in plain text, letting
  anyone who pulls the APK make billed API calls on your account. **Before
  any public release**, replace direct Gemini/Translate calls with calls to
  a backend proxy that holds the keys server-side; the app should never
  embed a usable key again. In the meantime, a cheap stopgap is a hard
  spend cap/billing alert on both API projects so a leaked key can't run up
  unlimited cost.
- **No rate limiting / abuse protection** on the live Gemini/Translate
  calls beyond the exchange cap (10 turns/bubble) and the input word cap
  (~150 words). Fine for a prototype, not for public traffic.
- **Visual design is intentionally minimal** — stock Material 3 components,
  no custom theming, no dark-mode pass, no responsive/tablet layout
  consideration. This is the expected Replit starting point, not a bug.
- **Only 3 of 66 books have any translation data** — Genesis (precomputed),
  Exodus/Leviticus (live). Every other book will show "No word-level
  translation yet" on tap until either precomputed or switched to the live
  Gemini pattern.
- **Chat conversations are in-memory only** — closing the bubble or
  switching verses discards the conversation (by design, per the original
  spec — no "saved study notes" feature yet).

---

## 6a. Firebase setup required before login actually works

Login (Google Sign-In + email/password), and everything planned on top of it (profile,
bookmarks/highlights sync, theme preference), needs a one-time manual setup in the Firebase
console before it does anything. **Until this is done, the app skips the login screen entirely
and behaves exactly as it did before this feature existed** — `AuthRepository.isConfigured`
checks whether `google-services.json` was present at build time and the login gate no-ops if
not, so this isn't a hard blocker for anyone working on unrelated parts of the app.

Steps (do these once):

1. Go to the [Firebase console](https://console.firebase.google.com), **Add project**, and
   choose **"bible-translate-502001"** — the same GCP project already used for Gemini/Translate/
   Cloud TTS — rather than creating a new one, so everything stays under one project/billing.
2. Add an Android app to that Firebase project:
   - Package name: `com.logos.bibletranslate`
   - Debug SHA-1: `A0:93:53:E0:BE:F5:09:AB:81:38:D0:E5:E0:5C:04:BA:AB:DE:4C:B5`
   - Debug SHA-256: `4E:32:B2:46:AD:C1:54:B6:41:61:F9:46:8C:89:EE:94:E4:25:D2:D4:E2:A7:1C:62:68:39:76:3A:46:40:35:49`
   - (These are from the shared debug keystore — fine for development; a release build will need
     its own release-keystore fingerprints added the same way before shipping.)
3. Download the generated **`google-services.json`** and place it at `app/google-services.json`
   (same folder as `app/build.gradle.kts`). It's safe to commit — it contains only public
   client identifiers, the same ones that end up inside the compiled APK either way.
4. In the Firebase console, **Authentication → Sign-in method**, enable **Google** and
   **Email/Password**.
5. Rebuild. The `com.google.gms.google-services` Gradle plugin only applies once it detects the
   file (see `app/build.gradle.kts`), so nothing else needs to change.

---

## 7. Suggested next steps

1. Frontend/visual redesign in Replit (the actual point of this handoff).
2. Decide whether to extend the Leviticus (live Gemini) pattern to more
   books, or precompute more books Genesis-style — probably a mix based on
   expected usage per book.
3. Real backend proxy for API keys before any public release.
4. Consider persisting chat conversations ("saved study notes") if that
   becomes a real user ask.
