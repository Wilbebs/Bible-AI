package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.logos.bibletranslate.data.VerseData

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerseRow(
    verse: VerseData,
    tokens: List<String>,
    selectedIndices: Set<Int>,
    bubbleOpenForThisVerse: Boolean,
    onSelectionStart: (Int) -> Unit,
    onSelectionExtend: (Int) -> Unit,
    onWordToggle: (Int) -> Unit,
    onTranslateVerse: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set for a verse search's landing verse — drives the slow sky-blue/dark-blue pulse across every word. */
    isHighlighted: Boolean = false,
) {
    // A two-tone (sky-blue ↔ deep-blue) breathing pulse, applied uniformly to every word in
    // the verse so the whole sentence pulses together, not just one word.
    val highlightColor: Color? = if (isHighlighted) {
        val transition = rememberInfiniteTransition(label = "verseHighlight")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "verseHighlightProgress",
        )
        lerp(Color(0xFF4FC3F7).copy(alpha = 0.22f), Color(0xFF0B1F4B).copy(alpha = 0.5f), progress)
    } else {
        null
    }
    val bounds = remember(verse.verseId) { mutableStateMapOf<Int, Rect>() }
    // Read fresh at the start of each gesture (not captured once when pointerInput's block
    // was created) so a bubble opened mid-session correctly flips later taps into toggle mode
    // without disturbing a gesture already in progress (tap-word-autofill-idea.md).
    val toggleModeState = rememberUpdatedState(bubbleOpenForThisVerse)

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // Verse number doubles as the "show this verse in the other two languages"
        // affordance (previously a separate "T" button) — tinted like a link so
        // it still reads as tappable, but it no longer eats a fixed 40dp column,
        // letting the verse text start almost flush with the screen edge.
        Text(
            text = "${verse.verse}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 7.dp, end = 3.dp)
                .clickable(onClick = onTranslateVerse),
        )
        FlowRow(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .pointerInput(verse.verseId) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val isToggleGesture = toggleModeState.value
                        val startIndex = hitTest(down.position, bounds)
                        if (isToggleGesture) {
                            startIndex?.let(onWordToggle)
                        } else {
                            startIndex?.let(onSelectionStart)
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!isToggleGesture && change.positionChanged()) {
                                hitTest(change.position, bounds)?.let(onSelectionExtend)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            tokens.forEachIndexed { index, token ->
                val isSelected = index in selectedIndices
                val background = when {
                    highlightColor != null -> highlightColor
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                }
                Text(
                    text = token,
                    modifier = Modifier
                        .onGloballyPositioned { coords -> bounds[index] = coords.boundsInParent() }
                        .background(background)
                        .padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** Finds the word whose bounds contain the point, else the nearest word on the same line. */
private fun hitTest(position: Offset, bounds: Map<Int, Rect>): Int? {
    bounds.entries.firstOrNull { it.value.contains(position) }?.let { return it.key }
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    for ((index, rect) in bounds) {
        if (position.y < rect.top - 24f || position.y > rect.bottom + 24f) continue
        val dx = when {
            position.x < rect.left -> rect.left - position.x
            position.x > rect.right -> position.x - rect.right
            else -> 0f
        }
        if (dx < bestDistance) {
            bestDistance = dx
            best = index
        }
    }
    return best
}
