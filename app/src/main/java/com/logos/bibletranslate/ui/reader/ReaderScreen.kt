package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import com.logos.bibletranslate.R
import com.logos.bibletranslate.ui.theme.AccentTheme
import kotlinx.coroutines.launch
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

    // Deliberately *no* auto-scroll-to-verse when a bubble opens: an animated scroll right as
    // a hold-to-select/drag gesture completes moved the scripture out from under the finger
    // still on screen, so a drag-to-select felt like it lost tracking mid-gesture. The panel
    // floats over the bottom of the screen regardless of where the verse lands, and the user
    // can still scroll manually (that background scrolling is untouched) if it ends up hidden.

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

    // NOTE: the navbar previously collapsed from an edge-to-edge rectangle into a floating
    // pill once scrolled (Insureit-style). Not needed for now — commented out rather than
    // deleted so it's easy to bring back later.
    // val isNavBarCollapsed by remember {
    //     derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 50 }
    // }

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
            // screen, exactly like the "Insureit" reference at scroll position 0. Retained as
            // a fixed shape for now — the scroll-triggered collapse into a floating pill
            // (corner radius/margin/padding animating together over 500ms ease-in-out) is
            // commented out below rather than deleted, in case it's wanted again later.
            // Floating-pill navbar: inset from the edges so the translucent bar reads as a
            // distinct glass object hovering over the scripture, rather than a flush chrome bar.
            // Shadow gives it lift; the large corner radius makes it feel lightweight. The pill
            // shrinks slightly (narrower margins → wider pill) when the settings drawer is open
            // so the expanded state looks intentional, not just taller.
            val horizontalMargin = 10.dp
            val verticalMargin = 6.dp
            val rowHorizontalPadding = 14.dp
            val rowVerticalPadding = 9.dp
            val navBarShape = RoundedCornerShape(24.dp)

            // Drag-down handle: pulling the little chevron down reveals a settings drawer
            // beneath the main nav row, up to maxPanelHeight; releasing snaps it fully open
            // or fully closed depending on which side of the halfway point it's on.
            val coroutineScope = rememberCoroutineScope()
            val density = LocalDensity.current
            // Just enough to reveal one row of controls — not a full drawer. Sized to
            // comfortably fit the enlarged logo in the middle of the row.
            val maxPanelHeight = 76.dp
            val panelHeight = remember { Animatable(0.dp, Dp.VectorConverter) }

            // Chevron rotates 180° when the settings drawer is open, giving a clear ↑/↓
            // indicator without any extra text or separate toggle.
            val chevronRotation by animateFloatAsState(
                targetValue = if (panelHeight.value >= maxPanelHeight / 2f) 180f else 0f,
                label = "chevronRotation",
            )

            // Press-down animation: the pill sinks 28 dp toward the thumb on any touch
            // and springs back on release. Enough to bring it into easy one-handed reach
            // without feeling like the bar is flying away.
            val navOffsetY = remember { Animatable(0f) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, navOffsetY.value.roundToInt()) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            coroutineScope.launch {
                                navOffsetY.animateTo(
                                    targetValue = with(density) { 28.dp.toPx() },
                                    animationSpec = spring(stiffness = 500f),
                                )
                            }
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                            coroutineScope.launch {
                                navOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 280f),
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalMargin, vertical = verticalMargin)
                        .shadow(elevation = 8.dp, shape = navBarShape)
                        .clip(navBarShape)
                        .border(
                            BorderStroke(0.7.dp, Color.White.copy(alpha = if (Glass.isDarkMode) 0.13f else 0.40f)),
                            navBarShape,
                        )
                        .background(Glass.navBarBrush()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // All three controls sit in a single Row and size to their own content.
                        // No fixed height on individual controls — vertical alignment comes from
                        // the Row (CenterVertically), which naturally aligns mismatched-height
                        // pills without requiring explicit height constraints on each child.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(horizontal = rowHorizontalPadding, vertical = rowVerticalPadding),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Book/chapter: frosted-glass pill, no visible border — the iOS 27
                            // approach where controls look pressed into the glass surface rather
                            // than layered on top of it. Box+clickable instead of TextButton
                            // so we fully own the background and shape without fighting Material.
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 128.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (Glass.isDarkMode) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.62f),
                                        RoundedCornerShape(50),
                                    )
                                    .clickable { showBookChapterPicker = true }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$displayedBookName $displayedChapter ▾",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Language picker: same liquid-glass treatment but tinted sky-blue.
                            CompactReadingLanguagePicker(
                                selected = uiState.language,
                                options = BibleLanguage.entries,
                                onSelected = viewModel::onLanguageSelected,
                            )
                            // Search field fills the remaining horizontal space — the pill
                            // stretches edge-to-edge inside the row rather than being a fixed
                            // narrow widget. Looks intentional rather than squished.
                            VerseSearchBar(
                                books = uiState.books,
                                onSubmit = viewModel::onVerseSearchSubmitted,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Settings reveal, driven by dragging or tapping the chevron below.
                        // Height-driven rather than AnimatedVisibility so it tracks the drag
                        // 1:1 while the finger is down, and only springs on release. Clipped
                        // to the same rounded pill shape as the outer container.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(panelHeight.value)
                                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .alpha((panelHeight.value / maxPanelHeight).coerceIn(0f, 1f)),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The gear now opens an actual settings menu (currently just
                                // the light/dark toggle, with room to grow) instead of being
                                // the toggle itself.
                                var showSettingsMenu by remember { mutableStateOf(false) }
                                Box {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "App settings",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable { showSettingsMenu = true },
                                    )
                                    DropdownMenu(
                                        expanded = showSettingsMenu,
                                        onDismissRequest = { showSettingsMenu = false },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("Appearance", style = MaterialTheme.typography.bodyMedium)
                                            Spacer(Modifier.width(4.dp))
                                            DarkModeToggleSwitch(
                                                isDarkMode = Glass.isDarkMode,
                                                onToggle = { Glass.toggleDarkMode() },
                                            )
                                        }
                                    }
                                }
                                AccentMarble(theme = AccentTheme.SkyDeep)
                                AccentMarble(theme = AccentTheme.SkyDeepPurple)
                                // The Jesus-with-people logo — enlarged further so it reads
                                // clearly instead of shrinking into an indistinct blob at icon
                                // size.
                                Image(
                                    painter = painterResource(R.drawable.logo_jesus_group),
                                    contentDescription = null,
                                    modifier = Modifier.size(66.dp),
                                )
                                AccentMarble(theme = AccentTheme.LightDarkPurple)
                                AccentMarble(theme = AccentTheme.LightDarkRed)
                                Icon(
                                    imageVector = Icons.Filled.BookmarkBorder,
                                    contentDescription = "Saved verses and words",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp).clickable { /* TODO: saved verses/words review */ },
                                )
                            }
                        }

                        // Drag/tap handle: chevron at the bottom of the pill. Drag it down to
                        // reveal the settings drawer 1:1 with the finger; tap it to snap open
                        // or closed; releasing a drag snaps to whichever half the drawer is on.
                        // Chevron rotates 180° when the drawer is open (↓ → ↑).
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (panelHeight.value >= maxPanelHeight / 2f) "Close settings" else "Open settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(bottom = 4.dp)
                                    .size(20.dp)
                                    .rotate(chevronRotation)
                                    .clickable {
                                        val target = if (panelHeight.value < maxPanelHeight / 2f) maxPanelHeight else 0.dp
                                        coroutineScope.launch { panelHeight.animateTo(target, spring(dampingRatio = 0.8f)) }
                                    }
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                val deltaDp = with(density) { dragAmount.toDp() }
                                                val newHeight = (panelHeight.value + deltaDp).coerceIn(0.dp, maxPanelHeight)
                                                coroutineScope.launch { panelHeight.snapTo(newHeight) }
                                            },
                                            onDragEnd = {
                                                val target = if (panelHeight.value > maxPanelHeight / 2f) maxPanelHeight else 0.dp
                                                coroutineScope.launch {
                                                    panelHeight.animateTo(target, spring(dampingRatio = 0.8f))
                                                }
                                            },
                                        )
                                    },
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
            // Also shown while a verse is triple-tap-locked even without an open bubble (e.g.
            // after the panel was closed) so a tap on blank space can still release the lock.
            if (isBubbleOpen || uiState.lockedVerseId != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                viewModel.onBubbleOutsideTap()
                                viewModel.onOutsideAllVersesTap()
                            },
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
                    onGestureDown = viewModel::onVerseGestureDown,
                    onVerseLocked = viewModel::lockVerse,
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
                        onDefine = viewModel::onDefineWord,
                        onLanguageChanged = viewModel::onBubbleLanguageChanged,
                        onInputChanged = viewModel::onFollowUpInputChanged,
                        onSend = viewModel::onSendFollowUp,
                        onChipTapped = viewModel::onChipTapped,
                        onResponseWordTapped = viewModel::onResponseWordTapped,
                        onDefinitionWordTapped = viewModel::onDefinitionWordTapped,
                        // Pad the top so the bubble's BoxWithConstraints receives the correct
                    // available height — without this the 70% cap is computed against the
                    // full screen and the panel can slide up behind the floating navbar.
                    modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
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
    onGestureDown: (Long) -> Unit,
    onVerseLocked: (Long) -> Unit,
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
                    // Condensed bubbles don't flip taps into toggle-the-question mode — there's
                    // no visible input to autofill yet, so taps keep their normal gesture rules.
                    bubbleOpenForThisVerse = bubbleForThisVerse?.isCondensed == false,
                    onSelectionStart = { wordIndex -> onSelectionStart(verse.numericVerseId, wordIndex) },
                    onSelectionExtend = { wordIndex -> onSelectionExtend(verse.numericVerseId, wordIndex) },
                    onWordToggle = { wordIndex -> onWordToggle(verse.numericVerseId, wordIndex) },
                    isHighlighted = uiState.highlightedVerseId == verse.numericVerseId,
                    isLocked = uiState.lockedVerseId == verse.numericVerseId,
                    onGestureDown = { onGestureDown(verse.numericVerseId) },
                    onVerseLocked = { onVerseLocked(verse.numericVerseId) },
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
 * A small circular "marble" swatch showing an [AccentTheme]'s gradient — tapping it switches
 * the app's accent color everywhere (buttons, glow ring, language pill) since [Glass] holds
 * the selected theme as Compose state. The currently-selected theme gets a bright ring so it
 * reads as "this one is active" among the options.
 */
@Composable
private fun AccentMarble(theme: AccentTheme, size: Dp = 22.dp) {
    val isSelected = Glass.selectedAccentTheme == theme
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Brush.linearGradient(colors = theme.colors))
            .then(
                if (isSelected) {
                    Modifier.border(BorderStroke(1.6.dp, MaterialTheme.colorScheme.onSurface), androidx.compose.foundation.shape.CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable { Glass.selectAccentTheme(theme) },
    )
}

/**
 * Apple-style light/dark toggle: a pill track with a sliding thumb, a sun glyph on the light
 * side and a moon on the dark side. Whichever side is active "lights up" (sun turns bright
 * yellow when in light mode, moon turns a cool blue-white when in dark mode) while the inactive
 * glyph stays dim — the toggle reads its own state at a glance instead of needing a label.
 */
@Composable
private fun DarkModeToggleSwitch(isDarkMode: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val trackWidth = 52.dp
    val trackHeight = 28.dp
    val thumbSize = 22.dp
    val thumbInset = 3.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (isDarkMode) trackWidth - thumbSize - thumbInset else thumbInset,
        label = "themeToggleThumb",
    )
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(50))
            .background(if (isDarkMode) Color(0xFF161A33) else Color(0xFFBFE3FA))
            .clickable { onToggle() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.LightMode,
                contentDescription = null,
                tint = if (!isDarkMode) Color(0xFFFFC107) else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp),
            )
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = null,
                tint = if (isDarkMode) Color(0xFFE3E9FF) else Color.Black.copy(alpha = 0.2f),
                modifier = Modifier.size(14.dp),
            )
        }
        Box(
            modifier = Modifier
                .padding(top = thumbInset)
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
                .shadow(1.dp, CircleShape),
        )
    }
}

/**
 * Smart verse search with inline greyed hints and progressive suggestions.
 *
 * Typing phases:
 *  • Letters only      → suggests matching book names (full name, prefix match)
 *  • "Book " (space)   → inline hint "5 · 14" shows chapter range; dropdown closes
 *  • "Book 5"          → inline hint ":1 · 22" shows verse range; single-tap chip to go
 *  • "Book 5:3"        → ready to submit
 *  • "Book 5" submit   → navigates to chapter 5, verse 1
 *  • "Genesis" submit  → navigates to Genesis 1:1
 *
 * Focus fix: FocusRequester re-grabs the text field after the dropdown appears so
 * the system soft keyboard stays open and typing isn't interrupted.
 */
@Composable
private fun VerseSearchBar(
    books: List<BookInfo>,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isDark = Glass.isDarkMode
    val fieldTextColor = if (isDark) Color.White.copy(alpha = 0.90f)
                         else MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.labelSmall

    // ── Parse query into (bookFragment, chapterFragment?, colonSeen) ──────────
    data class Parsed(
        val bookFrag: String,
        val chapterFrag: String?,   // digits after last space before ':'
        val verseFrag: String?,     // digits after ':'
    )
    val trimmed = query.trim()
    val parsed: Parsed = remember(trimmed) {
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val before = trimmed.substring(0, colonIdx).trim()
            val after  = trimmed.substring(colonIdx + 1).trim()
            val spIdx  = before.lastIndexOf(' ')
            if (spIdx > 0 && before.substring(spIdx + 1).all { it.isDigit() })
                Parsed(before.substring(0, spIdx), before.substring(spIdx + 1), after)
            else
                Parsed(before, null, after)
        } else {
            val spIdx = trimmed.lastIndexOf(' ')
            if (spIdx > 0 && trimmed.substring(spIdx + 1).all { it.isDigit() })
                Parsed(trimmed.substring(0, spIdx), trimmed.substring(spIdx + 1), null)
            else
                Parsed(trimmed, null, null)
        }
    }

    // ── Book suggestions: shown while still in the book-name phase ────────────
    val bookSuggestions: List<BookInfo> = remember(parsed, books) {
        if (parsed.chapterFrag != null || parsed.bookFrag.isEmpty()) emptyList()
        else books.filter { it.bookName.startsWith(parsed.bookFrag, ignoreCase = true) }
                  .sortedBy { it.bookName }
                  .take(7)
    }

    // ── Matched book (exact then prefix) — used for hints ────────────────────
    val matchedBook: BookInfo? = remember(parsed, books) {
        val f = parsed.bookFrag.ifEmpty { return@remember null }
        books.firstOrNull { it.bookName.equals(f, ignoreCase = true) }
            ?: books.firstOrNull { it.bookName.startsWith(f, ignoreCase = true) }
    }

    // ── Greyed inline suffix rendered BEHIND the BasicTextField ───────────────
    // Because BasicTextField draws its own opaque text on top, only the suffix
    // AFTER what the user has typed shows through as grey — an iOS-style hint.
    val hintSuffix: String = remember(query, trimmed, matchedBook, parsed) {
        when {
            trimmed.isEmpty() -> ""
            // Book matched, no chapter started, no trailing space → hint the chapter
            matchedBook != null && parsed.chapterFrag == null && !query.endsWith(" ") -> " [ch]"
            // Chapter digits present, no colon yet → hint the verse
            matchedBook != null && parsed.chapterFrag != null && parsed.verseFrag == null
                    && !query.endsWith(":") -> ":[vs]"
            else -> ""
        }
    }

    // ── Re-grab focus after dropdown opens so keyboard stays up ──────────────
    LaunchedEffect(bookSuggestions.size) {
        if (bookSuggestions.isNotEmpty()) {
            kotlinx.coroutines.delay(60)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(
                    if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.62f),
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = if (isDark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Box(Modifier.weight(1f)) {
                // Placeholder / inline hint layer — drawn BEHIND BasicTextField
                when {
                    query.isEmpty() ->
                        Text("Genesis 5 · Jn 3:16", style = textStyle,
                             color = fieldTextColor.copy(alpha = 0.40f), maxLines = 1)
                    hintSuffix.isNotEmpty() ->
                        Text(query + hintSuffix, style = textStyle,
                             color = fieldTextColor.copy(alpha = 0.35f), maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = textStyle.copy(color = fieldTextColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSubmit(query)
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { hasFocus = it.isFocused },
                )
            }
        }

        // Book-name suggestion chips — only while in the book-name phase
        DropdownMenu(
            expanded = bookSuggestions.isNotEmpty() && hasFocus,
            onDismissRequest = { /* dismissed naturally when chapter digit added */ },
        ) {
            bookSuggestions.forEach { book ->
                DropdownMenuItem(
                    text = { Text(book.bookName) },
                    onClick = {
                        // Append a space so the user can immediately type a chapter number
                        query = "${book.bookName} "
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
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
    // Liquid-glass tinted pill: skyBlue at reduced opacity so the glass background
    // shows through, consistent with the frosted feel of the other controls.
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Glass.skyBlue.copy(alpha = 0.82f), RoundedCornerShape(50))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
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

