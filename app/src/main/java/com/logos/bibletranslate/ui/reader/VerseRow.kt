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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.logos.bibletranslate.data.VerseData
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a finger must stay put on a word before a hold turns into a selection. 750ms made
 * "this is a deliberate hold" unambiguous but felt sluggish in practice — cut back by about a
 * third to 450ms, which is still comfortably past the touch-slop race window from a scroll
 * flick's initial touch, but responds quickly enough that hold-then-drag-to-select feels snappy.
 */
private const val HOLD_TO_SELECT_MILLIS = 450L

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
    /** True right after this verse was triple-tap-selected — every further tap on it is inert. */
    isLocked: Boolean = false,
    /** Fired at the very start of every gesture here, before any tap logic — lets a tap
     * elsewhere release a different verse's lock. */
    onGestureDown: () -> Unit = {},
    /** Fired when this row's own triple tap fires, to lock it. */
    onVerseLocked: () -> Unit = {},
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
    val lockedState = rememberUpdatedState(isLocked)
    val haptics = LocalHapticFeedback.current

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
                    // Selection is deliberately *not* started by a plain tap anymore — a stray
                    // finger landing mid-scroll used to fire a translation instantly. Now:
                    //   • hold a word ~750ms → select it
                    //   • double-tap a word   → select it
                    //   • triple-tap          → select the whole verse
                    //   • slide after any of those → extend the highlight word by word
                    // A plain tap (or a touch that moves into a scroll) does nothing.
                    // Multi-tap bookkeeping survives across gestures in these locals.
                    var tapCount = 0
                    var lastTapUpMillis = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Always fires first, even on this row's own locked verse — a tap on a
                        // *different* verse than the one currently locked releases that lock.
                        onGestureDown()
                        if (lockedState.value) {
                            // This verse was just triple-tap-selected; every tap on it is inert
                            // until an outside tap unlocks it (guards the in-flight/shown
                            // translation from a stray extra tap). The down IS consumed here —
                            // not to block scrolling (Compose's list-scroll drag detector
                            // tolerates an already-consumed down and still starts on movement,
                            // the same reason dragging over a button inside a scrollable list
                            // still scrolls it) but so the full-screen outside-tap catcher
                            // behind the list doesn't also see this same tap as unconsumed and
                            // mistake a repeat tap *on the locked verse itself* for an "outside"
                            // tap that would immediately undo the lock.
                            down.consume()
                            return@awaitEachGesture
                        }
                        val isToggleGesture = toggleModeState.value
                        val startIndex = hitTest(down.position, bounds)
                        if (isToggleGesture) {
                            // Bubble already open on this verse: a plain tap still toggles the
                            // word in/out of the pending autofill question — the user is already
                            // deliberately working inside this verse, so no hold gate here.
                            startIndex?.let(onWordToggle)
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        if (startIndex == null) {
                            tapCount = 0
                            return@awaitEachGesture
                        }

                        // Deliberately *not* requiring the same exact word as the previous tap:
                        // real fingers land a word or two off between taps, and requiring an
                        // exact match let that natural imprecision reset the chain, so a
                        // "triple tap" often fizzled into three independent single taps and the
                        // verse never got fully selected. Timing alone (within the platform's
                        // double-tap window) is enough — all taps land within one verse row's
                        // gesture handler anyway.
                        val isMultiTapContinuation = down.uptimeMillis - lastTapUpMillis <= viewConfiguration.doubleTapTimeoutMillis
                        val tapNumber = if (isMultiTapContinuation) tapCount + 1 else 1

                        var selecting = false
                        when {
                            tapNumber >= 3 -> {
                                // Triple tap: the whole verse.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelectionStart(0)
                                if (tokens.isNotEmpty()) onSelectionExtend(tokens.lastIndex)
                                selecting = true
                            }
                            tapNumber == 2 -> {
                                // Double tap: select this word (slide extends from it).
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectionStart(startIndex)
                                selecting = true
                            }
                            else -> {
                                // First tap: nothing happens yet. It becomes a selection only if
                                // it turns into a hold; a quick release is remembered as a
                                // potential first tap of a double/triple; movement past touch
                                // slop hands the gesture over to the scroller untouched.
                                var releasedAt = -1L
                                var movedAway = false
                                val finishedBeforeTimeout = withTimeoutOrNull(HOLD_TO_SELECT_MILLIS) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) {
                                            releasedAt = change?.uptimeMillis ?: down.uptimeMillis
                                            return@withTimeoutOrNull
                                        }
                                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                            movedAway = true
                                            return@withTimeoutOrNull
                                        }
                                    }
                                }
                                when {
                                    finishedBeforeTimeout == null -> {
                                        // Finger stayed put past the timeout: hold-to-select.
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelectionStart(startIndex)
                                        selecting = true
                                    }
                                    movedAway -> {
                                        tapCount = 0
                                        return@awaitEachGesture
                                    }
                                    else -> {
                                        tapCount = 1
                                        lastTapUpMillis = releasedAt
                                        return@awaitEachGesture
                                    }
                                }
                            }
                        }

                        if (selecting) {
                            var extended = false
                            var lastUptime = down.uptimeMillis
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastUptime = change.uptimeMillis
                                if (!change.pressed) break
                                if (change.positionChanged()) {
                                    hitTest(change.position, bounds)?.let { index ->
                                        if (index != startIndex) extended = true
                                        onSelectionExtend(index)
                                    }
                                    change.consume()
                                }
                            }
                            // Light tick as a slide-extended highlight is let go.
                            if (extended) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // A selection release still advances the multi-tap chain, so
                            // double-tap (word) can escalate to a third tap (whole verse).
                            tapCount = if (tapNumber >= 3) 0 else tapNumber
                            lastTapUpMillis = lastUptime
                            if (tapNumber >= 3) onVerseLocked()
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
