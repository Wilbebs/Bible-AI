package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.logos.bibletranslate.data.BibleLanguage
import com.logos.bibletranslate.data.BibleRepository
import com.logos.bibletranslate.data.BookInfo
import com.logos.bibletranslate.data.GeminiLiveTranslateClient
import com.logos.bibletranslate.data.GoogleTranslateLiveClient
import com.logos.bibletranslate.data.LiveTranslationCache
import com.logos.bibletranslate.data.VerseChatCache
import com.logos.bibletranslate.data.VerseChatClient
import com.logos.bibletranslate.data.VerseData
import com.logos.bibletranslate.data.VerseTokenizer
import com.logos.bibletranslate.R
import com.logos.bibletranslate.data.WordTranslationRepository
import com.logos.bibletranslate.ui.theme.Glass
import com.logos.bibletranslate.ui.theme.Sparkle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    repository: BibleRepository,
    wordTranslationRepository: WordTranslationRepository,
    liveTranslateClient: GeminiLiveTranslateClient,
    googleTranslateClient: GoogleTranslateLiveClient,
    liveTranslationCache: LiveTranslationCache,
    verseChatClient: VerseChatClient,
    verseChatCache: VerseChatCache,
) {
    val viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModelFactory(
            repository, wordTranslationRepository, liveTranslateClient, googleTranslateClient,
            liveTranslationCache, verseChatClient, verseChatCache,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()

    var showBookChapterPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // A verse search's landing spot: scroll it into view, then let the pulse have the
    // stage for a while before clearing itself (a new selection also clears it early).
    LaunchedEffect(uiState.highlightedVerse, uiState.verses) {
        val target = uiState.highlightedVerse ?: return@LaunchedEffect
        val index = uiState.verses.indexOfFirst { it.verse == target }
        if (index >= 0) listState.animateScrollToItem(index)
        delay(6000)
        viewModel.clearHighlightedVerse()
    }

    Scaffold(
        topBar = {
            // A compact custom bar instead of Material3's TopAppBar, which enforces a
            // ~64dp minimum height on its own — between that and the language row below,
            // it was eating a large chunk of the screen before any verse text appeared.
            Surface(tonalElevation = 2.dp) {
                Column {
                    // Search-bar/nav-pill row height, standardized so the logo can be sized
                    // exactly 1.6x it and the two flanking controls read as vertically balanced
                    // against it.
                    val navRowHeight = 32.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Single fluid book→chapter entry point (replaces the separate "Ch."
                        // button — chapter navigation now lives inside the same picker), balanced
                        // against the search bar on the other side of the centered logo.
                        TextButton(
                            onClick = { showBookChapterPicker = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(navRowHeight)
                                .clip(RoundedCornerShape(50))
                                .border(BorderStroke(1.dp, Glass.buttonBrush()), RoundedCornerShape(50)),
                        ) {
                            Text(
                                "${uiState.selectedBookName} ${uiState.selectedChapter} ▾",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                        }
                        // Center logo — sized relative to the flanking controls (~1.6x their
                        // height) so it reads as the visual anchor between them.
                        Image(
                            painter = painterResource(R.drawable.logo_jesus_group),
                            contentDescription = null,
                            modifier = Modifier
                                .height(navRowHeight * 1.6f)
                                .padding(horizontal = 6.dp),
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            VerseSearchBar(
                                books = uiState.books,
                                onSubmit = viewModel::onVerseSearchSubmitted,
                                modifier = Modifier.height(navRowHeight),
                            )
                        }
                    }
                    LanguagePairSelector(
                        readingLanguage = uiState.language,
                        targetLanguage = uiState.targetLanguage,
                        onReadingSelected = viewModel::onLanguageSelected,
                        onTargetSelected = viewModel::onTargetLanguageSelected,
                    )
                }
            }
        },
    ) { padding ->
        // Verse text stays fully readable behind the glass study panel — no
        // backdrop blur — the panel's own translucency and shadow are enough
        // to read as "floating above" the content without obscuring it.
        val isBubbleOpen = uiState.chatBubble != null

        Box(Modifier.fillMaxSize()) {
            // Outside-tap-to-minimize catcher: placed *behind* the verse list (lowest z) so any
            // tap that actually lands on verse text is handled there first — the scripture stays
            // fully interactable (scrollable, tappable, selectable) while the bubble is open. This
            // only fires for taps that land somewhere neither the verse list nor the panel itself
            // handles (e.g. blank space below the last verse).
            if (isBubbleOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = viewModel::onBubbleOutsideTap,
                        ),
                )
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                VerseList(
                    uiState = uiState,
                    padding = padding,
                    listState = listState,
                    onSelectionStart = viewModel::onSelectionStart,
                    onSelectionExtend = viewModel::onSelectionExtend,
                    onWordToggle = viewModel::onVerseWordTapped,
                    onTranslateVerse = viewModel::onTranslateVerseRequested,
                )
            }

            // Keep the last non-null bubble around through the exit animation
            // so AnimatedVisibility has content to fade/scale out instead of
            // vanishing instantly when the state flips to null.
            var lastBubble by remember { mutableStateOf<ChatBubbleState?>(null) }
            LaunchedEffect(uiState.chatBubble) {
                uiState.chatBubble?.let { lastBubble = it }
            }

            AnimatedVisibility(
                visible = isBubbleOpen,
                enter = fadeIn(tween(220)) + scaleIn(tween(280), initialScale = 0.92f),
                exit = fadeOut(tween(180)) + scaleOut(tween(200), targetScale = 0.95f),
            ) {
                val bubble = lastBubble
                if (bubble != null) {
                    val verse = uiState.verses.firstOrNull { it.verse == bubble.verseNumber }
                    // No scrim/darkening, and no full-screen click surface here — this box is
                    // sized to the panel's own footprint (bottom-aligned, wrap content) via
                    // ChatBubble's internal BoxWithConstraints, so it never blocks scripture taps
                    // outside its own visible bounds (§5).
                    ChatBubble(
                        verseLabel = verse?.let { "${it.bookName} ${it.chapter}:${it.verse}" } ?: "",
                        sourceLanguage = uiState.language,
                        bubble = bubble,
                        onClose = viewModel::onCloseBubble,
                        onStartOver = viewModel::onStartOver,
                        onExpand = viewModel::onExpandBubble,
                        onLanguageChanged = viewModel::onBubbleLanguageChanged,
                        onInputChanged = viewModel::onFollowUpInputChanged,
                        onSend = viewModel::onSendFollowUp,
                        onChipTapped = viewModel::onChipTapped,
                        onResponseWordTapped = viewModel::onResponseWordTapped,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (showBookChapterPicker) {
            BookChapterPickerDialog(
                books = uiState.books,
                getChapterCount = viewModel::chapterCountFor,
                onDismiss = { showBookChapterPicker = false },
                onNavigate = { bookId, chapter ->
                    viewModel.onBookAndChapterSelected(bookId, chapter)
                    showBookChapterPicker = false
                },
            )
        }

        uiState.verseDialog?.let { data ->
            VerseTranslateDialog(data = data, onDismiss = viewModel::dismissVerseDialog)
        }
    }
}

@Composable
private fun VerseList(
    uiState: ReaderUiState,
    padding: PaddingValues,
    listState: LazyListState,
    onSelectionStart: (Int, Int) -> Unit,
    onSelectionExtend: (Int, Int) -> Unit,
    onWordToggle: (Int, Int) -> Unit,
    onTranslateVerse: (VerseData) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(padding),
        // Extra breathing room on the left specifically — Android's edge-swipe-back
        // gesture zone sits right at the screen edge, and verse text starting flush
        // against it means a normal reading tap/swipe near the margin can trigger
        // the system back gesture instead of selecting a word.
        contentPadding = PaddingValues(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        items(uiState.verses, key = { it.verseId }) { verse ->
            val tokens = remember(verse.verseId) { VerseTokenizer.tokenize(verse.text) }
            val bubbleForThisVerse = uiState.chatBubble?.takeIf { it.verseNumber == verse.verse }
            val selectedIndices = remember(bubbleForThisVerse) {
                bubbleForThisVerse?.let { it.wordRange.toSet() + it.queuedWordIndices.toSet() } ?: emptySet()
            }
            VerseRow(
                verse = verse,
                tokens = tokens,
                selectedIndices = selectedIndices,
                bubbleOpenForThisVerse = bubbleForThisVerse != null,
                onSelectionStart = { wordIndex -> onSelectionStart(verse.verse, wordIndex) },
                onSelectionExtend = { wordIndex -> onSelectionExtend(verse.verse, wordIndex) },
                onWordToggle = { wordIndex -> onWordToggle(verse.verse, wordIndex) },
                onTranslateVerse = { onTranslateVerse(verse) },
                isHighlighted = uiState.highlightedVerse == verse.verse,
            )
        }
    }
}

/**
 * A single fluid picker: pick a book, then pick a chapter for it — all in one dialog instead
 * of two separate ones, with a "Back" step between them. Chapter counts are fetched lazily
 * per book (only the current book's count is already known client-side).
 */
@Composable
private fun BookChapterPickerDialog(
    books: List<BookInfo>,
    getChapterCount: suspend (Int) -> Int,
    onDismiss: () -> Unit,
    onNavigate: (bookId: Int, chapter: Int) -> Unit,
) {
    var selectedBook by remember { mutableStateOf<BookInfo?>(null) }
    var chapterCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(selectedBook) {
        val book = selectedBook
        chapterCount = if (book != null) getChapterCount(book.bookId) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            val book = selectedBook
            if (book != null) {
                TextButton(onClick = { selectedBook = null }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        title = { Text(selectedBook?.let { "${it.bookName} — chapter" } ?: "Select book") },
        text = {
            val book = selectedBook
            val count = chapterCount
            when {
                book == null -> LazyColumn {
                    items(books, key = { it.bookId }) { b ->
                        TextButton(onClick = { selectedBook = b }, modifier = Modifier.fillMaxWidth()) {
                            Text(b.bookName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                count == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> ChapterGrid(count = count, onChapterSelected = { chapter -> onNavigate(book.bookId, chapter) })
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterGrid(count: Int, onChapterSelected: (Int) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FlowRow {
            (1..count).forEach { chapter ->
                Surface(
                    onClick = { onChapterSelected(chapter) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Text(
                        "$chapter",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Exact verse search — "[Book] [chapter]:[verse]" — with book-name autosuggest while the
 * book-name portion is being typed. Submitting (search IME action) navigates straight to
 * that verse and hands off to the highlight/scroll pipeline in [ReaderScreen].
 */
@Composable
private fun VerseSearchBar(
    books: List<BookInfo>,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(84.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            "Jn 3:16",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { text ->
                            query = text
                            expanded = verseSearchBookSuggestions(text, books).isNotEmpty()
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            expanded = false
                            onSubmit(query)
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val suggestions = verseSearchBookSuggestions(query, books)
        DropdownMenu(expanded = expanded && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }) {
            suggestions.forEach { book ->
                DropdownMenuItem(
                    text = { Text(book.bookName) },
                    onClick = {
                        query = "${book.bookName} "
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Book-name autosuggest only kicks in while the book-name portion is being typed (before any digit/colon). */
private fun verseSearchBookSuggestions(query: String, books: List<BookInfo>): List<BookInfo> {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isDigit() || it == ':' }) return emptyList()
    return books.filter { it.bookName.startsWith(trimmed, ignoreCase = true) }.take(6)
}
