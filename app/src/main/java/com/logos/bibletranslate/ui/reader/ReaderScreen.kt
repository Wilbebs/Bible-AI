package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    LaunchedEffect(uiState.highlightedVerseId, uiState.verses) {
        val target = uiState.highlightedVerseId ?: return@LaunchedEffect
        val index = uiState.verses.indexOfFirst { it.numericVerseId == target }
        if (index >= 0) listState.animateScrollToItem(index)
        delay(6000)
        viewModel.clearHighlightedVerse()
    }

    // The study bubble panel floats over the bottom ~40-70% of the screen, so the verse it's
    // attached to must never end up rendered underneath it. Scrolling that verse to the very
    // top of the list whenever a *new* bubble opens (keyed only on which verse — not on every
    // bubble state update, e.g. follow-up typing) guarantees it stays fully visible above the
    // panel, regardless of where it happened to be on screen when tapped.
    LaunchedEffect(uiState.chatBubble?.verseId) {
        val verseId = uiState.chatBubble?.verseId ?: return@LaunchedEffect
        val index = uiState.verses.indexOfFirst { it.numericVerseId == verseId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    // Continuous cross-chapter scroll (§ pagination): watched off the same listState the
    // LazyColumn uses, so both loading-more-at-the-edge and the "which chapter is on screen
    // right now" label below react to the same source of truth. Guarded internally by the
    // ViewModel's isLoadingMore*/hasMore* flags, so firing on every scroll tick is harmless.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        }.collect { (firstIndex, lastIndex) ->
            if (firstIndex <= 3) viewModel.onNearTopOfList()
            if (lastIndex != null && lastIndex >= uiState.verses.size - 4) viewModel.onNearBottomOfList()
        }
    }

    // Prepending chapters above the current scroll position would otherwise yank the visible
    // content downward (LazyColumn keeps the same *index* on screen, which now points at a
    // different item) — compensate once, right after a prepend lands, by scrolling forward
    // exactly as many items as were just added, keeping the same verse pinned in place.
    LaunchedEffect(uiState.pendingTopPrependCount) {
        val prependedCount = uiState.pendingTopPrependCount
        if (prependedCount > 0) {
            listState.scrollToItem(listState.firstVisibleItemIndex + prependedCount, listState.firstVisibleItemScrollOffset)
            viewModel.clearPendingTopPrepend()
        }
    }

    // The book/chapter button reflects whatever chapter is actually scrolled into view, not
    // just wherever the reader was explicitly navigated to — necessary now that scrolling can
    // carry the user across chapter (and book) boundaries without an explicit jump.
    val topVisibleVerse by remember { derivedStateOf { uiState.verses.getOrNull(listState.firstVisibleItemIndex) } }
    val displayedBookName = topVisibleVerse?.bookName ?: uiState.selectedBookName
    val displayedChapter = topVisibleVerse?.chapter ?: uiState.selectedChapter

    // Collapses the navbar from an edge-to-edge rectangle into a content-hugging pill once the
    // list has scrolled down more than a few dozen pixels — mirrors the Insureit navbar pattern.
    // firstVisibleItemScrollOffset alone would reset to 0 every time a new item becomes the
    // first visible one, so any further scroll (index > 0) also counts as "scrolled".
    val isNavBarCollapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 50 }
    }

    Scaffold(
        topBar = {
            // A compact custom bar instead of Material3's TopAppBar, which enforces a
            // ~64dp minimum height on its own — between that and the language row below,
            // it was eating a large chunk of the screen before any verse text appeared.
            // Built as an actual navbar card: one glass container holding every nav control,
            // with a soft rounded bottom edge and a bright rim so it reads as a distinct panel
            // rather than a bare row floating over the verse text. More see-through than the
            // study bubble's own panel (Glass.navBarBrush vs Glass.panelBrush) since scripture
            // keeps scrolling directly behind it — the buttons/dropdowns inside keep their own
            // solid fills so they stay legible regardless.
            //
            // Starts as a full-width rectangle (square corners) flush with the top of the
            // screen — a "glass-nav" bar per the web reference. Past the scroll threshold
            // above, it morphs into a small floating pill sized to just fit its buttons:
            // corner radius, outer margin, and inner padding all animate together over 500ms
            // ease-in-out (matching the web spec's `transition-all duration-500 ease-in-out`),
            // while animateContentSize() smoothly interpolates the width/height change
            // (fillMaxWidth -> wrapContentWidth) in lockstep.
            val navBarTransitionSpec = tween<Dp>(durationMillis = 500, easing = FastOutSlowInEasing)
            val cornerRadius by animateDpAsState(if (isNavBarCollapsed) 999.dp else 0.dp, navBarTransitionSpec, label = "navBarCorner")
            val outerMargin by animateDpAsState(if (isNavBarCollapsed) 12.dp else 0.dp, navBarTransitionSpec, label = "navBarMargin")
            val rowHorizontalPadding by animateDpAsState(if (isNavBarCollapsed) 12.dp else 16.dp, navBarTransitionSpec, label = "navBarPaddingH")
            val rowVerticalPadding by animateDpAsState(if (isNavBarCollapsed) 8.dp else 12.dp, navBarTransitionSpec, label = "navBarPaddingV")
            val navBarShape = RoundedCornerShape(cornerRadius)

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = outerMargin, vertical = outerMargin)
                        .then(if (isNavBarCollapsed) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
                        .animateContentSize(animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing))
                        .shadow(elevation = 6.dp, shape = navBarShape)
                        .clip(navBarShape)
                        .background(Glass.navBarBrush())
                        .border(BorderStroke(0.8.dp, Glass.navBarBorderBrush()), navBarShape),
                ) {
                    Column {
                        // Search-bar/nav-pill row height — the controls are vertically balanced
                        // against this shared height.
                        val navRowHeight = 32.dp
                        Row(
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(horizontal = rowHorizontalPadding, vertical = rowVerticalPadding),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Single fluid book→chapter entry point (replaces the separate "Ch."
                            // button — chapter navigation now lives inside the same picker).
                            // Sized to its own content (capped so a long book name can't crowd
                            // out the language pill next to it) rather than stretched to fill
                            // the whole left cluster — the two pills sitting snug against each
                            // other, both hugging the left edge, reads far more deliberate than
                            // one control ballooning to eat all the leftover space.
                            TextButton(
                                onClick = { showBookChapterPicker = true },
                                modifier = Modifier
                                    .height(navRowHeight)
                                    .widthIn(max = 132.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(50))
                                    .border(BorderStroke(1.dp, Glass.buttonBrush()), RoundedCornerShape(50)),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) {
                                Text(
                                    "$displayedBookName $displayedChapter ▾",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            CompactReadingLanguagePicker(
                                selected = uiState.language,
                                options = BibleLanguage.entries,
                                onSelected = viewModel::onLanguageSelected,
                                modifier = Modifier.height(navRowHeight),
                            )
                            VerseSearchBar(
                                books = uiState.books,
                                onSubmit = viewModel::onVerseSearchSubmitted,
                                modifier = Modifier.height(navRowHeight),
                            )
                        }
                    }
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
                    val verse = uiState.verses.firstOrNull { it.numericVerseId == bubble.verseId }
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
                        onDefinitionWordTapped = viewModel::onDefinitionWordTapped,
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
    onSelectionStart: (Long, Int) -> Unit,
    onSelectionExtend: (Long, Int) -> Unit,
    onWordToggle: (Long, Int) -> Unit,
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
            val bubbleForThisVerse = uiState.chatBubble?.takeIf { it.verseId == verse.numericVerseId }
            val selectedIndices = remember(bubbleForThisVerse) {
                bubbleForThisVerse?.let { it.wordRange.toSet() + it.queuedWordIndices.toSet() } ?: emptySet()
            }
            Column {
                // Reading now flows continuously across chapter (and book) boundaries, so a
                // small inline heading at the start of each new chapter is the only orientation
                // cue left — there's no longer a hard stop that otherwise made this obvious.
                if (verse.verse == 1) {
                    Text(
                        "${verse.bookName} ${verse.chapter}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                VerseRow(
                    verse = verse,
                    tokens = tokens,
                    selectedIndices = selectedIndices,
                    bubbleOpenForThisVerse = bubbleForThisVerse != null,
                    onSelectionStart = { wordIndex -> onSelectionStart(verse.numericVerseId, wordIndex) },
                    onSelectionExtend = { wordIndex -> onSelectionExtend(verse.numericVerseId, wordIndex) },
                    onWordToggle = { wordIndex -> onWordToggle(verse.numericVerseId, wordIndex) },
                    onTranslateVerse = { onTranslateVerse(verse) },
                    isHighlighted = uiState.highlightedVerseId == verse.numericVerseId,
                )
            }
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

/**
 * Compact "reading language" pill for the top bar — shrunk to sit right next to the
 * book/chapter picker at the same height, showing just the language code (no separate
 * "Reading" label; there's no room for it at this size, and it's the only language control
 * left in the top bar so context makes it clear). "Translate to" no longer lives up here —
 * it's chosen per-conversation via the study bubble's own language dropdown instead.
 */
@Composable
private fun CompactReadingLanguagePicker(
    selected: BibleLanguage,
    options: List<BibleLanguage>,
    onSelected: (BibleLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                // Solid light-blue fill — no gradient — so it reads as one flat pill against
                // the frosted (translucent) nav bar behind it.
                .background(Glass.skyBlue, RoundedCornerShape(50))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.code.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Reading language",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = {
                        onSelected(language)
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
