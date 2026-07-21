---
name: TTS + bookmark implementation
description: How read-aloud (TTS) and verse bookmarking are wired in this app.
---

## TTS engine

`VerseTextToSpeech` (in `data/`) wraps Android's built-in `TextToSpeech`. It is **not** passed to the ViewModel constructor — it is lazily initialised via `viewModel.initTts(context.applicationContext)` in a `LaunchedEffect(Unit)` in `ReaderScreen`. The engine is released in `ReaderViewModel.onCleared()`.

**Why:** Avoids Context leaking into the ViewModel constructor while still allowing the ViewModel to own the lifecycle.

**How to apply:** Any new screen that needs TTS should call `initTts` in a `LaunchedEffect(Unit)` the same way.

## Verse hover icons

Tapping the verse number (the `Text` composable on the far left of each `VerseRow`) calls `onVerseHoverToggle(verseId)`. The ViewModel stores `hoveredVerseId: Long?` in `ReaderUiState` — a second tap on the same verse number sets it to null (toggle off); tapping a different verse number moves it there.

The icons (`VolumeUp` + `Bookmark`/`BookmarkBorder`) fade in via `AnimatedVisibility` at the trailing edge of the `VerseRow`, alongside the `FlowRow` text.

## Bookmark state

`bookmarkedVerseIds: Set<Long>` in `ReaderUiState` — in-memory only, not persisted. Bookmarked rows get `Glass.skyBlue.copy(alpha = 0.15f)` background.

## AI window speaker icons

Three speaker `IconButton`s in `ChatBubble.kt`, all routing to `onSpeakAiText(text, langCode)`:
1. **Header row** — reads `bubble.initialTranslation` in `bubble.bubbleTargetLanguage.code`.
2. **`ChatMessageRow`** — tiny icon in the label row for each ASSISTANT message.
3. **`WordInfoCard`** — icon in the word header row; reads `info.word` in `sourceLanguage.code`.

`ChatBubble` receives `onSpeakAiText: (text, langCode) -> Unit` as a param wired from `ReaderScreen` to `viewModel::onSpeakAiText`.

## VerseList callback threading

`VerseList` is a private sub-composable — `viewModel` is not in scope there. TTS/hover/bookmark callbacks must be passed as `VerseList` parameters and threaded down, the same way `onSelectionStart` etc. are.
