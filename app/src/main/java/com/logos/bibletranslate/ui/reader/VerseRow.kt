package com.logos.bibletranslate.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
) {
    val bounds = remember(verse.verseId) { mutableStateMapOf<Int, Rect>() }
    // Read fresh at the start of each gesture (not captured once when pointerInput's block
    // was created) so a bubble opened mid-session correctly flips later taps into toggle mode
    // without disturbing a gesture already in progress (tap-word-autofill-idea.md).
    val toggleModeState = rememberUpdatedState(bubbleOpenForThisVerse)

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TextButton(onClick = onTranslateVerse, modifier = Modifier.width(40.dp)) {
            Text("T", style = MaterialTheme.typography.labelSmall)
        }
        Column {
            Text(
                text = "${verse.verse}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 14.dp, end = 4.dp),
            )
        }
        FlowRow(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
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
                Text(
                    text = token,
                    modifier = Modifier
                        .onGloballyPositioned { coords -> bounds[index] = coords.boundsInParent() }
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
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
