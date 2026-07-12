package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    var showBookPicker by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        TextButton(onClick = { showBookPicker = true }) {
                            Text("${uiState.selectedBookName} ${uiState.selectedChapter}")
                        }
                    },
                    actions = {
                        TextButton(onClick = { showChapterPicker = true }) {
                            Text("Ch.")
                        }
                    },
                )
                LanguagePairSelector(
                    readingLanguage = uiState.language,
                    targetLanguage = uiState.targetLanguage,
                    onReadingSelected = viewModel::onLanguageSelected,
                    onTargetSelected = viewModel::onTargetLanguageSelected,
                )
            }
        },
    ) { padding ->
        // Verse text stays fully readable behind the glass study panel — no
        // backdrop blur — the panel's own translucency and shadow are enough
        // to read as "floating above" the content without obscuring it.
        val isBubbleOpen = uiState.chatBubble != null

        Box(Modifier.fillMaxSize()) {
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                VerseList(
                    uiState = uiState,
                    padding = padding,
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.18f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                // Tapping outside the panel collapses the conversation back to
                                // the word's original translation rather than closing the panel
                                // entirely — only the explicit "✕" fully dismisses it.
                                onClick = viewModel::onStartOver,
                            ),
                    ) {
                        ChatBubble(
                            verseLabel = verse?.let { "${it.bookName} ${it.chapter}:${it.verse}" } ?: "",
                            sourceLanguage = uiState.language,
                            bubble = bubble,
                            onClose = viewModel::onCloseBubble,
                            onStartOver = viewModel::onStartOver,
                            onLanguageChanged = viewModel::onBubbleLanguageChanged,
                            onInputChanged = viewModel::onFollowUpInputChanged,
                            onSend = viewModel::onSendFollowUp,
                            onChipTapped = viewModel::onChipTapped,
                            onResponseWordTapped = viewModel::onResponseWordTapped,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                ),
                        )
                    }
                }
            }
        }

        if (showBookPicker) {
            BookPickerDialog(
                books = uiState.books,
                onDismiss = { showBookPicker = false },
                onBookSelected = {
                    viewModel.onBookSelected(it)
                    showBookPicker = false
                },
            )
        }

        if (showChapterPicker) {
            ChapterPickerDialog(
                chapterCount = uiState.chapterCount,
                onDismiss = { showChapterPicker = false },
                onChapterSelected = {
                    viewModel.onChapterSelected(it)
                    showChapterPicker = false
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
    onSelectionStart: (Int, Int) -> Unit,
    onSelectionExtend: (Int, Int) -> Unit,
    onWordToggle: (Int, Int) -> Unit,
    onTranslateVerse: (VerseData) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
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
            )
        }
    }
}

@Composable
private fun BookPickerDialog(
    books: List<BookInfo>,
    onDismiss: () -> Unit,
    onBookSelected: (BookInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Select book") },
        text = {
            LazyColumn {
                items(books, key = { it.bookId }) { book ->
                    TextButton(onClick = { onBookSelected(book) }) {
                        Text(book.bookName)
                    }
                }
            }
        },
    )
}

@Composable
private fun ChapterPickerDialog(
    chapterCount: Int,
    onDismiss: () -> Unit,
    onChapterSelected: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Select chapter") },
        text = {
            LazyColumn {
                items((1..chapterCount).toList()) { chapter ->
                    TextButton(onClick = { onChapterSelected(chapter) }) {
                        Text("Chapter $chapter")
                    }
                }
            }
        },
    )
}
