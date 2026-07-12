package com.logos.bibletranslate.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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

/** Endless fallback cycle once a turn's own suggestions have all been shown once. */
private val FALLBACK_FOLLOWUP_SUGGESTIONS = listOf(
    "Translate to Hebrew",
    "Translate to Greek",
    "Translate to Aramaic",
    "Translate to Latin",
)

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
    onLanguageChanged: (BibleLanguage) -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onChipTapped: (String) -> Unit,
    onResponseWordTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasConversation = bubble.messages.isNotEmpty()
    // Nearly edge-to-edge (94%) regardless of conversation state — this is a
    // sheet-like study panel, not a floating chat bubble, so it should read as
    // part of the screen rather than a small popover shrinking the verse
    // text's usable width behind it.
    val widthFraction = 0.94f

    BoxWithConstraints(modifier) {
        val maxMessageListHeight = maxHeight * 0.7f

        // A frosted "pane of glass" floating over the reader: a translucent
        // gradient fill plus a bright rim-light border and soft shadow, in
        // place of an opaque Material Card — the iOS-26-style glass panel.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(widthFraction)
                .padding(bottom = 12.dp)
                .shadow(elevation = 24.dp, shape = Glass.panelShape, ambientColor = Color.Black.copy(alpha = 0.25f))
                .clip(Glass.panelShape)
                .background(Glass.panelBrush())
                .border(width = 1.dp, brush = Glass.panelBorderBrush(), shape = Glass.panelShape)
                .let { if (bubble.isMinimized) it.clickable { onExpand() } else it },
            color = Color.Transparent,
            shape = Glass.panelShape,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
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
                bubble.wordInfo?.let { info -> WordInfoCard(info) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bubble.initialIsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    TappableWords(
                        text = bubble.initialTranslation,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        onWordTapped = onResponseWordTapped,
                        animate = !bubble.initialIsLoading,
                    )
                }

                if (hasConversation || bubble.isSendingFollowUp) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxMessageListHeight)
                            .padding(top = 8.dp),
                    ) {
                        items(bubble.messages) { message -> ChatMessageRow(message, onResponseWordTapped) }
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
                    // Suggestions used to sit in a chip row above the input; they now cycle
                    // through the input's own placeholder instead, one at a time, with a
                    // typed-up reveal — this turn's own suggestions first (tapping the arrow
                    // asks that one directly), then looping endlessly through a fallback set
                    // of "Translate to X" prompts once the turn's own list is exhausted.
                    val suggestionCycle = remember(bubble.suggestedChips) {
                        bubble.suggestedChips + FALLBACK_FOLLOWUP_SUGGESTIONS
                    }
                    var suggestionIndex by remember(bubble.suggestedChips) { mutableIntStateOf(0) }
                    LaunchedEffect(suggestionCycle) {
                        suggestionIndex = 0
                        while (true) {
                            delay(2800)
                            suggestionIndex = (suggestionIndex + 1) % suggestionCycle.size
                        }
                    }
                    val currentSuggestion = suggestionCycle.getOrNull(suggestionIndex)
                    val typedSuggestion = currentSuggestion?.let { text ->
                        val visibleChars = rememberTypewriterProgress(
                            text = text,
                            totalUnits = text.length,
                            unitsPerTick = 1,
                            tickMillis = 40,
                        )
                        text.take(visibleChars)
                    }

                    Row(
                        Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Pill-shaped glass input with a slow, "heavenly" loading-style pulse
                        // (dark blue → sky blue → golden → sky blue → dark blue) sweeping
                        // around the ring — the "Gemini is ready" affordance.
                        TextField(
                            value = bubble.followUpInput,
                            onValueChange = onInputChanged,
                            placeholder = { Text(typedSuggestion ?: "Ask a follow-up…") },
                            leadingIcon = { Sparkle(size = 14.dp) },
                            trailingIcon = if (currentSuggestion != null && bubble.followUpInput.isBlank()) {
                                {
                                    IconButton(onClick = { onChipTapped(currentSuggestion) }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Ask: $currentSuggestion")
                                    }
                                }
                            } else null,
                            modifier = Modifier
                                .weight(1f)
                                .clip(Glass.pillShape)
                                .background(Color.White.copy(alpha = 0.6f), Glass.pillShape)
                                .geminiGlowBorder(strokeWidth = 2.2.dp, cornerRadius = 999.dp),
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
                            ),
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = onSend,
                            enabled = !bubble.isSendingFollowUp && bubble.followUpInput.isNotBlank(),
                        ) {
                            Text("Send")
                        }
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayNameWithTranslation) },
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
private fun ChatMessageRow(message: ChatMessage, onResponseWordTapped: (String) -> Unit) {
    val label = if (message.role == ChatRole.USER) "You" else "Assistant"
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
        if (message.role == ChatRole.ASSISTANT && !message.isError) {
            TappableWords(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                onWordTapped = onResponseWordTapped,
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

/**
 * Renders text as individually tappable words — tapping one a user doesn't understand prefills
 * a "What does X mean in this verse?" follow-up question (tap-word-autofill clarification).
 * Used for both the initial translation and assistant chat replies. When [animate] is set, the
 * words stream in one-by-one (a Gemini-style typewriter reveal) instead of appearing all at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TappableWords(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    onWordTapped: (String) -> Unit,
    fontWeight: FontWeight? = null,
    animate: Boolean = false,
) {
    val tokens = remember(text) { VerseTokenizer.tokenize(text) }
    val visibleCount = rememberTypewriterProgress(
        text = text,
        totalUnits = tokens.size,
        unitsPerTick = 1,
        tickMillis = 55,
        animate = animate,
    )
    FlowRow {
        tokens.take(visibleCount).forEach { word ->
            Text(
                text = "$word ",
                style = style,
                fontWeight = fontWeight,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onWordTapped(word) },
            )
        }
    }
}

/** Single-word focus card: word bolded at a bigger size, pronunciation, and a real definition. */
@Composable
private fun WordInfoCard(info: WordInfoState) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(info.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (info.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Looking up pronunciation and definition…", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        } else {
            info.pronunciation?.let {
                Text("/$it/", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 2.dp))
            }
            info.definition?.let {
                com.logos.bibletranslate.ui.theme.TypewriterText(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
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
