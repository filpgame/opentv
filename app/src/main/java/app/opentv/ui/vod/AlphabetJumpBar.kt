/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Categories smaller than this get no bar: a few screens of posters scroll by faster than a
 * letter can be aimed at, and the bar would only take width from the grid.
 */
internal const val ALPHABET_BAR_MIN_TITLES = 40

private val BAR_WIDTH = 44.dp

private const val SCRUB_SETTLE_MS = 90L

private const val LANDED_OUTLINE_MS = 1_600L

/**
 * The A–Z jump bar for a category grid, hooked in without wrapping the grid:
 *
 *     val jump = rememberAlphabetJump(items) { it.displayTitle }
 *     LazyVerticalGrid(state = jump.gridState, modifier = Modifier.fillMaxSize().padding(end = jump.barInset)) {
 *         items(items) { item -> PosterCard(…, modifier = jump.posterModifier(item)) }
 *     }
 *     AlphabetJumpOverlay(jump)
 *
 * [items] must be exactly the list the grid shows, in the order it shows it, so a filtered grid
 * gets an index of what is left.
 *
 * A caller that resets its grid's scroll by swapping the state (the category grid does on every
 * filter chip, so a shorter list starts at the top) passes that state as [gridState]; the jump
 * state is keyed on it and starts over with it.
 *
 * The bar lists all 27 entries and dims the empty ones rather than listing only the letters a
 * category has. Fixed positions are what make scrubbing work: a letter sits at the same height in
 * every category and under every filter, so the thumb learns where M is, and a filter that empties
 * a letter does not slide the rest of the bar under the finger. Dimmed letters take no d-pad focus,
 * so on a TV remote they cost no extra presses either.
 */
@Composable
internal fun <T> rememberAlphabetJump(
    items: List<T>,
    gridState: LazyGridState = rememberLazyGridState(),
    title: (T) -> String,
): AlphabetJumpState {
    val scope = rememberCoroutineScope()
    // Decided from the size alone, before the index exists, so the grid is laid out at its final
    // width from the first frame instead of reflowing its columns when the bar arrives.
    val showBar = items.size >= ALPHABET_BAR_MIN_TITLES
    val state = remember(gridState, showBar) { AlphabetJumpState(gridState, scope, showBar) }
    val currentTitle by rememberUpdatedState(title)
    LaunchedEffect(state, items) {
        state.clearJump()
        if (!state.showBar) return@LaunchedEffect
        // Off the main thread: the title is the regex-based display cleaner, run over every item,
        // and a category can hold 11,000 of them.
        val index = withContext(Dispatchers.Default) { AlphabetIndex.of(items, currentTitle) }
        state.setIndex(items, index)
    }
    return state
}

/**
 * The bar down the right edge and the letter bubble over the grid. Emitted after the grid, in the
 * same parent, so both draw on top of it; neither takes touches outside the bar itself, so the
 * grid still scrolls everywhere else.
 */
@Composable
internal fun AlphabetJumpOverlay(state: AlphabetJumpState) {
    if (!state.showBar) return
    Box(Modifier.fillMaxSize()) {
        val bubble = state.bubbleBucket
        if (bubble >= 0) {
            Box(Modifier.fillMaxSize().padding(end = BAR_WIDTH), contentAlignment = Alignment.Center) {
                LetterBubble(TitleAlphabet.LABELS[bubble])
            }
        }
        AlphabetBar(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** What the grid and its bar share: the grid's scroll, the index, and the last jump. */
@Stable
internal class AlphabetJumpState(
    val gridState: LazyGridState,
    private val scope: CoroutineScope,
    val showBar: Boolean,
) {
    /** End padding the grid leaves for the bar. */
    val barInset: Dp get() = if (showBar) BAR_WIDTH else 0.dp

    var index by mutableStateOf<AlphabetIndex?>(null)
        private set

    /** The list [index] was built from, to find the poster behind an index without the grid's help. */
    private var indexedItems: List<Any?> = emptyList()

    /** The item the last jump landed on, or -1. Only steers [currentBucket]. */
    private var jumpedTo by mutableIntStateOf(-1)

    internal val letterFocus = List(TitleAlphabet.BUCKETS) { FocusRequester() }

    /** Letter under a scrubbing finger, or -1. */
    internal var scrubBucket by mutableIntStateOf(-1)
        private set
    private var scrubJumped = -1
    private var scrubSettle: Job? = null

    /** Letter holding d-pad focus, or -1. */
    internal var focusedBucket by mutableIntStateOf(-1)

    /**
     * While a letter has d-pad focus, the first poster under it carries [posterFocus], so LEFT or OK
     * can hand focus straight to it. Tied to the focused letter rather than to the last jump: after
     * entering the bar on the letter already on screen there is no jump, and LEFT must still go to
     * the letter shown, not to wherever an older jump landed.
     */
    private val posterFocus = FocusRequester()
    private val posterTarget: Int by derivedStateOf {
        val bucket = focusedBucket
        if (bucket < 0) -1 else index?.firstIndexOf(bucket) ?: -1
    }

    /** The big letter over the grid: while scrubbing, and while the bar has d-pad focus. */
    val bubbleBucket: Int get() = if (scrubBucket >= 0) scrubBucket else focusedBucket

    /**
     * The letter the grid is on, for the bar's highlight and as the letter d-pad focus enters on.
     * Right after a jump it is the jumped-to letter, as long as that title is still in the top row:
     * a jump puts the title's *row* at the top, and that row usually starts with the tail of the
     * previous letter, so the first visible item would name the wrong one.
     */
    val currentBucket: Int by derivedStateOf {
        val idx = index ?: return@derivedStateOf -1
        val first = gridState.firstVisibleItemIndex
        val visible = gridState.layoutInfo.visibleItemsInfo
        // The row the scroll position is on. Not simply the first visible item's row: the bottom
        // of the row above still shows through the grid's top content padding, and counts as visible.
        val topRow = visible.firstOrNull { it.index == first }?.row
        val target = jumpedTo
        if (target >= 0 && topRow != null && visible.any { it.index == target && it.row == topRow }) idx.bucketAt(target)
        else idx.bucketAt(first)
    }

    /**
     * The poster a jump landed on, outlined for a moment. A jump brings that poster's *row* to the
     * top, and the row often opens with the last titles of the previous letter: jump to C and the
     * top row can read "Butch Cassidy, Buzine…, C'est pas moi". Without a mark that looks like a
     * jump to the wrong letter.
     */
    private var landedOn by mutableIntStateOf(-1)
    private var landedFade: Job? = null

    /**
     * Put on each grid cell, given that cell's item: the landing outline and the d-pad focus target
     * go on the posters they belong to, every other cell gets nothing. By item rather than by
     * position so the grid can keep its plain `items` call.
     */
    @Composable
    fun posterModifier(item: Any?): Modifier {
        fun isAt(i: Int) = i >= 0 && indexedItems.getOrNull(i) === item
        var modifier: Modifier = Modifier
        if (isAt(landedOn)) {
            modifier = modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
        }
        if (isAt(posterTarget)) modifier = modifier.focusRequester(posterFocus)
        return modifier
    }

    internal fun setIndex(items: List<Any?>, index: AlphabetIndex) {
        indexedItems = items
        this.index = index
    }

    /**
     * Scrolls the first title under [bucket] to the top. `scrollToItem`, not the animated variant:
     * the jump alone already costs one long frame of fresh posters on the car head unit, every
     * animated frame would lay out more, and while scrubbing the target changes faster than an
     * animation could finish.
     */
    fun jumpTo(bucket: Int, thenFocusPoster: Boolean = false) {
        val target = index?.firstIndexOf(bucket)?.takeIf { it >= 0 } ?: return
        jumpedTo = target
        landedOn = target
        landedFade?.cancel()
        landedFade = scope.launch {
            delay(LANDED_OUTLINE_MS)
            landedOn = -1
        }
        scope.launch {
            gridState.scrollToItem(target)
            if (thenFocusPoster) {
                // One frame for the cell to recompose with the requester attached.
                withFrameNanos { }
                focusPoster()
            }
        }
    }

    /**
     * Moves d-pad focus onto the focused letter's first poster. False when that poster is not on
     * screen (its requester is not attached), and the caller falls back to plain focus search.
     */
    fun focusPoster(): Boolean {
        val target = posterTarget
        if (target < 0 || gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) return false
        val moved = runCatching { posterFocus.requestFocus() }.isSuccess
        // The focus border marks it now; the outline on top would only double it.
        if (moved) landedOn = -1
        return moved
    }

    /**
     * The finger is on [bucket]. The press itself jumps at once, so a tap answers immediately; while
     * dragging, the bubble follows the finger at once but the grid only jumps once the finger has
     * stayed on a letter for [SCRUB_SETTLE_MS], or on release. Every jump lays out a fresh screen of
     * posters, which on the car head unit (debug build) is one 120–210 ms frame; jumping on every
     * letter crossed held a drag down the bar to about 9 frames a second, bubble included.
     */
    internal fun scrubTo(bucket: Int, immediate: Boolean) {
        if (bucket < 0 || bucket == scrubBucket) return
        scrubBucket = bucket
        scrubSettle?.cancel()
        if (bucket == scrubJumped) return
        if (immediate) {
            scrubJumped = bucket
            jumpTo(bucket)
        } else {
            scrubSettle = scope.launch {
                delay(SCRUB_SETTLE_MS)
                scrubJumped = bucket
                jumpTo(bucket)
            }
        }
    }

    internal fun endScrub() {
        scrubSettle?.cancel()
        val last = scrubBucket
        if (last >= 0 && last != scrubJumped) jumpTo(last)
        scrubBucket = -1
        scrubJumped = -1
    }

    internal fun clearJump() {
        jumpedTo = -1
        landedOn = -1
    }
}

/**
 * The bar itself. Touch: a press jumps to the letter under the finger and dragging scrubs, all in
 * one gesture handler on the column, so a drag that starts on one letter keeps working as it
 * crosses the others. D-pad: RIGHT from the grid's last column lands on the current letter;
 * UP/DOWN move through the letters that have titles, each one jumping as it takes focus;
 * LEFT or OK drop focus onto the first poster of that letter. LEFT from the grid itself is
 * untouched, so it still reaches the category rail.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AlphabetBar(state: AlphabetJumpState, modifier: Modifier) {
    val index = state.index
    BoxWithConstraints(
        modifier
            .width(BAR_WIDTH)
            .fillMaxHeight()
            .padding(top = 8.dp, bottom = 8.dp, end = 4.dp)
            .clip(RoundedCornerShape(BAR_WIDTH / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        if (index == null) return@BoxWithConstraints
        val slot = maxHeight / TitleAlphabet.BUCKETS
        // Sized to the slot so 27 letters fit a phone in landscape as well as the car's portrait
        // panel; clamped so they neither vanish nor balloon.
        val fontSize = with(LocalDensity.current) { (slot * 0.6f).toSp() }
            .let { if (it.value < 9f) 9.sp else if (it.value > 15f) 15.sp else it }
        val current = state.currentBucket
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(index) {
                    awaitEachGesture {
                        fun bucketAtY(y: Float): Int = index.nearestWithTitles(
                            (y / size.height * TitleAlphabet.BUCKETS).toInt().coerceIn(0, TitleAlphabet.BUCKETS - 1),
                        )
                        val down = awaitFirstDown()
                        down.consume()
                        state.scrubTo(bucketAtY(down.position.y), immediate = true)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                state.scrubTo(bucketAtY(change.position.y), immediate = false)
                            }
                        } finally {
                            state.endScrub()
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown &&
                        state.focusPoster()
                }
                // Entering from the grid lands on the letter being looked at, not on whichever
                // letter happens to sit level with the poster that had focus.
                .focusProperties {
                    enter = {
                        val bucket = state.currentBucket
                        if (bucket >= 0 && index.hasTitles(bucket)) state.letterFocus[bucket]
                        else FocusRequester.Default
                    }
                }
                .focusGroup(),
        ) {
            for (bucket in 0 until TitleAlphabet.BUCKETS) {
                LetterCell(
                    label = TitleAlphabet.LABELS[bucket],
                    hasTitles = index.hasTitles(bucket),
                    isCurrent = bucket == current,
                    fontSize = fontSize.value,
                    requester = state.letterFocus[bucket],
                    onFocusChanged = { focused ->
                        if (focused) {
                            state.focusedBucket = bucket
                            // Entering on the letter already shown must not scroll back to its
                            // first title and lose the place.
                            if (bucket != state.currentBucket) state.jumpTo(bucket)
                        } else if (state.focusedBucket == bucket) {
                            state.focusedBucket = -1
                        }
                    },
                    onConfirm = { if (!state.focusPoster()) state.jumpTo(bucket, thenFocusPoster = true) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LetterCell(
    label: Char,
    hasTitles: Boolean,
    isCurrent: Boolean,
    fontSize: Float,
    requester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val focusModifier = if (hasTitles) {
        Modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) }
            // OK acts on key-up, and the key-down is swallowed: moving focus on key-down would hand
            // the key-up to the poster, whose clickable would open the film.
            .onKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (isOk && event.type == KeyEventType.KeyUp) onConfirm()
                isOk
            }
            .focusable()
    } else {
        Modifier
    }
    Box(modifier.then(focusModifier), contentAlignment = Alignment.Center) {
        if (focused) {
            Box(
                Modifier
                    .size(BAR_WIDTH - 12.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
        Text(
            label.toString(),
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
            fontWeight = if (isCurrent || focused) FontWeight.Bold else FontWeight.Medium,
            color = when {
                focused -> colors.onPrimary
                !hasTitles -> colors.onSurfaceVariant.copy(alpha = 0.3f)
                isCurrent -> colors.primary
                else -> colors.onSurface
            },
        )
    }
}

/**
 * The large letter over the grid while scrubbing, where the thumb cannot hide it, and while a letter
 * has d-pad focus, where the bar's own letters are too small to read from the sofa.
 */
@Composable
private fun LetterBubble(label: Char) {
    Box(
        Modifier
            .size(120.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
