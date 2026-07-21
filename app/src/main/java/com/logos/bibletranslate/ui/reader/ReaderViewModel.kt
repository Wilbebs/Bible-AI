package com.logos.bibletranslate.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.logos.bibletranslate.data.ApiKeys
import com.logos.bibletranslate.data.VerseTextToSpeech
import com.logos.bibletranslate.data.BibleLanguage
import com.logos.bibletranslate.data.PartnerJudgmentKind
import com.logos.bibletranslate.data.PartnerSpeechRecognizer
import com.logos.bibletranslate.data.PartnerTurn
import kotlinx.coroutines.Dispatchers
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
import com.logos.bibletranslate.data.verseNumericId
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

/** Parses a verse reference string like "John 3:16" or "1 Samuel 2:3-5" into its components. */
private val VERSE_REF_PARSE_REGEX = Regex(
    """((?:[123]\s+)?[A-Z][a-z]+(?:\s+(?:of\s+)?[A-Z][a-z]+)?)\s+(\d+):(\d+)"""
)

/** Last Old Testament book_id in the bundled KJV ordering (Malachi); used to pick the Hebrew vs. Greek chip. */
private const val LAST_OT_BOOK_ID = 39

/** ~200 tokens per the addendum's follow-up input cap, approximated as a word count. */
private const val MAX_FOLLOWUP_WORDS = 150

/** Single-word focus card: pronunciation + dictionary definition + an optional cross-language translation. */
data class WordInfoState(
    val word: String,
    val pronunciation: String? = null,
    val definition: String? = null,
    /**
     * Short translation of [word] into the cross-language direction (e.g. Spanish→English or
     * English→Spanish), fetched alongside the definition so every word lookup shows the same
     * "word · translation" pairing as the initial scripture-word tap. Null if the lookup is
     * still in flight, failed, or if both languages happen to be the same.
     */
    val translation: String? = null,
    val isLoading: Boolean = true,
)

/** A tap/drag word selection, now a scoped mini-chat bubble (chat-feature-addendum). */
data class ChatBubbleState(
    /** Globally unique (book+chapter+verse) id — a bare verse *number* isn't unique once the
     * reader shows a continuous, multi-chapter scroll, since e.g. "verse 3" exists in every
     * chapter. */
    val verseId: Long,
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
    /** The verse word this bubble was opened on, when the selection is exactly one word. */
    val selectedSingleWord: String? = null,
    /**
     * Single-word minimal mode: just "word · translation" plus a Define affordance — no
     * header, conversation, or input. Cleared (with the full UI revealed) when the user
     * explicitly asks for the definition, or grows out of it by extending the selection.
     */
    val isCondensed: Boolean = false,
    /**
     * Bumped every time the ViewModel *programmatically* replaces [followUpInput] (word-tap
     * autofill, clearing after send/start-over) — never on ordinary typing. The input field
     * keeps its text/cursor as local state for glitch-free typing and only re-syncs from
     * [followUpInput] when this changes, so async state round-trips can't reorder characters
     * or teleport the cursor mid-typing.
     */
    val inputSetSequence: Int = 0,
    /**
     * Set when the user taps outside the panel — collapses it to just the word/verse
     * translation plus the header controls, without discarding the conversation
     * (unlike "Delete chat", which clears history). Tapping the collapsed panel again
     * expands it back.
     */
    val isMinimized: Boolean = false,
    /** Increments every time a *new* bubble opens — drives which follow-up suggestion shows (once, not on a timer). */
    val openSequence: Int = 0,

    // ── Partner Reading mode ─────────────────────────────────────────────────
    /** True while the partner-reading view is shown (toggle at the header top-left). */
    val isPartnerMode: Boolean = false,
    /** Current state-machine step of the turn loop. */
    val partnerTurn: PartnerTurn = PartnerTurn.AI_SPEAKING,
    /** Index into [ReaderUiState.verses] pointing at whichever verse is active this turn. */
    val partnerVerseIndex: Int = 0,
    /** Label shown in the partner body header ("Genesis 1:2"). */
    val partnerVerseLabel: String = "",
    /** Full text of the active verse — shown to the user during their turn. */
    val partnerVerseText: String = "",
    /** Running transcript of questions/answers exchanged during the reading session. */
    val partnerMessages: List<com.logos.bibletranslate.data.ChatMessage> = emptyList(),
    /** Whether the partner mic is enabled; default on, user can tap to pause. */
    val partnerMicEnabled: Boolean = true,
    /** True while the chat-input mic is actively capturing speech. */
    val chatMicListening: Boolean = false,
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
    /** A continuous, possibly multi-chapter run of verses — not just the "selected" chapter's own. */
    val verses: List<VerseData> = emptyList(),
    /** Keyed by [VerseData.numericVerseId], not a bare verse number, so chapters can merge safely. */
    val wordTranslations: Map<Long, List<WordTranslation>> = emptyMap(),
    val isLoading: Boolean = true,
    val chatBubble: ChatBubbleState? = null,
    val verseDialog: VerseDialogData? = null,
    /** Set by an exact verse search — drives the scroll-to + slow pulsing highlight, and self-clears after a while. */
    val highlightedVerseId: Long? = null,
    /**
     * Set right after a triple-tap selects a whole verse (translation in flight/shown). While
     * set, further taps *on this same verse* are inert — guards against an accidental 4th/5th
     * tap re-triggering or disturbing the selection mid-translation. Cleared only by a tap
     * outside this verse (another verse, or blank space), never by a timer.
     */
    val lockedVerseId: Long? = null,
    /** Continuous-scroll pagination state — [verses] is lazily extended in both directions as the user nears either edge. */
    val isLoadingMoreTop: Boolean = false,
    val isLoadingMoreBottom: Boolean = false,
    val hasMoreTop: Boolean = true,
    val hasMoreBottom: Boolean = true,
    /** Set right after prepending N verses at the top, so the UI can compensate the scroll offset once, then clear it. */
    val pendingTopPrependCount: Int = 0,
    /**
     * Incremented each time a fresh chapter is explicitly navigated to (book/chapter picker,
     * search jump, language switch). The ReaderScreen watches this value and scrolls to item 0
     * whenever it changes — so every explicit navigation always starts at verse 1.
     * Not incremented by the continuous-scroll prepend/append paths.
     */
    val scrollToTopTrigger: Int = 0,
    /** Which verse is currently showing its action icons (speaker + bookmark).
     *  Set by tapping the verse number; null when none are shown. */
    val hoveredVerseId: Long? = null,
    /** Verses the user has bookmarked this session — UI-only until a DB layer is added. */
    val bookmarkedVerseIds: Set<Long> = emptySet(),
    /** Which verse is currently highlighted for partner reading (null when mode is off). */
    val partnerHighlightVerseId: Long? = null,
    /** True = AI's verse (first accent color); false = user's verse (last accent color). */
    val partnerHighlightIsAi: Boolean? = null,
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

    /** TTS engine — lazily initialised from the composable via [initTts]; released in [onCleared]. */
    private var tts: VerseTextToSpeech? = null
    /** Speech recogniser for partner reading — created once alongside TTS. */
    private var recognizer: PartnerSpeechRecognizer? = null
    /** Application context stored during initTts — needed for speech recognition. */
    private var appContext: Context? = null

    /** Guards against a stale live-call result overwriting a newer selection. */
    private var liveCallRequestId = 0

    /** Separate counter for the word-info lookup, since it runs independently of translation/chat calls. */
    private var wordInfoRequestId = 0

    /** Increments once per bubble opened, so the follow-up placeholder shows a different suggestion each open (not a timer). */
    private var bubbleOpenSequenceCounter = 0

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
        val targetId = verseNumericId(book.bookId, chapter, verseNumber)
        viewModelScope.launch {
            if (state.verses.any { it.numericVerseId == targetId }) {
                _uiState.value = _uiState.value.copy(highlightedVerseId = targetId)
            } else {
                loadChapter(state.language, book.bookId, book.bookName, state.books, chapter, state.targetLanguage, highlightedVerseId = targetId)
            }
        }
    }

    /** Self-clear for the search highlight — called by the UI after the pulse has had time to draw the eye. */
    fun clearHighlightedVerse() {
        if (_uiState.value.highlightedVerseId == null) return
        _uiState.value = _uiState.value.copy(highlightedVerseId = null)
    }

    /** Tap-down or drag-start on a word (§5). */
    fun onSelectionStart(verseId: Long, wordIndex: Int) {
        updateSelection(verseId, wordIndex..wordIndex)
    }

    /** Locks a just-triple-tapped verse against further taps until an outside tap releases it. */
    fun lockVerse(verseId: Long) {
        _uiState.value = _uiState.value.copy(lockedVerseId = verseId)
    }

    /**
     * Fired at the very start of every gesture on a verse row (before any tap/hold logic runs).
     * A down on a *different* verse than the one currently locked counts as "outside the
     * locked verse" and releases the lock; a down on the locked verse itself is a no-op here
     * (the row's own gesture code is what ignores it).
     */
    fun onVerseGestureDown(verseId: Long) {
        val locked = _uiState.value.lockedVerseId ?: return
        if (locked != verseId) _uiState.value = _uiState.value.copy(lockedVerseId = null)
    }

    /** Blank-space taps (below the last verse, outside the panel) also count as "outside". */
    fun onOutsideAllVersesTap() {
        if (_uiState.value.lockedVerseId != null) _uiState.value = _uiState.value.copy(lockedVerseId = null)
    }

    /** Drag extending the selection; live-updates the bubble (§5). */
    fun onSelectionExtend(verseId: Long, wordIndex: Int) {
        val current = _uiState.value.chatBubble ?: return
        if (current.verseId != verseId) return
        val start = current.wordRange.first
        val newRange = if (wordIndex >= start) start..wordIndex else wordIndex..start
        updateSelection(verseId, newRange)
    }

    /**
     * Tap on a word while the bubble is already open on this same verse — toggles it into/out of
     * the pending autofilled question instead of starting a new translation selection
     * (tap-word-autofill-idea.md). Taps on a different verse fall through to onSelectionStart
     * and open a fresh bubble there instead.
     */
    fun onVerseWordTapped(verseId: Long, wordIndex: Int) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (bubble.verseId != verseId) return
        val verse = state.verses.firstOrNull { it.numericVerseId == verseId } ?: return
        val tokens = VerseTokenizer.tokenize(verse.text)
        if (wordIndex !in tokens.indices) return

        // Word-tap behaviour:
        //  • Tap the currently shown single word   → deselect (toggle off)
        //  • Tap a DIFFERENT word while exactly 1 is defined → replace the selection
        //    (so the new word's definition loads rather than building a 2-word autofill
        //     question — the confusing "pie gives eat's definition" loop fixed here)
        //  • Tap any word in a multi-word selection → normal toggle
        val onlyCurrentWord = bubble.queuedWordIndices.singleOrNull()
        val newQueue = when {
            wordIndex == onlyCurrentWord -> bubble.queuedWordIndices - wordIndex  // deselect
            onlyCurrentWord != null && bubble.wordInfo != null ->
                listOf(wordIndex)  // replace: show new word's definition
            wordIndex in bubble.queuedWordIndices -> bubble.queuedWordIndices - wordIndex
            else -> bubble.queuedWordIndices + wordIndex
        }
        val questionText = buildAutofillQuestion(newQueue.map { tokens[it] })
        val updatedBubble = bubble.copy(
            queuedWordIndices = newQueue,
            followUpInput = questionText,
            inputSetSequence = bubble.inputSetSequence + 1,
        )
        if (newQueue.size == 1) {
            val word = tokens[newQueue.first()]
            // Show the loading state the moment the word is tapped, before the network
            // call resolves, so a definition visibly starts generating right away.
            // Also inject a contextual "Use in a sentence" chip so the user has a
            // natural next step right next to the word they just looked up.
            val wordChips = listOf("Use \"$word\" in a sentence") +
                suggestedChipsFor(state.selectedBookId, bubble.originalLanguageIndex).take(2)
            _uiState.value = state.copy(chatBubble = updatedBubble.copy(
                wordInfo = WordInfoState(word, isLoading = true),
                suggestedChips = wordChips,
            ))
            // The word itself is in the reading language (state.language), but the pronunciation/
            // definition should print in whatever language is currently selected in the bubble's
            // own dropdown, so it stays consistent with everything else in the bubble. The
            // translation target is bubbleTargetLanguage (Spanish word → English translation).
            fetchWordInfoAsync(verse, word, state.language, bubble.bubbleTargetLanguage, bubble.bubbleTargetLanguage)
        } else {
            _uiState.value = state.copy(chatBubble = clearWordInfo(updatedBubble))
        }
    }

    private fun buildAutofillQuestion(words: List<String>): String {
        if (words.isEmpty()) return ""
        if (words.size == 1) return ""   // single word: auto-defined already, no prefill needed
        val joined = words.joinToString(", ")
        return "What do $joined mean in this verse?"
    }

    /**
     * Tap on a word inside the assistant's generated reply — prefills a question about it. Stays
     * on whatever language is currently selected in the bubble's own dropdown (bubbleTargetLanguage)
     * rather than forcing it back to the app-wide default: the tapped word is already written in
     * that language, so a lookup should stay in it too.
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
            inputSetSequence = bubble.inputSetSequence + 1,
            manualLanguageOverride = true,
        )
        if (newQueue.size == 1) {
            val verse = state.verses.firstOrNull { it.numericVerseId == bubble.verseId }
            if (verse != null) {
                // Same immediate-loading treatment as a verse-text tap (§3). The word is in the
                // target language; translate it back to the source (scripture) language for the
                // "word · translation" pairing shown in the card.
                val wordChips = listOf("Use \"${newQueue.first()}\" in a sentence") +
                    suggestedChipsFor(state.selectedBookId, bubble.originalLanguageIndex).take(2)
                _uiState.value = state.copy(chatBubble = updatedBubble.copy(
                    wordInfo = WordInfoState(newQueue.first(), isLoading = true),
                    suggestedChips = wordChips,
                ))
                fetchWordInfoAsync(verse, newQueue.first(), bubble.bubbleTargetLanguage, bubble.bubbleTargetLanguage, state.language)
            } else {
                _uiState.value = state.copy(chatBubble = updatedBubble)
            }
        } else {
            _uiState.value = state.copy(chatBubble = clearWordInfo(updatedBubble))
        }
    }

    /**
     * Tap on a word *inside the word-info card's own definition* — a distinct gesture from
     * [onResponseWordTapped]: it drills one level deeper ("what does *this* word in the
     * definition mean?") instead of building up the multi-word follow-up autofill question. The
     * current card disappears immediately, replaced by a loading state for the newly tapped
     * word — and since a word inside a definition is already written in whatever language the
     * bubble's dropdown is set to, both the lookup and its own definition stay in that language,
     * so chained lookups (define a word, tap a word in *that* definition, and so on) never drift
     * to a different language partway through.
     */
    /**
     * Tap on a verse reference hyperlink inside an AI reply (e.g. "John 3:16") — looks the verse
     * up in the current reading language's DB and injects it as a scripture card in the chat so
     * the user can read it without leaving the AI window or triggering a fresh navigation.
     */
    // ── TTS + verse action icons ─────────────────────────────────────────────

    /** Call once from the composable (LaunchedEffect) to hand the engine an application context. */
    fun initTts(context: Context) {
        if (tts == null) {
            val appCtx = context.applicationContext
            appContext = appCtx
            tts = VerseTextToSpeech(appCtx)
            recognizer = PartnerSpeechRecognizer()
        }
    }

    /** Tapping the verse number shows/hides the action icons for that verse.
     *  Tapping a second time (same verse) hides them; tapping a different verse moves them. */
    fun onVerseHoverToggle(id: Long) {
        val cur = _uiState.value.hoveredVerseId
        _uiState.value = _uiState.value.copy(hoveredVerseId = if (cur == id) null else id)
    }

    /** Toggle bookmark for a verse — soft highlight only for now, no DB write. */
    fun onToggleBookmark(id: Long) {
        val current = _uiState.value.bookmarkedVerseIds
        _uiState.value = _uiState.value.copy(
            bookmarkedVerseIds = if (id in current) current - id else current + id,
        )
    }

    /** Reads a verse's full text aloud using the verse's reading language locale. */
    fun onSpeakVerse(text: String, langCode: String) { tts?.speak(text, langCode) }

    /** Reads any AI-generated text aloud (initial translation, chat reply, word definition). */
    fun onSpeakAiText(text: String, langCode: String) { tts?.speak(text, langCode) }

    /** Toggle the partner mic on/off without changing the turn state. */
    fun onPartnerMicToggle() {
        val bubble = _uiState.value.chatBubble ?: return
        _uiState.value = _uiState.value.copy(
            chatBubble = bubble.copy(partnerMicEnabled = !bubble.partnerMicEnabled),
        )
    }

    /**
     * Tapping the mic button in the regular chat input row — captures a single utterance
     * and fills the follow-up input field with the transcript so the user can review and send.
     * No-op when partner mode is active (its own mic handles that path).
     */
    fun onChatMicTapped() {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (bubble.isPartnerMode) return
        viewModelScope.launch(Dispatchers.Main) {
            val ctx = appContext ?: return@launch
            val cur = _uiState.value.chatBubble ?: return@launch
            _uiState.value = _uiState.value.copy(chatBubble = cur.copy(chatMicListening = true))
            val langTag = localeTagFor(state.language.code)
            val result = recognizer?.listenOnce(ctx, langTag)
            val latest = _uiState.value.chatBubble
            if (latest != null) _uiState.value = _uiState.value.copy(chatBubble = latest.copy(chatMicListening = false))
            result?.onSuccess { transcript ->
                if (transcript.isNotBlank()) onFollowUpInputChanged(transcript)
            }
        }
    }

    /** Stop any in-progress TTS playback (e.g. when the bubble closes or language changes). */
    fun onStopSpeaking() { tts?.stop() }

    // ── Partner Reading orchestration ────────────────────────────────────────

    /** Enter partner reading mode.
     *
     * Starts at whichever verse the user last tapped the verse-number of (hoveredVerseId),
     * falling back to the verse the bubble is anchored to. No odd/even snapping — the AI
     * reads the anchor verse first, then alternates from there. */
    fun onEnterPartnerMode() {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        val verses = state.verses
        // Prefer the verse the user has highlighted (tapped verse-number); fall back to
        // the bubble's anchor verse so opening partner mode from a tap always feels intentional.
        val anchorVerseId = state.hoveredVerseId ?: bubble.verseId
        val anchorIdx = verses.indexOfFirst { it.numericVerseId == anchorVerseId }
            .coerceAtLeast(0)
        _uiState.value = state.copy(
            chatBubble = bubble.copy(
                isPartnerMode = true,
                partnerMessages = emptyList(),
            ),
        )
        speakAiPartnerVerse(anchorIdx)
    }

    /** Exit partner reading mode, clear all partner highlights. */
    fun onExitPartnerMode() {
        tts?.stop()
        val bubble = _uiState.value.chatBubble ?: return
        _uiState.value = _uiState.value.copy(
            chatBubble = bubble.copy(isPartnerMode = false),
            partnerHighlightVerseId = null,
            partnerHighlightIsAi = null,
        )
    }

    /** Tap the mic button during the user's turn — starts SpeechRecognizer on the main thread. */
    fun onPartnerMicTapped() {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        if (bubble.partnerTurn != PartnerTurn.AWAITING_USER) return
        if (!bubble.partnerMicEnabled) return
        viewModelScope.launch(Dispatchers.Main) {
            setPartnerTurn(PartnerTurn.LISTENING)
            val ctx = appContext ?: return@launch
            val langTag = localeTagFor(state.language.code)
            val result = recognizer?.listenOnce(ctx, langTag) ?: return@launch
            result.fold(
                onSuccess = { transcript -> handlePartnerTranscript(transcript) },
                onFailure = { setPartnerTurn(PartnerTurn.AWAITING_USER) },
            )
        }
    }

    private suspend fun handlePartnerTranscript(transcript: String) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        val verses = state.verses
        val userVerseIndex = bubble.partnerVerseIndex
        val expectedText = verses.getOrNull(userVerseIndex)?.text ?: return
        val langName = state.language.displayName

        setPartnerTurn(PartnerTurn.AI_RESPONDING)

        val apiKey = ApiKeys.geminiApiKey ?: return
        val judgment = verseChatClient.judgePartnerReading(apiKey, expectedText, langName, transcript)

        judgment.fold(
            onSuccess = { j ->
                when (j.kind) {
                    PartnerJudgmentKind.GOOD_READ -> {
                        // Brief affirmation (≤8 words), then immediately advance to next AI verse
                        val nextAiIdx = userVerseIndex + 1
                        if (j.reply.isNotBlank()) {
                            tts?.speak(j.reply, state.language.code) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    advanceToAiVerse(nextAiIdx)
                                }
                            }
                        } else {
                            advanceToAiVerse(nextAiIdx)
                        }
                    }
                    PartnerJudgmentKind.BAD_READ -> {
                        // Gentle note, then return to AWAITING_USER for a retry on the same verse
                        tts?.speak(j.reply, state.language.code) {
                            viewModelScope.launch(Dispatchers.Main) {
                                setPartnerTurn(PartnerTurn.AWAITING_USER)
                            }
                        } ?: setPartnerTurn(PartnerTurn.AWAITING_USER)
                    }
                    PartnerJudgmentKind.QUESTION_OR_STATEMENT -> {
                        // Append Q&A to partnerMessages, speak the answer, return to AWAITING_USER
                        val cur = _uiState.value.chatBubble ?: return
                        val updatedMsgs = cur.partnerMessages +
                            com.logos.bibletranslate.data.ChatMessage(
                                role = com.logos.bibletranslate.data.ChatRole.USER, text = transcript,
                            ) +
                            com.logos.bibletranslate.data.ChatMessage(
                                role = com.logos.bibletranslate.data.ChatRole.ASSISTANT, text = j.reply,
                            )
                        _uiState.value = _uiState.value.copy(
                            chatBubble = (_uiState.value.chatBubble ?: return).copy(
                                partnerMessages = updatedMsgs,
                                partnerTurn = PartnerTurn.AI_RESPONDING,
                            ),
                        )
                        tts?.speak(j.reply, state.language.code) {
                            viewModelScope.launch(Dispatchers.Main) {
                                // Return to AWAITING_USER — no prompt; user will tap mic when ready
                                setPartnerTurn(PartnerTurn.AWAITING_USER)
                            }
                        } ?: setPartnerTurn(PartnerTurn.AWAITING_USER)
                    }
                }
            },
            onFailure = { setPartnerTurn(PartnerTurn.AWAITING_USER) },
        )
    }

    private fun advanceToAiVerse(aiIdx: Int) {
        val verses = _uiState.value.verses
        if (aiIdx >= verses.size) {
            onExitPartnerMode()
            return
        }
        speakAiPartnerVerse(aiIdx)
    }

    private fun speakAiPartnerVerse(aiIdx: Int) {
        val state = _uiState.value
        val verses = state.verses
        val aiVerse = verses.getOrNull(aiIdx) ?: run { onExitPartnerMode(); return }
        val bubble = state.chatBubble ?: return
        _uiState.value = state.copy(
            chatBubble = bubble.copy(
                partnerTurn = PartnerTurn.AI_SPEAKING,
                partnerVerseIndex = aiIdx,
                partnerVerseLabel = "${aiVerse.bookName} ${aiVerse.chapter}:${aiVerse.verse}",
                partnerVerseText = aiVerse.text,
            ),
            partnerHighlightVerseId = aiVerse.numericVerseId,
            partnerHighlightIsAi = true,
        )
        tts?.speak(aiVerse.text, state.language.code) {
            viewModelScope.launch(Dispatchers.Main) {
                val userIdx = aiIdx + 1
                val userVerse = _uiState.value.verses.getOrNull(userIdx)
                if (userVerse == null) { onExitPartnerMode(); return@launch }
                val cur = _uiState.value.chatBubble ?: return@launch
                _uiState.value = _uiState.value.copy(
                    chatBubble = cur.copy(
                        partnerTurn = PartnerTurn.AWAITING_USER,
                        partnerVerseIndex = userIdx,
                        partnerVerseLabel = "${userVerse.bookName} ${userVerse.chapter}:${userVerse.verse}",
                        partnerVerseText = userVerse.text,
                    ),
                    partnerHighlightVerseId = userVerse.numericVerseId,
                    partnerHighlightIsAi = false,
                )
            }
        }
    }

    private fun setPartnerTurn(turn: PartnerTurn) {
        val bubble = _uiState.value.chatBubble ?: return
        _uiState.value = _uiState.value.copy(
            chatBubble = bubble.copy(partnerTurn = turn),
        )
    }

    /** BCP-47 tag that matches the reading language — used for SpeechRecognizer locale. */
    private fun localeTagFor(code: String) = when (code) {
        "en" -> "en-US"
        "es" -> "es-ES"
        "pt" -> "pt-BR"
        "he" -> "iw-IL"
        "el" -> "el-GR"
        "la" -> "la"
        else -> "en-US"
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
    }

    fun onVerseRefTapped(ref: String) {
        val state = _uiState.value
        if (state.chatBubble == null) return
        viewModelScope.launch {
            val verseData = lookupVerseRef(ref, state) ?: return@launch
            val current = _uiState.value.chatBubble ?: return@launch
            val display = "${verseData.bookName} ${verseData.chapter}:${verseData.verse}\n${verseData.text}"
            _uiState.value = _uiState.value.copy(
                chatBubble = current.copy(
                    messages = current.messages + ChatMessage(
                        role = ChatRole.ASSISTANT,
                        text = display,
                        isVerseCard = true,
                    ),
                ),
            )
        }
    }

    /** Parses a verse reference string and looks it up in the current reading language's DB. */
    private suspend fun lookupVerseRef(ref: String, state: ReaderUiState): VerseData? {
        val match = VERSE_REF_PARSE_REGEX.find(ref) ?: return null
        val bookFragment = match.groupValues[1].trim()
        val chapter = match.groupValues[2].toIntOrNull() ?: return null
        val verseNum = match.groupValues[3].toIntOrNull() ?: return null
        val book = state.books.firstOrNull { it.bookName.startsWith(bookFragment, ignoreCase = true) }
            ?: state.books.firstOrNull { it.bookName.contains(bookFragment, ignoreCase = true) }
            ?: return null
        return repository.getVerse(state.language, book.bookId, chapter, verseNum)
    }

    fun onDefinitionWordTapped(word: String) {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        val verse = state.verses.firstOrNull { it.numericVerseId == bubble.verseId } ?: return
        val cleaned = word.trim { it.isWhitespace() || it in ",.;:!?\"'“”¡¿()" }
        if (cleaned.isEmpty()) return
        _uiState.value = state.copy(chatBubble = bubble.copy(wordInfo = WordInfoState(cleaned, isLoading = true)))
        // The definition word is in the target language; translate it back to the source
        // language so the card shows the same cross-language "word · translation" pair as
        // a direct scripture-word tap does.
        fetchWordInfoAsync(verse, cleaned, bubble.bubbleTargetLanguage, bubble.bubbleTargetLanguage, state.language)
    }

    /**
     * The condensed bubble's explicit "Define" affordance — the only place a single verse-word's
     * pronunciation/definition lookup starts now (it used to fire automatically on selection).
     * Expands the bubble out of condensed mode and streams the definition in as it resolves.
     */
    fun onDefineWord() {
        val state = _uiState.value
        val bubble = state.chatBubble ?: return
        val word = bubble.selectedSingleWord ?: return
        val verse = state.verses.firstOrNull { it.numericVerseId == bubble.verseId } ?: return
        _uiState.value = state.copy(
            chatBubble = bubble.copy(isCondensed = false, wordInfo = WordInfoState(word, isLoading = true)),
        )
        fetchWordInfoAsync(verse, word, state.language, bubble.bubbleTargetLanguage, bubble.bubbleTargetLanguage)
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
                    inputSetSequence = bubble.inputSetSequence + 1,
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
        // Condensed mode is already the minimal presentation — nothing further to collapse.
        if (bubble.isMinimized || bubble.isCondensed) return
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
        val verse = state.verses.firstOrNull { it.numericVerseId == bubble.verseId } ?: return

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

        // This dropdown is now the only place "translate to" is chosen (the top bar's old
        // separate control is gone), so persist the pick as the app-wide default target
        // language too — future word taps elsewhere should keep using it, not silently reset.
        // The chapter's cached word-level translations are keyed by target language, so they
        // need a refetch too or a later plain word tap would show stale-language text.
        val apiKey = ApiKeys.geminiApiKey
        val myRequestId = ++liveCallRequestId
        _uiState.value = state.copy(
            targetLanguage = language,
            chatBubble = bubble.copy(
                bubbleTargetLanguage = language,
                initialIsLoading = true,
                initialTranslation = "Translating…",
                manualLanguageOverride = true,
            ),
        )
        viewModelScope.launch { reloadWordTranslations() }
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
        val verse = state.verses.firstOrNull { it.numericVerseId == bubble.verseId } ?: return
        val question = capWords(rawQuestion, MAX_FOLLOWUP_WORDS)
        val historyBeforeThisTurn = bubble.messages
        val messagesWithQuestion = historyBeforeThisTurn + ChatMessage(ChatRole.USER, question)

        _uiState.value = state.copy(
            chatBubble = clearWordInfo(
                bubble.copy(
                    messages = messagesWithQuestion, followUpInput = "", isSendingFollowUp = true,
                    queuedWordIndices = emptyList(), queuedResponseWords = emptyList(),
                    inputSetSequence = bubble.inputSetSequence + 1,
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
     *
     * [translationTargetLang] is the language the translation field should be rendered in — the
     * cross direction from [wordLanguage] so the card shows "word · translation" in two languages.
     */
    private fun fetchWordInfoAsync(
        verse: VerseData,
        word: String,
        wordLanguage: BibleLanguage,
        responseLanguage: BibleLanguage,
        translationTargetLang: BibleLanguage,
    ) {
        val myId = ++wordInfoRequestId
        val apiKey = ApiKeys.geminiApiKey
        if (apiKey == null) {
            val current = _uiState.value.chatBubble ?: return
            _uiState.value = _uiState.value.copy(chatBubble = current.copy(wordInfo = WordInfoState(word, isLoading = false)))
            return
        }
        val verseRef = "${verse.bookName} ${verse.chapter}:${verse.verse}"
        viewModelScope.launch {
            val result = verseChatClient.fetchWordInfo(
                apiKey, word, wordLanguage.displayName, responseLanguage.displayName,
                translationTargetLang.displayName, verseRef, verse.text,
            )
            if (myId != wordInfoRequestId) return@launch
            val current = _uiState.value.chatBubble ?: return@launch
            _uiState.value = _uiState.value.copy(
                chatBubble = result.fold(
                    onSuccess = { (pronunciation, definition, translation) ->
                        current.copy(wordInfo = WordInfoState(word, pronunciation, definition, translation, isLoading = false))
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

    private fun updateSelection(verseId: Long, range: IntRange) {
        // Starting a new word selection means the user has moved on from wherever a verse
        // search last landed them — clear that highlight rather than leaving it pulsing
        // somewhere off-screen. Cleared before capturing `state` so the copies below don't
        // resurrect the stale value.
        if (_uiState.value.highlightedVerseId != null) {
            _uiState.value = _uiState.value.copy(highlightedVerseId = null)
        }
        val state = _uiState.value
        val verse = state.verses.firstOrNull { it.numericVerseId == verseId } ?: return
        val tokens = VerseTokenizer.tokenize(verse.text)
        val clamped = range.first.coerceIn(0, tokens.lastIndex)..range.last.coerceIn(0, tokens.lastIndex)
        val wordsForVerse = state.wordTranslations[verseId].orEmpty().associateBy { it.wordIndex }
        val parts = clamped.map { wordsForVerse[it]?.translatedWord }
        val hasData = parts.all { it != null }

        val myRequestId = ++liveCallRequestId
        val openSeq = ++bubbleOpenSequenceCounter

        // A lone word now opens in *condensed* mode — just "word · translation" plus a Define
        // affordance. The pronunciation/definition lookup no longer fires automatically on
        // selection; it runs only when the user explicitly asks (onDefineWord). Any lookup
        // still in flight from a previous selection is invalidated either way.
        val singleWord: String? = if (clamped.first == clamped.last) tokens[clamped.first] else null
        wordInfoRequestId++

        if (hasData) {
            val text = parts.filterNotNull().distinctFromNeighbors().joinToString(" ")
            _uiState.value = state.copy(
                chatBubble = ChatBubbleState(
                    verseId, clamped, state.targetLanguage, text, initialHasData = true,
                    selectedSingleWord = singleWord, isCondensed = singleWord != null, openSequence = openSeq,
                ),
            )
            return
        }

        // These two books are the live-call experiments (no precomputed word data) — gated on
        // *this verse's* own book, not whatever book was last explicitly navigated to, since a
        // continuous scroll can carry the user into/out of either book without a fresh "load".
        if (verse.bookId == EXODUS_BOOK_ID) {
            val apiKey = ApiKeys.translateApiKey
            if (apiKey == null) {
                _uiState.value = state.copy(
                    chatBubble = ChatBubbleState(
                        verseId, clamped, state.targetLanguage,
                        "No Translation API key configured for this build.", initialHasData = false,
                        selectedSingleWord = singleWord, isCondensed = singleWord != null, openSequence = openSeq,
                    ),
                )
                return
            }
            val selectedWords = clamped.map { tokens[it] }
            _uiState.value = state.copy(
                chatBubble = ChatBubbleState(
                    verseId, clamped, state.targetLanguage, "Translating…", initialHasData = false, initialIsLoading = true,
                    selectedSingleWord = singleWord, isCondensed = singleWord != null, openSequence = openSeq,
                ),
            )
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

        // All remaining books (everything except Genesis precomputed data and Exodus Google Translate)
        // use Gemini live translate with verse context — same quality path as Leviticus used to be.
        val apiKey = ApiKeys.geminiApiKey
        if (apiKey == null) {
            _uiState.value = state.copy(
                chatBubble = ChatBubbleState(
                    verseId, clamped, state.targetLanguage,
                    "No Gemini API key configured for this build.", initialHasData = false,
                    selectedSingleWord = singleWord, isCondensed = singleWord != null, openSequence = openSeq,
                ),
            )
            return
        }
        val selectedText = clamped.joinToString(" ") { tokens[it] }
        _uiState.value = state.copy(
            chatBubble = ChatBubbleState(
                verseId, clamped, state.targetLanguage, "Translating…", initialHasData = false, initialIsLoading = true,
                selectedSingleWord = singleWord, isCondensed = singleWord != null, openSequence = openSeq,
            ),
        )
        viewModelScope.launch {
            val cached = liveTranslationCache.get(verse.numericVerseId, state.language, state.targetLanguage, clamped.first, clamped.last)
            if (cached != null) {
                applyInitialResult(myRequestId, Result.success(cached))
            } else {
                runGeminiLiveTranslate(myRequestId, verse, selectedText, apiKey, state.language, state.targetLanguage, clamped)
            }
        }
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
        highlightedVerseId: Long? = null,
    ) {
        _uiState.value = _uiState.value.copy(isLoading = true, chatBubble = null)
        val chapterCount = repository.getChapterCount(language, bookId).coerceAtLeast(1)
        val resolvedChapter = chapter.coerceIn(1, chapterCount)
        val verses = repository.getChapter(language, bookId, resolvedChapter)
        val wordTranslations = wordTranslationRepository.getChapterWordTranslations(language, targetLanguage, bookId, resolvedChapter)
        // A fresh explicit load (jump/search/language switch) resets the continuous-scroll
        // window back down to just this one chapter — ReaderUiState()'s field defaults already
        // reopen both pagination directions and clear any in-flight paging state.
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
            highlightedVerseId = highlightedVerseId,
            scrollToTopTrigger = _uiState.value.scrollToTopTrigger + 1,
        )
    }

    /** Resolves what comes immediately after (bookId, chapter) — next chapter, or chapter 1 of the next book. Null past Revelation. */
    private suspend fun nextChapterRef(language: BibleLanguage, bookId: Int, chapter: Int, books: List<BookInfo>): Pair<Int, Int>? {
        val chapterCount = repository.getChapterCount(language, bookId).coerceAtLeast(1)
        if (chapter < chapterCount) return bookId to (chapter + 1)
        val bookIndex = books.indexOfFirst { it.bookId == bookId }
        val nextBook = books.getOrNull(bookIndex + 1) ?: return null
        return nextBook.bookId to 1
    }

    /** Resolves what comes immediately before (bookId, chapter) — previous chapter, or the last chapter of the previous book. Null before Genesis 1. */
    private suspend fun prevChapterRef(language: BibleLanguage, bookId: Int, chapter: Int, books: List<BookInfo>): Pair<Int, Int>? {
        if (chapter > 1) return bookId to (chapter - 1)
        val bookIndex = books.indexOfFirst { it.bookId == bookId }
        val prevBook = books.getOrNull(bookIndex - 1) ?: return null
        val lastChapter = repository.getChapterCount(language, prevBook.bookId).coerceAtLeast(1)
        return prevBook.bookId to lastChapter
    }

    /**
     * Called by the UI once the scroll position nears the bottom of what's loaded — appends the
     * next chapter (crossing into the next book at a book's end) so scrolling reads as one
     * continuous stream instead of stopping dead at a chapter boundary.
     */
    fun onNearBottomOfList() {
        val state = _uiState.value
        if (state.isLoadingMoreBottom || !state.hasMoreBottom) return
        val last = state.verses.lastOrNull() ?: return
        _uiState.value = state.copy(isLoadingMoreBottom = true)
        viewModelScope.launch {
            val next = nextChapterRef(state.language, last.bookId, last.chapter, state.books)
            if (next == null) {
                _uiState.value = _uiState.value.copy(isLoadingMoreBottom = false, hasMoreBottom = false)
                return@launch
            }
            val (bookId, chapter) = next
            val newVerses = repository.getChapter(state.language, bookId, chapter)
            val newWordTranslations = wordTranslationRepository.getChapterWordTranslations(state.language, state.targetLanguage, bookId, chapter)
            val current = _uiState.value
            _uiState.value = current.copy(
                verses = current.verses + newVerses,
                wordTranslations = current.wordTranslations + newWordTranslations,
                isLoadingMoreBottom = false,
            )
        }
    }

    /** Same idea as [onNearBottomOfList], but prepending the previous chapter as the user scrolls up. */
    fun onNearTopOfList() {
        val state = _uiState.value
        if (state.isLoadingMoreTop || !state.hasMoreTop) return
        val first = state.verses.firstOrNull() ?: return
        _uiState.value = state.copy(isLoadingMoreTop = true)
        viewModelScope.launch {
            val prev = prevChapterRef(state.language, first.bookId, first.chapter, state.books)
            if (prev == null) {
                _uiState.value = _uiState.value.copy(isLoadingMoreTop = false, hasMoreTop = false)
                return@launch
            }
            val (bookId, chapter) = prev
            val newVerses = repository.getChapter(state.language, bookId, chapter)
            val newWordTranslations = wordTranslationRepository.getChapterWordTranslations(state.language, state.targetLanguage, bookId, chapter)
            val current = _uiState.value
            _uiState.value = current.copy(
                verses = newVerses + current.verses,
                wordTranslations = current.wordTranslations + newWordTranslations,
                isLoadingMoreTop = false,
                // Tells the UI exactly how many items just landed above the viewport, so it can
                // compensate the scroll offset by that many items and avoid a visible jump.
                pendingTopPrependCount = newVerses.size,
            )
        }
    }

    /** Called by the UI right after it has compensated the scroll offset for a top-prepend. */
    fun clearPendingTopPrepend() {
        if (_uiState.value.pendingTopPrependCount == 0) return
        _uiState.value = _uiState.value.copy(pendingTopPrependCount = 0)
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
    fun findBook(fragment: String): BookInfo? =
        books.firstOrNull { it.bookName.equals(fragment, ignoreCase = true) }
            ?: books.firstOrNull { it.bookName.startsWith(fragment, ignoreCase = true) }

    // Full "Book Chapter:Verse" (e.g. "Jn 3:16", "Genesis 5:3")
    Regex("""^\s*(.+?)\s+(\d+)\s*:\s*(\d+)\s*$""").find(query)?.let { m ->
        val (bookPart, chStr, vsStr) = m.destructured
        val book = findBook(bookPart.trim()) ?: return@let null
        val ch = chStr.toIntOrNull() ?: return@let null
        val vs = vsStr.toIntOrNull() ?: return@let null
        return Triple(book, ch, vs)
    }
    // "Book Chapter" without verse — navigate to chapter:1 (e.g. "Genesis 10", "Lev 6")
    Regex("""^\s*(.+?)\s+(\d+)\s*$""").find(query)?.let { m ->
        val (bookPart, chStr) = m.destructured
        val book = findBook(bookPart.trim()) ?: return@let null
        val ch = chStr.toIntOrNull() ?: return@let null
        return Triple(book, ch, 1)
    }
    // "Book" alone — navigate to chapter 1, verse 1
    val bookOnly = query.trim()
    if (bookOnly.isNotEmpty() && bookOnly.none { it.isDigit() || it == ':' }) {
        findBook(bookOnly)?.let { return Triple(it, 1, 1) }
    }
    return null
}

private fun List<String>.distinctFromNeighbors(): List<String> =
    fold(mutableListOf<String>()) { acc, item ->
        if (acc.lastOrNull() != item) acc.add(item)
        acc
    }
