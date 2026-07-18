package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.logos.bibletranslate.data.BibleLanguage
import com.logos.bibletranslate.data.ChatMessage
import com.logos.bibletranslate.data.ChatRole
import com.logos.bibletranslate.data.VerseTokenizer
import com.logos.bibletranslate.ui.theme.Glass
import com.logos.bibletranslate.ui.theme.Sparkle
import com.logos.bibletranslate.ui.theme.geminiGlowBorder
import com.logos.bibletranslate.ui.theme.rememberTypewriterProgress
import kotlinx.coroutines.delay

/**
 * Follow-up placeholder pool — cycles automatically every [SUGGESTION_CYCLE_MILLIS] while the
 * bubble is open and the input is untouched, so the user sees a variety of things they could ask
 * rather than only ever "translate to X" (which used to be the whole pool). Kept short — a
 * couple of words each, like the "Translate to X" entries — so nothing gets truncated in the
 * single-line placeholder slot. One list per bubble reply language, since these are suggested
 * *questions* the user could ask in that language, not the reading text itself.
 */
private val FOLLOWUP_SUGGESTIONS_EN = listOf(
    "Translate to Hebrew",
    "Translate to Greek",
    "Translate to Aramaic",
    "Translate to Latin",
    "Verse deep-dive",
    "Historical context",
    "Explore cross-references",
    "Key word breakdown",
    "Scholarly views",
    "Modern application",
)

private val FOLLOWUP_SUGGESTIONS_ES = listOf(
    "Traducir al hebreo",
    "Traducir al griego",
    "Traducir al arameo",
    "Traducir al latín",
    "Análisis del versículo",
    "Contexto histórico",
    "Ver referencias cruzadas",
    "Palabras clave",
    "Interpretaciones académicas",
    "Aplicación actual",
)

private val FOLLOWUP_SUGGESTIONS_PT = listOf(
    "Traduzir para o hebraico",
    "Traduzir para o grego",
    "Traduzir para o aramaico",
    "Traduzir para o latim",
    "Análise do versículo",
    "Contexto histórico",
    "Ver referências cruzadas",
    "Palavras-chave",
    "Interpretações acadêmicas",
    "Aplicação atual",
)

/** Picks the suggestion pool matching the bubble's current reply language. WEB/BBE share the
 *  English pool; Bíblia Livre shares the Portuguese pool; biblical-language texts fall back to
 *  English since AI follow-ups for those are most naturally phrased in English. */
private fun followUpSuggestionsFor(language: BibleLanguage): List<String> = when (language) {
    BibleLanguage.EN, BibleLanguage.WEB, BibleLanguage.BBE -> FOLLOWUP_SUGGESTIONS_EN
    BibleLanguage.ES -> FOLLOWUP_SUGGESTIONS_ES
    BibleLanguage.PT, BibleLanguage.PT_LIVRE -> FOLLOWUP_SUGGESTIONS_PT
    BibleLanguage.HE, BibleLanguage.GR, BibleLanguage.AR, BibleLanguage.LA -> FOLLOWUP_SUGGESTIONS_EN
}

private const val SUGGESTION_CYCLE_MILLIS = 12_000L

/**
 * The tap/verse-translate popup as a scoped mini-chat (chat-feature-addendum).
 * Sizing: starts compact (sized to the initial translation), grows
 * horizontally first as the conversation starts, then wraps and grows
 * vertically up to ~70% of the available height, after which the message
 * list scrolls internally instead of the whole bubble growing further.
 * Text styles are all sp-based (Material typography defaults), so they
 * follow the system font-scaling setting automatically.
 */
@Composable
fun ChatBubble(
    verseLabel: String,
    sourceLanguage: BibleLanguage,
    bubble: ChatBubbleState,
    onClose: () -> Unit,
    onStartOver: () -> Unit,
    onExpand: () -> Unit,
    onMinimize: () -> Unit = {},
    onDefine: () -> Unit,
    onLanguageChanged: (BibleLanguage) -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onChipTapped: (String) -> Unit,
    onResponseWordTapped: (String) -> Unit,
    onDefinitionWordTapped: (String) -> Unit,
    onVerseRefTapped: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hasConversation = bubble.messages.isNotEmpty()
    // Nearly edge-to-edge (94%) regardless of conversation state — this is a
    // sheet-like study panel, not a floating chat bubble, so it should read as
    // part of the screen rather than a small popover shrinking the verse
    // text's usable width behind it.
    val widthFraction = 0.94f

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // User-controlled height fraction: starts at 70 % of available screen, drag the handle
        // up to expand, down to condense (min 15 % so at least one message stays visible).
        var userHeightFraction by remember { mutableFloatStateOf(0.70f) }
        val availableHeight = maxHeight
        val maxMessageListHeight = availableHeight * userHeightFraction

        // DraggableState for the handle — startDragImmediately = true bypasses touch slop
        // so every finger movement from the very first pixel is tracked. Positive delta = down.
        val draggableState = rememberDraggableState { delta ->
            val heightPx = with(density) { availableHeight.toPx() }
            val deltaFrac = -delta / heightPx
            val newFrac = (userHeightFraction + deltaFrac).coerceIn(0.15f, 0.92f)
            userHeightFraction = newFrac
            if (newFrac <= 0.22f) onMinimize()
        }

        // Reset fraction to comfortable reading height whenever the panel is re-expanded.
        LaunchedEffect(bubble.isMinimized) {
            if (!bubble.isMinimized) userHeightFraction = 0.70f
        }

        // Always scroll the message list to the bottom so the most recent exchange
        // is visible when the panel is condensed by the drag handle.
        val msgListState = rememberLazyListState()
        LaunchedEffect(bubble.messages.size) {
            if (bubble.messages.isNotEmpty()) {
                msgListState.animateScrollToItem(bubble.messages.lastIndex + if (bubble.isSendingFollowUp) 1 else 0)
            }
        }

        // A frosted "pane of glass" floating over the reader: a translucent
        // gradient fill plus a bright rim-light border and soft shadow, in
        // place of an opaque Material Card — the iOS-26-style glass panel.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (bubble.isCondensed) {
                        // Condensed single-word mode hugs its content instead of spanning the
                        // screen — a small floating chip, not a full study sheet.
                        Modifier.widthIn(max = maxWidth * widthFraction)
                    } else {
                        Modifier.fillMaxWidth(widthFraction)
                    },
                )
                // Clears the system gesture/nav bar at the bottom instead of sitting under it.
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .shadow(elevation = 24.dp, shape = Glass.panelShape, ambientColor = Color.Black.copy(alpha = 0.25f))
                .clip(Glass.panelShape)
                .background(Glass.panelBrush())
                .border(width = 1.dp, brush = Glass.panelBorderBrush(), shape = Glass.panelShape)
                // Scoped to just the panel's own footprint (not the whole screen) — expands when
                // minimized, otherwise just absorbs the tap so it can't leak through to the verse
                // list or the outside-tap-to-minimize catcher behind it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (bubble.isMinimized) onExpand() },
                ),
            color = Color.Transparent,
            shape = Glass.panelShape,
        ) {
            Column(
                Modifier
                    .animateContentSize()
                    .padding(
                        start = if (bubble.isCondensed) 14.dp else 18.dp,
                        end = if (bubble.isCondensed) 14.dp else 18.dp,
                        top = if (bubble.isCondensed) 8.dp else 0.dp,
                        bottom = if (bubble.isCondensed) 8.dp else 16.dp,
                    ),
            ) {
                // ── Drag handle ─────────────────────────────────────────────────
                // 14 dp strip (half the previous size). draggable() with
                // startDragImmediately = true means every finger movement is tracked
                // from pixel zero — no touch-slop wait that made it feel unresponsive.
                if (!bubble.isCondensed) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .draggable(
                                state = draggableState,
                                orientation = Orientation.Vertical,
                                startDragImmediately = true,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (Glass.isDarkMode) Color.White.copy(alpha = 0.40f)
                                    else Color.Black.copy(alpha = 0.26f),
                                ),
                        )
                    }
                }

                if (bubble.isCondensed) {
                    CondensedWordRow(bubble, onDefine, onClose)
                    return@Column
                }
                // Sticky header: sparkle + verse label, language dropdown, close/delete.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Sparkle(size = 14.dp, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        verseLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    LanguageDropdown(
                        options = BibleLanguage.entries,
                        selected = bubble.bubbleTargetLanguage,
                        onSelected = onLanguageChanged,
                    )
                    IconButton(onClick = onStartOver) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                    }
                    TextButton(onClick = onClose) { Text("✕") }
                }

                Spacer(Modifier.padding(top = 6.dp))

                // Single-word focus: bolded/larger, with pronunciation + a real definition,
                // shown up top regardless of whether it came from the verse text or a reply.
                bubble.wordInfo?.let { info ->
                    WordInfoCard(
                        info = info,
                        // info.translation is now fetched alongside every word lookup, so all
                        // tapped words (verse words, response words, definition drill-downs)
                        // show the same "word · translation" pairing. Fall back to the
                        // precomputed initialTranslation only for the originally selected verse
                        // word where info.translation is absent (e.g. old cached state).
                        translation = info.translation
                            ?: bubble.selectedSingleWord
                                ?.takeIf { it == info.word && !bubble.initialIsLoading }
                                ?.let { bubble.initialTranslation },
                        onDefinitionWordTapped = onDefinitionWordTapped,
                    )
                }

                if (bubble.isMinimized) {
                    // Collapsed state (outside-tap): just the word/verse translation, no
                    // conversation, suggestions, or input — tap anywhere to expand again.
                    if (bubble.wordInfo == null) {
                        Text(
                            bubble.initialTranslation,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    return@Column
                }

                // Initial translation — still the precomputed/live one-shot result, unchanged (§7).
                // Every word is tappable, same as chat replies (tap-word-autofill clarification).
                // Hidden when the word-info card above is already carrying this exact translation
                // inline next to the word, so it doesn't print twice.
                val translationShownInCard =
                    bubble.selectedSingleWord != null && bubble.wordInfo?.word == bubble.selectedSingleWord
                if (!translationShownInCard) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bubble.initialIsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        // No SelectionContainer here — clipboard text-selection is deliberately
                        // disabled throughout the AI window; the only "selection" gesture on
                        // this text is tapping an individual word to look it up.
                        TappableWords(
                            text = bubble.initialTranslation,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            onWordTapped = onResponseWordTapped,
                            animate = !bubble.initialIsLoading,
                        )
                    }
                }

                if (hasConversation || bubble.isSendingFollowUp) {
                    LazyColumn(
                        state = msgListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxMessageListHeight)
                            .padding(top = 8.dp),
                    ) {
                        items(bubble.messages) { message -> ChatMessageRow(message, onResponseWordTapped, onVerseRefTapped) }
                        if (bubble.isSendingFollowUp) {
                            item { TypingIndicatorRow() }
                        }
                    }
                }

                if (bubble.atCap) {
                    Text(
                        "This is a deep conversation! Starting fresh will help keep answers focused — tap the trash icon to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    // Starts wherever openSequence lands (so different bubble opens don't all show
                    // the same first suggestion), then cycles through the whole pool automatically
                    // every 12s for as long as this bubble stays open on this verse.
                    var suggestionIndex by remember(bubble.openSequence) { mutableIntStateOf(bubble.openSequence) }
                    LaunchedEffect(bubble.openSequence) {
                        while (true) {
                            delay(SUGGESTION_CYCLE_MILLIS)
                            suggestionIndex++
                        }
                    }
                    // Re-picked whenever the bubble's reply language changes, so the pool (and
                    // whatever's currently showing) shifts languages right along with it.
                    val suggestions = remember(bubble.bubbleTargetLanguage) { followUpSuggestionsFor(bubble.bubbleTargetLanguage) }
                    val currentSuggestion = suggestions[suggestionIndex % suggestions.size]
                    val typedSuggestion = remember(currentSuggestion) {
                        // Typed out once when each new suggestion appears; doesn't re-animate on recomposition.
                        currentSuggestion
                    }.let { text ->
                        val visibleChars = rememberTypewriterProgress(
                            text = text,
                            totalUnits = text.length,
                            unitsPerTick = 1,
                            tickMillis = 40,
                        )
                        text.take(visibleChars)
                    }

                    var isInputFocused by remember { mutableStateOf(false) }

                    // The field's text + cursor live locally (TextFieldValue) so each keystroke
                    // applies synchronously — round-tripping every keystroke through the
                    // ViewModel's StateFlow used to let a stale value overwrite a newer one
                    // mid-burst, reordering fast typing and teleporting the cursor (the
                    // "backwards typing" bug). The ViewModel still mirrors the text for its
                    // send/autofill logic; inputSetSequence marks its *programmatic* writes
                    // (word-tap autofill, clear-on-send/start-over) and only those sync back in.
                    var inputValue by remember(bubble.verseId, bubble.openSequence) {
                        mutableStateOf(TextFieldValue(bubble.followUpInput, TextRange(bubble.followUpInput.length)))
                    }
                    LaunchedEffect(bubble.inputSetSequence) {
                        if (inputValue.text != bubble.followUpInput) {
                            inputValue = TextFieldValue(bubble.followUpInput, TextRange(bubble.followUpInput.length))
                        }
                    }

                    // Word-context chips: "Use X in a sentence" + original-language chips,
                    // set in the ViewModel whenever a single word is defined. Each chip is a
                    // tappable pill that fires onChipTapped so it goes through the same send
                    // path as the cycling placeholder suggestions. Hidden once at the cap or
                    // when the chip list is empty (multi-word, verse-level bubbles).
                    if (bubble.suggestedChips.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            items(bubble.suggestedChips) { chip ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onChipTapped(chip) },
                                ) {
                                    Text(
                                        chip,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Pill-shaped glass input with a slow, static (non-rotating) sky-blue/
                        // deep-blue pulse around the ring — the "Gemini is ready" affordance.
                        TextField(
                            value = inputValue,
                            onValueChange = {
                                // Forward every *text* change to the VM mirror unconditionally,
                                // comparing against the previous local value — not against
                                // bubble.followUpInput, which is a recomposition snapshot that
                                // can lag a keystroke behind and swallow a rapid type-then-
                                // delete edit (leaving the VM sending stale text). Cursor-only
                                // moves still skip the callback.
                                val textChanged = it.text != inputValue.text
                                inputValue = it
                                if (textChanged) onInputChanged(it.text)
                            },
                            placeholder = {
                                // Hide the cycling suggestion the moment the user taps into the
                                // field — the blinking cursor already signals "ready to type",
                                // and the suggestion text behind it looked like the cursor was
                                // randomly positioned mid-word (image feedback, item 7).
                                if (!isInputFocused) {
                                    Text(
                                        typedSuggestion,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.offset(x = (-6).dp),
                                    )
                                }
                            },
                            leadingIcon = { Sparkle(size = 14.dp) },
                            // The arrow is now the *only* send affordance — no separate Send
                            // button alongside the field. Blank field: taps the currently
                            // shown suggestion (unchanged). Non-blank: sends what's typed,
                            // replacing the old dedicated button 1:1.
                            trailingIcon = {
                                val canSend = inputValue.text.isNotBlank() && !bubble.isSendingFollowUp
                                IconButton(
                                    onClick = { if (inputValue.text.isBlank()) onChipTapped(currentSuggestion) else onSend() },
                                    enabled = inputValue.text.isBlank() || canSend,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = if (inputValue.text.isBlank()) "Ask: $currentSuggestion" else "Send",
                                        tint = if (inputValue.text.isBlank() || canSend) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        },
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clip(Glass.pillShape)
                                // Theme-aware (was hardcoded white, which glared in dark mode).
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), Glass.pillShape)
                                .geminiGlowBorder(strokeWidth = 2.2.dp, cornerRadius = 999.dp)
                                .onFocusChanged { isInputFocused = it.isFocused },
                            singleLine = true,
                            enabled = !bubble.isSendingFollowUp,
                            shape = Glass.pillShape,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                // No caret while the field is unfocused and empty — otherwise it
                                // blinks in front of the placeholder the whole time suggestions
                                // are auto-cycling, which reads as "stuck" every time a new one
                                // pops in. As soon as the user taps into the field (focuses it),
                                // the cursor should blink right away as the "ready to type" cue
                                // — it no longer waits for the first character.
                                cursorColor = if (isInputFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Compact all-3-language selector — a small dropdown instead of a segmented row to save width in the bubble header. */
@Composable
private fun LanguageDropdown(
    options: List<BibleLanguage>,
    selected: BibleLanguage,
    onSelected: (BibleLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("${selected.code.uppercase()} ▾", style = MaterialTheme.typography.labelMedium)
        }
        // Plain language names only — this dropdown just switches which language the bubble
        // replies in, it isn't a Bible-version/translation picker (that lives in the reader's
        // top-bar language pills), so the "(translation)" suffix doesn't belong here.
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    onResponseWordTapped: (String) -> Unit,
    onVerseRefTapped: (String) -> Unit,
) {
    // Verse cards are injected by tapping a hyperlinked verse reference — rendered as a
    // scripture excerpt card rather than a conversation turn so they stay visually distinct.
    if (message.isVerseCard) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        return
    }
    val label = if (message.role == ChatRole.USER) "You" else "Assistant"
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
        if (message.role == ChatRole.ASSISTANT && !message.isError) {
            // VerseLinkedText renders verse refs (e.g. "John 3:16") as tappable hyperlinks
            // that show the verse inline when tapped; all other words stay individually
            // tappable for the word-definition lookup — same as TappableWords.
            VerseLinkedText(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                onWordTapped = onResponseWordTapped,
                onVerseRefTapped = onVerseRefTapped,
                animate = true,
            )
        } else if (message.isError) {
            // Failed responses are plain, non-tappable text (not treated as
            // AI-generated content to tap into a definition lookup) so a
            // network/API error message can't itself be mistaken for verse
            // content and looked up as if it were a real word.
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontStyle = FontStyle.Italic,
            )
        } else {
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Matches Bible verse references in AI text: "John 3:16", "1 Samuel 2:3", "Song of Songs 1:2-4", etc. */
private val VERSE_IN_TEXT_REGEX = Regex(
    """(?:[123]\s+)?[A-Z][a-z]+(?:\s+(?:of\s+)?[A-Z][a-z]+)?\s+\d+:\d+(?:[-–]\d+)?"""
)

/**
 * Renders AI text with two tappability layers:
 * • Detected verse references (e.g. "John 3:16") → primary-colored + underlined → [onVerseRefTapped].
 * • All other words → [onWordTapped] for the word-definition drill-down.
 * Supports the same optional typewriter reveal as [TappableWords] when [animate] is set.
 */
@Composable
private fun VerseLinkedText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight? = null,
    onWordTapped: (String) -> Unit,
    onVerseRefTapped: (String) -> Unit,
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val fullAnnotated = remember(text, linkColor) {
        buildAnnotatedString {
            var last = 0
            VERSE_IN_TEXT_REGEX.findAll(text).forEach { match ->
                if (match.range.first > last) append(text.substring(last, match.range.first))
                pushStringAnnotation("verse", match.value)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(match.value)
                }
                pop()
                last = match.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }

    val visibleChars = rememberTypewriterProgress(
        text = text,
        totalUnits = text.length,
        unitsPerTick = 5,
        tickMillis = 16,
        animate = animate,
    )

    val displayedAnnotated = remember(visibleChars, fullAnnotated) {
        if (visibleChars >= fullAnnotated.length) fullAnnotated
        else buildAnnotatedString {
            append(fullAnnotated, 0, visibleChars.coerceAtMost(fullAnnotated.length))
        }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val mergedStyle = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style

    Text(
        text = displayedAnnotated,
        style = mergedStyle,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(displayedAnnotated) {
            detectTapGestures { offset ->
                val layout = layoutResult ?: return@detectTapGestures
                val charPos = layout.getOffsetForPosition(offset)
                    .coerceIn(0, (displayedAnnotated.length - 1).coerceAtLeast(0))
                val verseHit = displayedAnnotated
                    .getStringAnnotations("verse", charPos, charPos)
                    .firstOrNull()
                if (verseHit != null) {
                    onVerseRefTapped(verseHit.item)
                } else {
                    val t = displayedAnnotated.text
                    if (t.isEmpty()) return@detectTapGestures
                    var start = charPos
                    while (start > 0 && !t[start - 1].isWhitespace()) start--
                    var end = charPos
                    while (end < t.length && !t[end].isWhitespace()) end++
                    val word = t.substring(start, end)
                        .trim { it.isWhitespace() || it in setOf(',', '.', ';', ':', '!', '?', '"', '\'', '\u201C', '\u201D', '\u00A1', '\u00BF', '(', ')') }
                    if (word.isNotEmpty()) onWordTapped(word)
                }
            }
        },
    )
}

/**
 * Renders text as individually tappable words — tapping one a user doesn't understand prefills
 * a word-definition lookup. Used for the initial translation and word-info definitions.
 * When [animate] is set, words stream in one-by-one (Gemini-style typewriter reveal).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TappableWords(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    onWordTapped: (String) -> Unit,
    fontWeight: FontWeight? = null,
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = remember(text) { VerseTokenizer.tokenize(text) }
    val visibleCount = rememberTypewriterProgress(
        text = text,
        totalUnits = tokens.size,
        unitsPerTick = 1,
        tickMillis = 55,
        animate = animate,
    )
    FlowRow(modifier = modifier) {
        tokens.take(visibleCount).forEach { word ->
            // No underline — every word here is already tappable by design (that's the whole
            // point of this composable), so decorating them as if they were links/definitions
            // added visual noise across the entire response instead of only where it matters.
            Text(
                text = "$word ",
                style = style,
                fontWeight = fontWeight,
                modifier = Modifier.clickable { onWordTapped(word) },
            )
        }
    }
}

/**
 * Single-word focus card: word bolded at a bigger size, pronunciation, and a real definition.
 * Tapping a word *inside* the definition drills one level deeper — via [onDefinitionWordTapped],
 * a distinct gesture from tapping a word in a chat reply — replacing this card with a fresh
 * lookup for that word instead of folding it into the multi-word follow-up autofill queue.
 */
@Composable
private fun WordInfoCard(info: WordInfoState, translation: String?, onDefinitionWordTapped: (String) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        // "word · translation" — the translation rides inline right next to the word (smaller,
        // not underlined, dot-separated) instead of as its own block below the card.
        Text(
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(info.word)
                }
                if (translation != null) {
                    withStyle(
                        SpanStyle(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        append("  ·  $translation")
                    }
                }
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        if (info.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Looking up pronunciation and definition…", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        } else {
            // No SelectionContainer — clipboard text-selection is disabled here too; the
            // definition's words stay individually tappable for the drill-down lookup instead.
            Column {
                info.pronunciation?.let {
                    Text("/$it/", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 2.dp))
                }
                info.definition?.let {
                    TappableWords(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        onWordTapped = onDefinitionWordTapped,
                        animate = true,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Condensed single-word mode: one compact row — "word · translation" plus a Define affordance.
 * The full study UI (header, conversation, input) only unfolds once the user asks to define,
 * with the panel growing smoothly out of this chip as the definition streams in.
 */
@Composable
private fun CondensedWordRow(bubble: ChatBubbleState, onDefine: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Sparkle(size = 14.dp)
        Spacer(Modifier.width(8.dp))
        if (bubble.initialIsLoading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(bubble.selectedSingleWord.orEmpty())
                }
                if (!bubble.initialIsLoading) {
                    withStyle(
                        SpanStyle(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        append("  ·  ${bubble.initialTranslation}")
                    }
                }
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onDefine, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("✦ Define", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕") }
    }
}

@Composable
private fun TypingIndicatorRow() {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Thinking…", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
    }
}
