package com.logos.bibletranslate.ui.reader

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.logos.bibletranslate.data.BibleLanguage
import com.logos.bibletranslate.data.ChatMessage
import com.logos.bibletranslate.data.ChatRole
import com.logos.bibletranslate.data.VerseTokenizer

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

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(widthFraction)
                .padding(bottom = 12.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                // Sticky header: verse label, language dropdown, close/start-over.
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    TextButton(onClick = onStartOver) { Text("Start Over") }
                    TextButton(onClick = onClose) { Text("✕") }
                }

                Spacer(Modifier.padding(top = 6.dp))

                // Single-word focus: bolded/larger, with pronunciation + a real definition,
                // shown up top regardless of whether it came from the verse text or a reply.
                bubble.wordInfo?.let { info -> WordInfoCard(info) }

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

                if (bubble.suggestedChips.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp)) {
                        bubble.suggestedChips.forEach { chip ->
                            SuggestionChip(
                                onClick = { onChipTapped(chip) },
                                label = { Text(chip, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }

                if (bubble.atCap) {
                    Text(
                        "This is a deep conversation! Starting fresh will help keep answers focused — tap 'Start Over' to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = bubble.followUpInput,
                            onValueChange = onInputChanged,
                            placeholder = { Text("Ask a follow-up...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !bubble.isSendingFollowUp,
                        )
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
private fun ChatMessageRow(message: ChatMessage, onResponseWordTapped: (String) -> Unit) {
    val label = if (message.role == ChatRole.USER) "You" else "Assistant"
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
        if (message.role == ChatRole.ASSISTANT) {
            TappableWords(text = message.text, style = MaterialTheme.typography.bodyMedium, onWordTapped = onResponseWordTapped)
        } else {
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Renders text as individually tappable words — tapping one a user doesn't understand prefills
 * a "What does X mean in this verse?" follow-up question (tap-word-autofill clarification).
 * Used for both the initial translation and assistant chat replies.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TappableWords(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    onWordTapped: (String) -> Unit,
    fontWeight: FontWeight? = null,
) {
    FlowRow {
        VerseTokenizer.tokenize(text).forEach { word ->
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
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
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
