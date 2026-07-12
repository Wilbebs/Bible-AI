package com.logos.bibletranslate.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.logos.bibletranslate.data.ApiKeys
import com.logos.bibletranslate.data.BibleLanguage
import com.logos.bibletranslate.data.BibleRepository
import com.logos.bibletranslate.data.BookInfo
import com.logos.bibletranslate.data.ChatMessage
import com.logos.bibletranslate.data.ChatRole
import com.logos.bibletranslate.data.GeminiLiveTranslateClient
import com.logos.bibletranslate.data.GoogleTranslateLiveClient
import com.logos.bibletranslate.data.LiveTranslationCache
import com.logos.bibletranslate.data.MAX_CHAT_EXCHANGES
import com.logos.bibletranslate.data.VerseChatCache
import com.logos.bibletranslate.data.VerseChatClient
import com.logos.bibletranslate.data.VerseData
import com.logos.bibletranslate.data.VerseTokenizer
import com.logos.bibletranslate.data.WordTranslation
import com.logos.bibletranslate.data.WordTranslationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live-call experiment books, both non-preprocessed, run entirely at tap
 * time with no precomputed data: Exodus (Cloud Translation API,
 * word-isolated, no verse context) and Leviticus (Gemini, with verse
 * context). Genesis is the precomputed comparison point.
 */
private const val EXODUS_BOOK_ID = 2
private const val LEVITICUS_BOOK_ID = 3

/** Last Old Testament book_id in the bundled KJV ordering (Malachi); used to pick the Hebrew vs. Greek chip. */
private const val LAST_OT_BOOK_ID = 39

/** ~200 tokens per the addendum's follow-up input cap, approximated as a word count. */
private const val MAX_FOLLOWUP_WORDS = 150

/** Single-word focus card: pronunciation + a real dictionary definition, not just the translation. */
data class WordInfoState(
    val word: String,
    val pronunciation: String? = null,
    val definition: String? = null,
    val isLoading: Boolean = true,
)

/** A tap/drag word selection, now a scoped mini-chat bubble (chat-feature-addendum). */
data class ChatBubbleState(
    val verseNumber: Int,
    val wordRange: IntRange,
    val bubbleTargetLanguage: BibleLanguage,
    val initialTranslation: String,
    val initialHasData: Boolean,
    val initialIsLoading: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val suggestedChips: List<String> = emptyList(),
    val followUpInput: String = "",
    val isSendingFollowUp: Boolean = false,
    val exchangeCount: Int = 0,
    val atCap: Boolean = false,
    /** Words tapped-to-queue for the autofilled question, in tap order (tap-word-autofill-idea.md). */
    val queuedWordIndices: List<Int> = emptyList(),
    /** Words tapped in the initial translation / assistant replies, in tap order (same idea, applied to generated text). */
    val queuedResponseWords: List<String> = emptyList(),
    /** Set when the user just manually tapped the toggle — honored for the next reply, then cleared (auto-detect-language-idea.md). */
    val manualLanguageOverride: Boolean = false,
    /** How far through the Hebrew/Aramaic → Greek → Latin cycle we are — one shown at a time. */
    val originalLanguageIndex: Int = 0,
    /** Populated whenever exactly one word is in focus, from either the verse text or the AI's reply. */
    val wordInfo: WordInfoState? = null,
    /**
     * Set when the user taps outside the panel — collapses it to just the word/verse
     * translation plus the header controls, without discarding the conversation
     * (unlike "Delete chat", which clears history). Tapping the collapsed panel again
     * expands it back.
     */
    val isMinimized: Boolean = false,
)

data class VerseTranslationEntry(val language: BibleLanguage, val text: String?)

data class VerseDialogData(
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val sourceLanguage: BibleLanguage,
    val originalText: String,
    val translations: List<VerseTranslationEntry>,
)

data class ReaderUiState(
    val language: BibleLanguage = BibleLanguage.EN,
    val targetLanguage: BibleLanguage = BibleLanguage.ES,
    val books: List<BookInfo> = emptyList(),
    val selectedBookId: Int = 1,
    val selectedBookName: String = "",
    val chapterCount: Int = 1,
    val selectedChapter: Int = 1,
    val verses: List<VerseData> = emptyList(),
    val wordTranslations: Map<Int, List<WordTranslation>> = emptyMap(),
    val isLoading: Boolean = true,
    val chatBubble: ChatBubbleState? = null,
    val verseDialog: VerseDialogData? = null,
    /** Set by an exact verse search — drives the scroll-to + slow pulsing highlight, and self-clears after a while. */
    val highlightedVerse: Int? = null,
)

class ReaderViewModel(
    private val repository: BibleRepository,
    private val wordTranslationRepository: WordTranslationRepository,
    private val liveTranslateClient: GeminiLiveTranslateClient,
    private val googleTranslateClient: GoogleTranslateLiveClient,
    private val liveTranslationCache: LiveTranslationCache,
    private val verseChatClient: VerseChatClient,
    private val verseChatCache: VerseChatCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** Guards against a stale live-call result overwriting a newer selection. */
    private var liveCallRequestId = 0

    /** Separate counter for the word-info lookup, since it runs independently of translation/chat calls. */
    private var wordInfoRequestId = 0

    init {
        viewModelScope.launch {
            loadBooksAndChapter(BibleLanguage.EN, bookId = 1, chapter = 1, targetLanguage = BibleLanguage.ES)
        }
    }

    fun onLanguageSelected(language: BibleLanguage) {
        val state = _uiState.value
        val newTarget = if (state.targetLanguage == language) defaultTargetFor(language) else state.targetLanguage
        viewModelScope.launch {
            loadBooksAndChapter(language, bookId = state.selectedBookId, chapter = state.selectedChapter, targetLanguage = newTarget)
        }
    }

    fun onTargetLanguageSelected(language: BibleLanguage) {
        val state = _uiState.value
        if (language == state.language) return
        _uiState.value = state.copy(targetLanguage = language, chatBubble = null)
        viewModelScope.launch {
            reloadWordTranslations()
        }
    }

    /** Fluid one-dialog book→chapter navigation — works whether [bookId] is the current book or a different one. */
    fun onBookAndChapterSelected(bookId: Int, chapter: Int) {
        val state = _uiState.value
        val book = state.books.firstOrNull { it.bookId == bookId } ?: return
        viewModelScope.launch {
            loadChapter(state.language, book.bookId, book.bookName, state.books, chapter, state.targetLanguage)
        }
    }

    /** Read-only chapter-count lookup for a book that may not be the currently loaded one — powers the picker's chapter grid. */
    suspend fun chapterCountFor(bookId: Int): Int =
        repository.getChapterCount(_uiState.value.language, bookId).coerceAtLeast(1)

    /**
     * Exact verse search (e.g. "John 3:16") — resolves the book by exact or prefix match
     * against the currently loaded language's book list, navigates there if needed, and marks
     * the verse for the scroll-to + pulsing highlight treatment. Silently no-ops on a query
     * that doesn't parse or doesn't match a known book, since this is a live-as-you-type search
     * box, not a form with its own error state.
     */
    fun onVerseSearchSubmitted(query: String) {
        val state = _uiState.value
        val (book, chapter, verseNumber) = parseVerseQuery(query, state.books) ?: return
        viewModelScope.launch {
            if (book.bookId == state.selectedBookId && chapter == state.selectedChapter) {
                _uiState.value = _uiState.value.copy(highlightedVerse = verseNumber)
            } else {
                loadChapter(state.language, book.bookId, book.bookName, state.books, chapter, state.targetLanguage, highlightedVerse = verseNumber)
            }
        }
    }

    /** Self-clear for the search highlight — called by the UI after the pulse has had time to draw the eye. */
    fun clearHighlightedVerse() {
        if (_uiState.value.highlightedVerse == null) return
        _uiState.value = _uiState.value.copy(highlightedVerse = null)
    }

    /** Tap-down or drag-start on a word (§5). */
    fun onSelectionStart(verseNumber: Int, wordIndex: Int) {
        updateSelection(verseNumber, wordIndex..wordIndex)
    }

    /** Drag extending the selection; live-updates the bubble (§5). */
    fun onSelectionExtend(verseNumber: Int, wordIndex: Int) {
        val current = _uiState.value.chatBubble ?: return
        if (current.verseNumber != verseNumber) return
        val start = current.wordRange.first
        val newRange = if (wordIndex >= start) start..wordIndex else wordIndex..start
        updateSelection(verseNumber, newRange)
    }

    /**
     * Tap on a word while the bubble is already open on this same verse — toggles it into/out of
     * the pending autofilled question instead of starting a new translation selection
     * (tap-word-autofill-idea.md). Taps on a different verse fall through to onSelectionStart
     * and open a fresh bubble there instead.
     */
    fun onVerseWordTapped(verseNumber: Int, wordIndex: Int) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (bubble.verseNumber != verseNumber) return
        val verse = state.verses.firstOrNull { it.verse == verseNumber } ?: return
        val tokens = VerseTokenizer.tokenize(verse.text)
        if (wordIndex !in tokens.indices) return

        val newQueue = if (wordIndex in bubble.queuedWordIndices) {
            bubble.queuedWordIndices - wordIndex
        } else {
            bubble.queuedWordIndices + wordIndex
        }
        val questionText = buildAutofillQuestion(newQueue.map { tokens[it] })
        val updatedBubble = bubble.copy(queuedWordIndices = newQueue, followUpInput = questionText)
        if (newQueue.size == 1) {
            val word = tokens[newQueue.first()]
            // Show the loading state the moment the word is tapped, before the network
            // call resolves, so a definition visibly starts generating right away.
            _uiState.value = state.copy(chatBubble = updatedBubble.copy(wordInfo = WordInfoState(word, isLoading = true)))
            fetchWordInfoAsync(verse, word, state.language)
        } else {
            _uiState.value = state.copy(chatBubble = clearWordInfo(updatedBubble))
        }
    }

    private fun buildAutofillQuestion(words: List<String>): String {
        if (words.isEmpty()) return ""
        val joined = words.joinToString(", ")
        return if (words.size == 1) "What does $joined mean in this verse?" else "What do $joined mean in this verse?"
    }

    /**
     * Tap on a word inside the assistant's generated reply — prefills a question about it and
     * snaps the bubble back to the global "language to be learned" (state.targetLanguage),
     * overriding any auto-detect drift, since the point is practicing/understanding that
     * language specifically.
     */
    fun onResponseWordTapped(word: String) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        val cleaned = word.trim { it.isWhitespace() || it in ",.;:!?\"'“”¡¿()" }
        if (cleaned.isEmpty()) return

        val newQueue = if (cleaned in bubble.queuedResponseWords) {
            bubble.queuedResponseWords - cleaned
        } else {
            bubble.queuedResponseWords + cleaned
        }
        val updatedBubble = bubble.copy(
            queuedResponseWords = newQueue,
            followUpInput = buildAutofillQuestion(newQueue),
            bubbleTargetLanguage = state.targetLanguage,
            manualLanguageOverride = true,
        )
        if (newQueue.size == 1) {
            val verse = state.verses.firstOrNull { it.verse == bubble.verseNumber }
            if (verse != null) {
                // Same immediate-loading treatment as a verse-text tap (§3).
                _uiState.value = state.copy(chatBubble = updatedBubble.copy(wordInfo = WordInfoState(newQueue.first(), isLoading = true)))
                fetchWordInfoAsync(verse, newQueue.first(), state.targetLanguage)
            } else {
                _uiState.value = state.copy(chatBubble = updatedBubble)
            }
        } else {
            _uiState.value = state.copy(chatBubble = clearWordInfo(updatedBubble))
        }
    }

    /** Explicit "X" — fully closes the bubble (addendum §6). */
    fun onCloseBubble() {
        liveCallRequestId++ // invalidate any in-flight live call
        _uiState.value = _uiState.value.copy(chatBubble = null)
    }

    /** "Delete chat" — clears the conversation but keeps the bubble open on the same verse (addendum §6). */
    fun onStartOver() {
        val bubble = _uiState.value.chatBubble ?: return
        _uiState.value = _uiState.value.copy(
            chatBubble = clearWordInfo(
                bubble.copy(
                    messages = emptyList(),
                    suggestedChips = emptyList(),
                    exchangeCount = 0,
                    atCap = false,
                    followUpInput = "",
                    manualLanguageOverride = false,
                    queuedWordIndices = emptyList(),
                    queuedResponseWords = emptyList(),
                ),
            ),
        )
    }

    /**
     * Tapping anywhere outside the panel collapses it to just the translation/header
     * (visual only — conversation history is preserved, unlike [onStartOver]).
     */
    fun onBubbleOutsideTap() {
        val bubble = _uiState.value.chatBubble ?: return
        if (bubble.isMinimized) return
        _uiState.value = _uiState.value.copy(chatBubble = bubble.copy(isMinimized = true))
    }

    /** Tapping the collapsed panel expands it back to the full conversation view. */
    fun onExpandBubble() {
        val bubble = _uiState.value.chatBubble ?: return
        if (!bubble.isMinimized) return
        _uiState.value = _uiState.value.copy(chatBubble = bubble.copy(isMinimized = false))
    }

    fun onFollowUpInputChanged(text: String) {
        val bubble = _uiState.value.chatBubble ?: return
        _uiState.value = _uiState.value.copy(chatBubble = bubble.copy(followUpInput = text))
    }

    fun onSendFollowUp() {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (bubble.atCap || bubble.isSendingFollowUp) return
        val question = bubble.followUpInput.trim()
        if (question.isEmpty()) return
        sendFollowUp(bubble, state, question)
    }

    fun onChipTapped(chip: String) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (bubble.atCap || bubble.isSendingFollowUp) return
        val isOriginalLanguageChip = chip in originalLanguageSequence(state.selectedBookId)
        sendFollowUp(bubble, state, chip, advanceOriginalLanguage = isOriginalLanguageChip)
    }

    /** In-bubble language toggle — re-runs the initial translation, keeps conversation history (addendum §5). */
    fun onBubbleLanguageChanged(language: BibleLanguage) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (language == bubble.bubbleTargetLanguage) return
        val verse = state.verses.firstOrNull { it.verse == bubble.verseNumber } ?: return

        // Picking the reading language itself: no translation needed, just show the original
        // text and let follow-ups happen in that language (all 3 languages are now selectable
        // in the bubble, not just the two non-reading ones).
        if (language == state.language) {
            val tokens = VerseTokenizer.tokenize(verse.text)
            val selectedText = bubble.wordRange.joinToString(" ") { tokens[it] }
            _uiState.value = state.copy(
                chatBubble = bubble.copy(
                    bubbleTargetLanguage = language,
                    initialTranslation = selectedText,
                    initialHasData = true,
                    initialIsLoading = false,
                    manualLanguageOverride = true,
                ),
            )
            return
        }

        val apiKey = ApiKeys.geminiApiKey
        val myRequestId = ++liveCallRequestId
        _uiState.value = state.copy(
            chatBubble = bubble.copy(
                bubbleTargetLanguage = language,
                initialIsLoading = true,
                initialTranslation = "Translating…",
                manualLanguageOverride = true,
            ),
        )
        if (apiKey == null) {
            _uiState.value = _uiState.value.copy(
                chatBubble = _uiState.value.chatBubble?.copy(
                    initialTranslation = "No Gemini API key configured for this build.",
                    initialIsLoading = false,
                    initialHasData = false,
                ),
            )
            return
        }
        val tokens = VerseTokenizer.tokenize(verse.text)
        val selectedText = bubble.wordRange.joinToString(" ") { tokens[it] }
        viewModelScope.launch {
            val targetVerse = repository.getVerse(language, verse.bookId, verse.chapter, verse.verse)
            val result = liveTranslateClient.translateSelection(
                apiKey = apiKey,
                sourceLangName = state.language.displayName,
                targetLangName = language.displayName,
                sourceVerseText = verse.text,
                targetVerseText = targetVerse?.text.orEmpty(),
                selectedText = selectedText,
            )
            if (myRequestId != liveCallRequestId) return@launch
            val current = _uiState.value.chatBubble ?: return@launch
            _uiState.value = _uiState.value.copy(
                chatBubble = result.fold(
                    onSuccess = { text -> current.copy(initialTranslation = text, initialHasData = true, initialIsLoading = false) },
                    onFailure = { err -> current.copy(initialTranslation = "Live translate failed: ${err.message}", initialIsLoading = false) },
                ),
            )
        }
    }

    private fun sendFollowUp(bubble: ChatBubbleState, state: ReaderUiState, rawQuestion: String, advanceOriginalLanguage: Boolean = false) {
        val verse = state.verses.firstOrNull { it.verse == bubble.verseNumber } ?: return
        val question = capWords(rawQuestion, MAX_FOLLOWUP_WORDS)
        val historyBeforeThisTurn = bubble.messages
        val messagesWithQuestion = historyBeforeThisTurn + ChatMessage(ChatRole.USER, question)

        _uiState.value = state.copy(
            chatBubble = clearWordInfo(
                bubble.copy(
                    messages = messagesWithQuestion, followUpInput = "", isSendingFollowUp = true,
                    queuedWordIndices = emptyList(), queuedResponseWords = emptyList(),
                ),
            ),
        )

        val apiKey = ApiKeys.geminiApiKey
        if (apiKey == null) {
            _uiState.value = _uiState.value.copy(
                chatBubble = _uiState.value.chatBubble?.copy(
                    messages = messagesWithQuestion + ChatMessage(ChatRole.ASSISTANT, "No Gemini API key configured for this build."),
                    isSendingFollowUp = false,
                ),
            )
            return
        }

        // Very short/ambiguous messages don't trigger a language switch even without a manual
        // override (auto-detect-language-idea.md guardrail); the model applies the same rule
        // server-side, this is just a cheap client-side floor.
        val wordCount = question.split(Regex("\\s+")).size
        val useDetection = !bubble.manualLanguageOverride && wordCount >= 2

        val myRequestId = ++liveCallRequestId
        viewModelScope.launch {
            val targetVerse = repository.getVerse(bubble.bubbleTargetLanguage, verse.bookId, verse.chapter, verse.verse)
            val verseRef = "${verse.bookName} ${verse.chapter}:${verse.verse}"

            if (useDetection) {
                val result = verseChatClient.sendMessageWithDetection(
                    apiKey = apiKey,
                    verseRef = verseRef,
                    sourceLangName = state.language.displayName,
                    sourceText = verse.text,
                    referenceTargetLangName = bubble.bubbleTargetLanguage.displayName,
                    referenceTargetText = targetVerse?.text.orEmpty(),
                    currentLanguage = bubble.bubbleTargetLanguage,
                    history = historyBeforeThisTurn,
                    userMessage = question,
                )
                if (myRequestId != liveCallRequestId) return@launch
                val current = _uiState.value.chatBubble ?: return@launch
                _uiState.value = _uiState.value.copy(
                    chatBubble = result.fold(
                        onSuccess = { (detectedLanguage, answer) ->
                            val newExchangeCount = current.exchangeCount + 1
                            val newIndex = if (advanceOriginalLanguage) current.originalLanguageIndex + 1 else current.originalLanguageIndex
                            current.copy(
                                bubbleTargetLanguage = detectedLanguage,
                                messages = current.messages + ChatMessage(ChatRole.ASSISTANT, answer),
                                isSendingFollowUp = false,
                                exchangeCount = newExchangeCount,
                                atCap = newExchangeCount >= MAX_CHAT_EXCHANGES,
                                suggestedChips = if (advanceOriginalLanguage || current.suggestedChips.isEmpty()) {
                                    suggestedChipsFor(state.selectedBookId, newIndex)
                                } else {
                                    current.suggestedChips
                                },
                                originalLanguageIndex = newIndex,
                            )
                        },
                        onFailure = { err ->
                            current.copy(
                                messages = current.messages + ChatMessage(ChatRole.ASSISTANT, "Sorry, that failed: ${err.message}", isError = true),
                                isSendingFollowUp = false,
                            )
                        },
                    ),
                )
                return@launch
            }

            val cached = verseChatCache.get(verse.numericVerseId, state.language, bubble.bubbleTargetLanguage, historyBeforeThisTurn, question)
            val result = if (cached != null) {
                Result.success(cached)
            } else {
                verseChatClient.sendMessage(
                    apiKey = apiKey,
                    verseRef = verseRef,
                    sourceLangName = state.language.displayName,
                    targetLangName = bubble.bubbleTargetLanguage.displayName,
                    sourceText = verse.text,
                    targetText = targetVerse?.text.orEmpty(),
                    history = historyBeforeThisTurn,
                    userMessage = question,
                ).onSuccess { answer ->
                    verseChatCache.put(verse.numericVerseId, state.language, bubble.bubbleTargetLanguage, historyBeforeThisTurn, question, answer)
                }
            }

            if (myRequestId != liveCallRequestId) return@launch
            val current = _uiState.value.chatBubble ?: return@launch
            _uiState.value = _uiState.value.copy(
                chatBubble = result.fold(
                    onSuccess = { answer ->
                        val newExchangeCount = current.exchangeCount + 1
                        val newIndex = if (advanceOriginalLanguage) current.originalLanguageIndex + 1 else current.originalLanguageIndex
                        current.copy(
                            messages = current.messages + ChatMessage(ChatRole.ASSISTANT, answer),
                            isSendingFollowUp = false,
                            exchangeCount = newExchangeCount,
                            atCap = newExchangeCount >= MAX_CHAT_EXCHANGES,
                            suggestedChips = if (advanceOriginalLanguage || current.suggestedChips.isEmpty()) {
                                suggestedChipsFor(state.selectedBookId, newIndex)
                            } else {
                                current.suggestedChips
                            },
                            originalLanguageIndex = newIndex,
                            manualLanguageOverride = false,
                        )
                    },
                    onFailure = { err ->
                        current.copy(
                            messages = current.messages + ChatMessage(ChatRole.ASSISTANT, "Sorry, that failed: ${err.message}", isError = true),
                            isSendingFollowUp = false,
                        )
                    },
                ),
            )
        }
    }

    /** Original-language options cycle one at a time rather than all showing together; order favors the book's own testament first. */
    private fun originalLanguageSequence(bookId: Int): List<String> =
        if (bookId <= LAST_OT_BOOK_ID) {
            listOf("Show Hebrew/Aramaic", "Show Greek", "Show Latin")
        } else {
            listOf("Show Greek", "Show Hebrew/Aramaic", "Show Latin")
        }

    private fun suggestedChipsFor(bookId: Int, originalLanguageIndex: Int): List<String> {
        val originalLanguageChip = originalLanguageSequence(bookId).getOrNull(originalLanguageIndex)
        val third = listOf("Cross-references", "Historical context").random()
        return listOfNotNull(originalLanguageChip, "Explain this verse", third)
    }

    private fun capWords(text: String, maxWords: Int): String {
        val trimmed = text.trim()
        val words = trimmed.split(Regex("\\s+"))
        return if (words.size <= maxWords) trimmed else words.take(maxWords).joinToString(" ")
    }

    /**
     * Fires a live lookup whenever exactly one word is in focus (verse tap or response-word
     * tap), grounded in the verse it appears in for the correct sense. Callers already put a
     * loading [WordInfoState] into the bubble synchronously before calling this, so the "looking
     * up…" state is visible immediately — this just resolves it once the network call returns.
     * Silently leaves it at "no definition" without a Gemini key — the rest of the bubble still
     * works.
     */
    private fun fetchWordInfoAsync(verse: VerseData, word: String, language: BibleLanguage) {
        val myId = ++wordInfoRequestId
        val apiKey = ApiKeys.geminiApiKey
        if (apiKey == null) {
            val current = _uiState.value.chatBubble ?: return
            _uiState.value = _uiState.value.copy(chatBubble = current.copy(wordInfo = WordInfoState(word, isLoading = false)))
            return
        }
        val verseRef = "${verse.bookName} ${verse.chapter}:${verse.verse}"
        viewModelScope.launch {
            val result = verseChatClient.fetchWordInfo(apiKey, word, language.displayName, verseRef, verse.text)
            if (myId != wordInfoRequestId) return@launch
            val current = _uiState.value.chatBubble ?: return@launch
            _uiState.value = _uiState.value.copy(
                chatBubble = result.fold(
                    onSuccess = { (pronunciation, definition) ->
                        current.copy(wordInfo = WordInfoState(word, pronunciation, definition, isLoading = false))
                    },
                    onFailure = { current.copy(wordInfo = WordInfoState(word, isLoading = false)) },
                ),
            )
        }
    }

    /** Invalidates any in-flight word-info fetch and clears the card — selection is no longer a single word. */
    private fun clearWordInfo(bubble: ChatBubbleState): ChatBubbleState {
        wordInfoRequestId++
        return bubble.copy(wordInfo = null)
    }

    private fun updateSelection(verseNumber: Int, range: IntRange) {
        // Starting a new word selection means the user has moved on from wherever a verse
        // search last landed them — clear that highlight rather than leaving it pulsing
        // somewhere off-screen. Cleared before capturing `state` so the copies below don't
        // resurrect the stale value.
        if (_uiState.value.highlightedVerse != null) {
            _uiState.value = _uiState.value.copy(highlightedVerse = null)
        }
        val state = _uiState.value
        val verse = state.verses.firstOrNull { it.verse == verseNumber } ?: return
        val tokens = VerseTokenizer.tokenize(verse.text)
        val clamped = range.first.coerceIn(0, tokens.lastIndex)..range.last.coerceIn(0, tokens.lastIndex)
        val wordsForVerse = state.wordTranslations[verseNumber].orEmpty().associateBy { it.wordIndex }
        val parts = clamped.map { wordsForVerse[it]?.translatedWord }
        val hasData = parts.all { it != null }

        val myRequestId = ++liveCallRequestId

        // A lone word gets a pronunciation + definition card, regardless of which translation
        // path handles the rest (tap-word-autofill clarification: single-word focus). The
        // WordInfoState is baked into the ChatBubbleState below (constructed with isLoading =
        // true) *before* the network call starts, so the "looking up…" state is visible the
        // instant the bubble opens rather than only after the request resolves — and so it
        // isn't clobbered by the ChatBubbleState construction that follows it.
        val singleWord: String? = if (clamped.first == clamped.last) tokens[clamped.first] else null
        val initialWordInfo = singleWord?.let { WordInfoState(it, isLoading = true) }
        if (singleWord == null) wordInfoRequestId++ // no single word in focus; invalidate any stale in-flight fetch

        if (hasData) {
            val text = parts.filterNotNull().distinctFromNeighbors().joinToString(" ")
            _uiState.value = state.copy(
                chatBubble = ChatBubbleState(verseNumber, clamped, state.targetLanguage, text, initialHasData = true, wordInfo = initialWordInfo),
            )
            singleWord?.let { fetchWordInfoAsync(verse, it, state.language) }
            return
        }

        if (state.selectedBookId == LEVITICUS_BOOK_ID) {
            val apiKey = ApiKeys.geminiApiKey
            if (apiKey == null) {
                _uiState.value = state.copy(
                    chatBubble = ChatBubbleState(
                        verseNumber, clamped, state.targetLanguage,
                        "No Gemini API key configured for this build.", initialHasData = false, wordInfo = initialWordInfo,
                    ),
                )
                singleWord?.let { fetchWordInfoAsync(verse, it, state.language) }
                return
            }
            val selectedText = clamped.joinToString(" ") { tokens[it] }
            _uiState.value = state.copy(
                chatBubble = ChatBubbleState(
                    verseNumber, clamped, state.targetLanguage, "Translating…", initialHasData = false, initialIsLoading = true, wordInfo = initialWordInfo,
                ),
            )
            singleWord?.let { fetchWordInfoAsync(verse, it, state.language) }
            viewModelScope.launch {
                val cached = liveTranslationCache.get(verse.numericVerseId, state.language, state.targetLanguage, clamped.first, clamped.last)
                if (cached != null) {
                    applyInitialResult(myRequestId, Result.success(cached))
                } else {
                    runGeminiLiveTranslate(myRequestId, verse, selectedText, apiKey, state.language, state.targetLanguage, clamped)
                }
            }
            return
        }

        if (state.selectedBookId == EXODUS_BOOK_ID) {
            val apiKey = ApiKeys.translateApiKey
            if (apiKey == null) {
                _uiState.value = state.copy(
                    chatBubble = ChatBubbleState(
                        verseNumber, clamped, state.targetLanguage,
                        "No Translation API key configured for this build.", initialHasData = false, wordInfo = initialWordInfo,
                    ),
                )
                singleWord?.let { fetchWordInfoAsync(verse, it, state.language) }
                return
            }
            val selectedWords = clamped.map { tokens[it] }
            _uiState.value = state.copy(
                chatBubble = ChatBubbleState(
                    verseNumber, clamped, state.targetLanguage, "Translating…", initialHasData = false, initialIsLoading = true, wordInfo = initialWordInfo,
                ),
            )
            singleWord?.let { fetchWordInfoAsync(verse, it, state.language) }
            viewModelScope.launch {
                val cached = liveTranslationCache.get(verse.numericVerseId, state.language, state.targetLanguage, clamped.first, clamped.last)
                if (cached != null) {
                    applyInitialResult(myRequestId, Result.success(cached))
                } else {
                    runGoogleTranslateLive(myRequestId, verse, selectedWords, apiKey, state.language, state.targetLanguage, clamped)
                }
            }
            return
        }

        _uiState.value = state.copy(
            chatBubble = ChatBubbleState(verseNumber, clamped, state.targetLanguage, "No word-level translation yet", initialHasData = false, wordInfo = initialWordInfo),
        )
        singleWord?.let { fetchWordInfoAsync(verse, it, state.language) }
    }

    /** The "direct Gemini calls, non-preprocessed" experiment arm (Leviticus) — one live call per tap, with verse context. */
    private suspend fun runGeminiLiveTranslate(
        requestId: Int,
        verse: VerseData,
        selectedText: String,
        apiKey: String,
        sourceLanguage: BibleLanguage,
        targetLanguage: BibleLanguage,
        wordRange: IntRange,
    ) {
        val targetVerse = repository.getVerse(targetLanguage, verse.bookId, verse.chapter, verse.verse)
        val result = liveTranslateClient.translateSelection(
            apiKey = apiKey,
            sourceLangName = sourceLanguage.displayName,
            targetLangName = targetLanguage.displayName,
            sourceVerseText = verse.text,
            targetVerseText = targetVerse?.text.orEmpty(),
            selectedText = selectedText,
        )
        result.onSuccess { text ->
            liveTranslationCache.put(verse.numericVerseId, sourceLanguage, targetLanguage, wordRange.first, wordRange.last, text)
        }
        applyInitialResult(requestId, result)
    }

    /** The "dedicated translate API, non-preprocessed" experiment arm (Exodus) — no verse context, word-by-word. */
    private suspend fun runGoogleTranslateLive(
        requestId: Int,
        verse: VerseData,
        selectedWords: List<String>,
        apiKey: String,
        sourceLanguage: BibleLanguage,
        targetLanguage: BibleLanguage,
        wordRange: IntRange,
    ) {
        val result = googleTranslateClient.translateWords(
            apiKey = apiKey,
            sourceLangCode = sourceLanguage.code,
            targetLangCode = targetLanguage.code,
            words = selectedWords,
        ).map { it.joinToString(" ") }
        result.onSuccess { text ->
            liveTranslationCache.put(verse.numericVerseId, sourceLanguage, targetLanguage, wordRange.first, wordRange.last, text)
        }
        applyInitialResult(requestId, result)
    }

    private fun applyInitialResult(requestId: Int, result: Result<String>) {
        if (requestId != liveCallRequestId) return // selection changed while we were waiting
        val current = _uiState.value.chatBubble ?: return
        _uiState.value = _uiState.value.copy(
            chatBubble = result.fold(
                onSuccess = { text -> current.copy(initialTranslation = text, initialHasData = true, initialIsLoading = false) },
                onFailure = { err -> current.copy(initialTranslation = "Live translate failed: ${err.message}", initialIsLoading = false) },
            ),
        )
    }

    /** Verse-level "translate verse" — a straight DB lookup (§6), no precomputed data needed. */
    fun onTranslateVerseRequested(verse: VerseData) {
        val state = _uiState.value
        viewModelScope.launch {
            val otherLanguages = BibleLanguage.entries.filter { it != state.language }
            val translations = otherLanguages.map { lang ->
                val translated = repository.getVerse(lang, verse.bookId, verse.chapter, verse.verse)
                VerseTranslationEntry(lang, translated?.text)
            }
            _uiState.value = _uiState.value.copy(
                verseDialog = VerseDialogData(
                    bookName = verse.bookName,
                    chapter = verse.chapter,
                    verse = verse.verse,
                    sourceLanguage = state.language,
                    originalText = verse.text,
                    translations = translations,
                ),
            )
        }
    }

    fun dismissVerseDialog() {
        _uiState.value = _uiState.value.copy(verseDialog = null)
    }

    private fun defaultTargetFor(language: BibleLanguage): BibleLanguage =
        BibleLanguage.entries.first { it != language }

    private suspend fun loadBooksAndChapter(
        language: BibleLanguage,
        bookId: Int,
        chapter: Int,
        targetLanguage: BibleLanguage,
    ) {
        _uiState.value = _uiState.value.copy(isLoading = true, chatBubble = null)
        val books = repository.getBooks(language)
        val resolvedBookId = if (books.any { it.bookId == bookId }) bookId else books.firstOrNull()?.bookId ?: 1
        val bookName = books.firstOrNull { it.bookId == resolvedBookId }?.bookName.orEmpty()
        loadChapter(language, resolvedBookId, bookName, books, chapter, targetLanguage)
    }

    private suspend fun loadChapter(
        language: BibleLanguage,
        bookId: Int,
        bookName: String,
        books: List<BookInfo>,
        chapter: Int,
        targetLanguage: BibleLanguage,
        highlightedVerse: Int? = null,
    ) {
        _uiState.value = _uiState.value.copy(isLoading = true, chatBubble = null)
        val chapterCount = repository.getChapterCount(language, bookId).coerceAtLeast(1)
        val resolvedChapter = chapter.coerceIn(1, chapterCount)
        val verses = repository.getChapter(language, bookId, resolvedChapter)
        val wordTranslations = wordTranslationRepository.getChapterWordTranslations(language, targetLanguage, bookId, resolvedChapter)
        _uiState.value = ReaderUiState(
            language = language,
            targetLanguage = targetLanguage,
            books = books,
            selectedBookId = bookId,
            selectedBookName = bookName,
            chapterCount = chapterCount,
            selectedChapter = resolvedChapter,
            verses = verses,
            wordTranslations = wordTranslations,
            isLoading = false,
            highlightedVerse = highlightedVerse,
        )
    }

    private suspend fun reloadWordTranslations() {
        val state = _uiState.value
        val wordTranslations = wordTranslationRepository.getChapterWordTranslations(
            state.language, state.targetLanguage, state.selectedBookId, state.selectedChapter,
        )
        _uiState.value = _uiState.value.copy(wordTranslations = wordTranslations)
    }
}

/**
 * Parses an exact verse query in the "[Book] [chapter]:[verse]" shape (e.g. "John 3:16" or
 * "Song of Solomon 2:1") against the given book list — exact name match first, falling back to
 * a prefix match so a partially-typed book name picked from the autosuggest list still resolves.
 */
private fun parseVerseQuery(query: String, books: List<BookInfo>): Triple<BookInfo, Int, Int>? {
    val match = Regex("""^\s*(.+?)\s+(\d+)\s*:\s*(\d+)\s*$""").find(query) ?: return null
    val (bookPart, chapterStr, verseStr) = match.destructured
    val chapter = chapterStr.toIntOrNull() ?: return null
    val verseNumber = verseStr.toIntOrNull() ?: return null
    val trimmedBookPart = bookPart.trim()
    val book = books.firstOrNull { it.bookName.equals(trimmedBookPart, ignoreCase = true) }
        ?: books.firstOrNull { it.bookName.startsWith(trimmedBookPart, ignoreCase = true) }
        ?: return null
    return Triple(book, chapter, verseNumber)
}

private fun List<String>.distinctFromNeighbors(): List<String> =
    fold(mutableListOf<String>()) { acc, item ->
        if (acc.lastOrNull() != item) acc.add(item)
        acc
    }
