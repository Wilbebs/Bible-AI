package com.logos.bibletranslate.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.logos.bibletranslate.ui.theme.Glass
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
    /** Whether this verse's action icons (speaker + bookmark) are currently visible.
     * Toggled by tapping the verse number — a second tap hides them; tapping a different
     * verse number moves the icons there instead. */
    isHovered: Boolean = false,
    /** Whether this verse is bookmarked — applies a soft accent-color wash behind the row. */
    isBookmarked: Boolean = false,
    /**
     * Partner reading highlight: true = AI's turn (first accent color), false = user's turn
     * (last accent color), null = not the currently active partner verse.
     * Overrides the bookmark background when set.
     */
    isPartnerHighlightIsAi: Boolean? = null,
    /** Called when the verse number is tapped — parent decides toggle logic. */
    onVerseHoverToggle: () -> Unit = {},
    /** Called when the speaker icon is tapped — parent reads the verse text aloud. */
    onSpeakVerse: () -> Unit = {},
    /** Called when the bookmark icon is tapped — parent toggles bookmark state. */
    onToggleBookmark: () -> Unit = {},
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
    // without disturbing a gesture already in progress.
    val toggleModeState = rememberUpdatedState(bubbleOpenForThisVerse)
    val lockedState = rememberUpdatedState(isLocked)
    val haptics = LocalHapticFeedback.current

    // Partner reading highlight takes precedence; uses first/last accent colors so it always
    // matches whichever marble is selected. Bookmark wash shows only when partner mode is off.
    val rowBackground = when {
        isPartnerHighlightIsAi == true  -> Glass.skyBlue.copy(alpha = 0.22f)
        isPartnerHighlightIsAi == false -> Glass.deepBlue.copy(alpha = 0.22f)
        isBookmarked                    -> Glass.skyBlue.copy(alpha = 0.15f)
        else                            -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground),
        verticalAlignment = Alignment.Top,
    ) {
        // Verse number — tapping it toggles the action icons (hover state) for this verse.
        // The clickable region is the number itself plus its padding, deliberately small so
        // it doesn't compete with word-selection gestures on the text alongside it.
        Text(
            text = "${verse.verse}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onVerseHoverToggle)
                .padding(top = 7.dp, end = 3.dp),
        )
        FlowRow(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .pointerInput(verse.verseId) {
                    // Selection is deliberately *not* started by a plain tap anymore — a stray
                    // finger landing mid-scroll used to fire a translation instantly. Now:
                    //   • hold a word ~450ms → select it
                    //   • double-tap a word   → select it
                    //   • triple-tap          → select the whole verse
                    //   • slide after any of those → extend the highlight word by word
                    // A plain tap (or a touch that moves into a scroll) does nothing.
                    var tapCount = 0
                    var lastTapUpMillis = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onGestureDown()
                        if (lockedState.value) {
                            down.consume()
                            return@awaitEachGesture
                        }
                        val isToggleGesture = toggleModeState.value
                        val startIndex = hitTest(down.position, bounds)
                        if (isToggleGesture) {
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

                        val isMultiTapContinuation = down.uptimeMillis - lastTapUpMillis <= viewConfiguration.doubleTapTimeoutMillis
                        val tapNumber = if (isMultiTapContinuation) tapCount + 1 else 1

                        var selecting = false
                        when {
                            tapNumber >= 3 -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelectionStart(0)
                                if (tokens.isNotEmpty()) onSelectionExtend(tokens.lastIndex)
                                selecting = true
                            }
                            tapNumber == 2 -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectionStart(startIndex)
                                selecting = true
                            }
                            else -> {
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
                            if (extended) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

        // ── Action icons: bookmark (left) + speaker (right) ─────────────────
        // Fade in when the verse number is tapped; fade out on a second tap or when another
        // verse is activated. Compact 28 dp tap targets / 16 dp glyphs so they don't push
        // the verse row height or intrude on reading width.
        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(120)),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(28.dp).padding(top = 2.dp),
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark verse",
                        modifier = Modifier.size(16.dp),
                        tint = if (isBookmarked) Glass.skyBlue
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
                IconButton(
                    onClick = onSpeakVerse,
                    modifier = Modifier.size(28.dp).padding(top = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VolumeUp,
                        contentDescription = "Read verse aloud",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                    )
                }
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
