/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The vertical category rail shared by Live TV, Movies and Shows.
 *
 * It is vertical because provider category lists run to dozens of entries and a horizontal chip row
 * hides all but the first few — on a d-pad, anything you cannot see you cannot reach.
 *
 * The rail is full-width while focus is on it, then slides shut once focus moves into the content so
 * the content gets the whole width. D-pad LEFT from the content's leftmost element slides it back and
 * drops focus on the selected entry (see [CollapsibleRailState.reopen]).
 */
internal class CollapsibleRailState {
    var expanded by mutableStateOf(true)
        private set

    /** Set when LEFT reopens the rail; [rememberCollapsibleRailState] moves focus once it is laid out. */
    var pendingFocus by mutableStateOf(false)
        internal set

    /**
     * Carried by the currently-selected entry only, so reopening the rail lands focus straight back
     * on the current category.
     */
    val focusRequester = FocusRequester()

    /** Focus moved into the content: give it the rail's width. */
    fun onContentFocused() {
        expanded = false
    }

    /**
     * Called when LEFT leaves the content. Returns true — the key should be consumed — only if the rail
     * was hidden; an open rail is reached by ordinary focus search instead.
     */
    fun reopen(): Boolean {
        if (expanded) return false
        expanded = true
        pendingFocus = true
        return true
    }
}

@Composable
internal fun rememberCollapsibleRailState(): CollapsibleRailState {
    val state = remember { CollapsibleRailState() }
    // The effect waits for the rail to be laid out again before moving focus onto it — a
    // just-revealed node isn't focusable on the very same frame.
    LaunchedEffect(state.pendingFocus) {
        if (state.pendingFocus) {
            delay(50)
            // runCatching: if the selected entry is scrolled out of the rail's list it may not be
            // composed; a second LEFT press then still reaches the rail by ordinary navigation.
            runCatching { state.focusRequester.requestFocus() }
            state.pendingFocus = false
        }
    }
    return state
}

/** The rail itself: a title over a scrolling list of [CategoryRailEntry]s supplied by [content]. */
@Composable
internal fun CategoryRailPanel(
    title: String,
    state: CollapsibleRailState,
    content: LazyListScope.() -> Unit,
) {
    val width by animateDpAsState(
        targetValue = if (state.expanded) 240.dp else 0.dp,
        label = "railWidth",
    )
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .clipToBounds()
            .padding(vertical = 16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
internal fun CategoryRailEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
