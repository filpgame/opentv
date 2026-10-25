/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.findActivity
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.ui.CategoryRailEntry
import app.opentv.ui.CategoryRailPanel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.rememberCollapsibleRailState
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV: a category rail on the left, the channel list on the right.
 *
 * The rail is vertical because provider category lists run to dozens of entries and a
 * horizontal chip row hides all but the first few — on a d-pad, anything you cannot see
 * you cannot reach. Favourites and All are pinned at the top.
 *
 * The list shows what is on *now* and *next* against every channel. That doubles as a
 * permanent, glanceable health indicator for the guide: when the EPG stops updating, these
 * lines say so honestly instead of going quietly stale.
 */
@Composable
fun HomeScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit = { _, _, _, _ -> },
    viewModel: ChannelsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val previewEnabled by settings.guidePreviewVideo.collectAsState()
    val channelLayout by settings.channelLayout.collectAsState()

    val categories by viewModel.visibleCategoryGroups.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val windowStart by viewModel.windowStartMillis.collectAsState()
    // How many days ahead the guide is scrolled (0 = live now). Drives the ‹ Today › nav.
    val guideDayOffset by viewModel.guideDayOffset.collectAsState()
    // Tri-state: null = still checking the catalogue, true = channels on disk, false = confirmed
    // empty. Drives the choice between the loading spinner and a recoverable error below.
    val channelsPresent by viewModel.channelsPresent.collectAsState()

    // Two separate ideas, on purpose:
    //  - highlightedRow: where the d-pad is in the grid. Moves freely with up/down.
    //  - selectedRow: what the preview pane plays. Only changes when you press OK, so scrolling
    //    the list is calm and silent instead of re-tuning a stream on every keypress.
    var highlightedRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    var selectedRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    val previewSound by settings.guidePreviewSound.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val actionScope = rememberCoroutineScope()
    // The programme the user pressed OK on in the grid — opens its detail / catch-up dialog.
    var programmeTarget by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme>?>(null) }
    // The channel whose OK menu (Watch / Open with…) is open.
    var channelMenu by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }

    // Collapses once focus moves into the guide (onFocusRow) so the grid gets the whole width;
    // d-pad LEFT from the guide's leftmost (channel) column slides it back. Shared with Movies/Shows.
    val rail = rememberCollapsibleRailState()

    // Re-evaluate "now" once a minute so progress bars advance without leaving the screen.
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            viewModel.tick()
            delay(60_000)
        }
    }

    // Keep both valid as the list changes (e.g. switching category): stay on the same channel if
    // it's still present, otherwise fall back to the top of the new list.
    LaunchedEffect(rows) {
        highlightedRow = rows.firstOrNull { it.key == highlightedRow?.key } ?: rows.firstOrNull()
        selectedRow = rows.firstOrNull { it.key == selectedRow?.key } ?: rows.firstOrNull()
    }

    // ---- Live preview player -----------------------------------------------------------------
    // One muted player, reused. It only ever decodes while the guide is the foreground screen,
    // and is stopped before any hand-off to full-screen, so the box never runs two decoders at
    // once — the thing that used to lock up cheap sticks.
    val previewScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val previewController = remember {
        PlayerController(context, previewScope, graph.httpClient, subtitlesEnabled = false, preview = true)
            .also { it.player.volume = 0f }
    }
    DisposableEffect(Unit) {
        onDispose {
            previewController.release()
            previewScope.cancel()
        }
    }

    // Every jump to full-screen live goes through here: hand the player the list being browsed so it
    // can zap channel up/down, and stop the preview so two decoders never run at once.
    fun startLive(channel: Channel) {
        PlaybackQueue.items = rows.map {
            PlaybackQueue.Item(it.primary.id, it.primary.shownName, it.primary.logoUrl, it.primary.number)
        }
        previewController.stop()
        onPlayChannel(channel)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> screenResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    screenResumed = false
                    previewController.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hold the screen awake while the guide's live preview is playing — otherwise the box's
    // screensaver fires while you're browsing with a channel running in the preview pane.
    DisposableEffect(previewEnabled, screenResumed) {
        val window = context.findActivity()?.window
        if (previewEnabled && screenResumed) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Preview audio follows the setting; muted by default so browsing stays quiet.
    LaunchedEffect(previewSound) {
        previewController.player.volume = if (previewSound) 1f else 0f
    }

    // Tune the preview to the highlighted channel, so it follows the d-pad as you browse — the
    // standard TV-guide behaviour. Debounced, so holding a direction doesn't re-tune every step.
    LaunchedEffect(highlightedRow?.key, previewEnabled, screenResumed) {
        val row = highlightedRow
        if (!previewEnabled || !screenResumed || row == null) {
            previewController.stop()
            return@LaunchedEffect
        }
        val channel = row.primary
        val source = graph.sourceRepository.byId(channel.sourceId)
        previewController.play(
            PlayerController.Request(
                url = channel.streamUrl,
                title = channel.shownName,
                userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                isLive = true,
            ),
            debounce = true,
        )
    }

    Row(Modifier.fillMaxSize()) {

        // ---- Category rail -----------------------------------------------------------------
        // The top "Search channels" bar was removed — Search now lives in the global nav rail.
        CategoryRailPanel(stringResource(R.string.nav_live_tv), rail) {
            // Provider switch — only when there's more than one source. Lets the user keep
            // several playlists and flip between them (cardiodoc's request); "All" folds them.
            if (sources.size > 1) {
                item(key = "provider-header") {
                    Text(
                        stringResource(R.string.channels_manager_source_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                item(key = "provider-all") {
                    CategoryRailEntry(
                        label = stringResource(R.string.channels_manager_all_sources),
                        selected = selectedSource == null,
                        onClick = { viewModel.selectSource(null) },
                    )
                }
                items(sources, key = { "src-${it.id}" }) { source ->
                    CategoryRailEntry(
                        label = source.name,
                        selected = selectedSource == source.id,
                        onClick = { viewModel.selectSource(source.id) },
                    )
                }
                item(key = "provider-divider") { Spacer(Modifier.height(10.dp)) }
            }
            // The currently-selected entry carries the rail's FocusRequester, so reopening the
            // rail (d-pad LEFT in the guide) lands focus straight back on the current category.
            item {
                CategoryRailEntry(
                    label = stringResource(R.string.guide_favourites),
                    selected = favouritesOnly,
                    onClick = viewModel::selectFavourites,
                    modifier = if (favouritesOnly) Modifier.focusRequester(rail.focusRequester) else Modifier,
                )
            }
            item {
                val allSelected = !favouritesOnly && selectedCategory == null
                CategoryRailEntry(
                    label = stringResource(R.string.guide_all_channels),
                    selected = allSelected,
                    onClick = { viewModel.selectCategory(null) },
                    modifier = if (allSelected) Modifier.focusRequester(rail.focusRequester) else Modifier,
                )
            }
            items(categories, key = { it.key }) { group ->
                val groupSelected = !favouritesOnly && selectedCategory == group.key
                CategoryRailEntry(
                    label = group.label,
                    selected = groupSelected,
                    onClick = { viewModel.selectCategory(group.key) },
                    modifier = if (groupSelected) Modifier.focusRequester(rail.focusRequester) else Modifier,
                )
            }
        }

        // ---- Preview + guide ---------------------------------------------------------------
        Column(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                when {
                    favouritesOnly -> NoFavouritesState()
                    // Work genuinely in progress: a sync is running, the catalogue check hasn't
                    // returned yet, or channels ARE on disk and the guide is still building. A
                    // large provider takes a while, and showing "No channels" during it reads as
                    // failure — which is how someone concludes an app is broken thirty seconds
                    // after installing it.
                    isSyncing || channelsPresent == null || channelsPresent == true -> LoadingState(isSyncing)
                    // Nothing is syncing and the catalogue is confirmed empty. With a provider
                    // configured, the last load failed or returned nothing — surface a clear error
                    // with Retry and a way back to setup instead of spinning forever.
                    hasSources -> ChannelsErrorState(onRetry = onRefresh, onEditProvider = onAddSource)
                    else -> EmptyState(onAddSource)
                }
            } else {
                fun goFullscreen(channel: Channel) = startLive(channel)

                val dayLabel = when (guideDayOffset) {
                    0 -> stringResource(R.string.guide_today)
                    1 -> stringResource(R.string.guide_tomorrow)
                    else -> remember(windowStart) {
                        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStart))
                    }
                }
                GuidePreview(
                    row = highlightedRow,
                    nowMillis = nowMillis,
                    onWatch = { highlightedRow?.let { goFullscreen(it.primary) } },
                    onRefresh = onRefresh,
                    onAddSource = onAddSource,
                    previewPlayer = if (previewEnabled) previewController.player else null,
                    dayLabel = dayLabel,
                    canGoPrevDay = guideDayOffset > 0,
                    onPrevDay = { viewModel.nudgeGuideDay(-1) },
                    onNextDay = { viewModel.nudgeGuideDay(1) },
                )
                // Shared by both layouts: focus follows the highlight and collapses the rail; LEFT
                // from the leftmost element reopens the rail (consumed only when it was hidden).
                val onFocusChannel: (ChannelsViewModel.Row) -> Unit = {
                    highlightedRow = it
                    rail.onContentFocused()
                }
                val onExitLeftChannel: () -> Boolean = rail::reopen
                if (channelLayout == AppSettings.ChannelLayout.LIST) {
                    ChannelList(
                        rows = rows,
                        selectedKey = highlightedRow?.key,
                        onSelectRow = { row -> channelMenu = row },
                        onFocusRow = onFocusChannel,
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                    )
                } else {
                    GuideGrid(
                        rows = rows,
                        windowStartMillis = windowStart,
                        dayOffset = guideDayOffset,
                        selectedKey = highlightedRow?.key,
                        // OK on a channel opens its menu (Watch, Open with…). The preview already
                        // follows the highlight as you browse.
                        onSelectRow = { row -> channelMenu = row },
                        onFocusRow = onFocusChannel,
                        onProgramme = { row, programme -> programmeTarget = row to programme },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                    )
                }
            }
        }
    }

    // The detail dialog for a programme picked in the grid. A finished programme on a catch-up
    // channel can be played back from the archive; anything else just offers the channel itself.
    programmeTarget?.let { (targetRow, programme) ->
        val channel = targetRow.primary
        val isPast = programme.endUtcMillis <= nowMillis
        Dialog(onDismissRequest = { programmeTarget = null }) {
            Column(
                Modifier
                    .width(440.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            ) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatTime(programme.startUtcMillis)}–${formatTime(programme.endUtcMillis)}   ${channel.shownName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (isPast && channel.tvArchive) {
                    ActionRow(stringResource(R.string.guide_watch_from_start), primary = true) {
                        actionScope.launch {
                            val source = graph.sourceRepository.byId(channel.sourceId)
                            if (source == null) {
                                Toast.makeText(context, context.getString(R.string.guide_catchup_link_failed), Toast.LENGTH_SHORT).show()
                            } else {
                                val mins = (programme.durationMillis / 60000).toInt().coerceAtLeast(1)
                                val url = graph.xtreamApi.catchupUrl(source, channel.streamId, programme.startUtcMillis, mins)
                                onPlayCatchup(
                                    "catchup:${channel.id}:${programme.startUtcMillis}",
                                    url,
                                    "${channel.shownName} — ${programme.title}",
                                    source.userAgent,
                                )
                            }
                        }
                        programmeTarget = null
                    }
                }
                ActionRow(stringResource(R.string.guide_watch_channel)) {
                    programmeTarget = null
                    startLive(channel)
                }
                ActionRow(stringResource(R.string.common_cancel)) { programmeTarget = null }
            }
        }
    }

    // The channel menu — opened by pressing OK on a channel. A plain vertical list, so it's reliable
    // on any remote — no fiddly timeline navigation needed.
    channelMenu?.let { menuRow ->
        val channel = menuRow.primary
        val nowProg = menuRow.now
        Dialog(onDismissRequest = { channelMenu = null }) {
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                Text(
                    channel.shownName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                nowProg?.let {
                    Text(
                        stringResource(R.string.guide_now_title, it.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))

                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ActionRow(stringResource(R.string.guide_watch)) {
                        channelMenu = null
                        startLive(channel)
                    }
                    ActionRow(stringResource(R.string.guide_open_external)) {
                        channelMenu = null
                        actionScope.launch {
                            val ua = graph.sourceRepository.byId(channel.sourceId)?.userAgent ?: "OpenTV/0.1 (Android)"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(channel.streamUrl), "video/*")
                                putExtra("title", channel.shownName)
                                // MX Player / VLC read the User-Agent from this header extra.
                                putExtra("headers", arrayOf("User-Agent", ua))
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.guide_play_channel_with))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    ActionRow(stringResource(R.string.common_cancel)) { channelMenu = null }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ChannelRow(
    row: ChannelsViewModel.Row,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val background =
        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            // Logo, name, guide — and nothing else. Quality is a playback decision;
            // its switch lives in the player, not as clutter on every row.
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.now?.let { "${formatTime(it.startUtcMillis)}  ${it.title}" }
                    // Honest rather than blank. A user who sees this on every channel knows
                    // the guide is the problem, not their provider.
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.now?.let { programme ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { programme.progressAt(now) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            row.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Default.Star
                else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite) else stringResource(R.string.common_favourite),
            )
        }
    }
}

@Composable
private fun LoadingState(isSyncing: Boolean) {
    val s by app.opentv.core.StatusBus.message.collectAsState()
    val p by app.opentv.core.StatusBus.progress.collectAsState()
    val status = s
    val progress = p
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            if (progress == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.guide_loading_channels), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    // Actively assembling — this is work in progress, not a failure.
                    status != null -> status
                    isSyncing -> stringResource(R.string.guide_fetching_desc)
                    // Channels are already on the device; the guide is being built from them.
                    // A big provider takes a moment (longer on this debug build) — not a failure.
                    else -> stringResource(R.string.guide_building_desc)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (progress != null) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun NoFavouritesState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_favourites_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_no_favourites_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown when a provider is configured but there are no channels to display and nothing is
 * syncing — i.e. the last catalogue load failed or came back empty. Replaces the endless
 * "Loading your channels" spinner with something the user can act on.
 */
@Composable
private fun ChannelsErrorState(onRetry: () -> Unit, onEditProvider: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Text(
                stringResource(R.string.guide_load_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_load_failed_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = onRetry) {
                    Text(stringResource(R.string.common_try_again))
                }
                androidx.compose.material3.OutlinedButton(onClick = onEditProvider) {
                    Text(stringResource(R.string.guide_edit_provider))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAddSource: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_channels_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_add_provider_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAddSource) { Text(stringResource(R.string.guide_add_provider_button)) }
        }
    }
}

/** UTC in the database, device zone on screen. Converted here and nowhere else. */
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = timeFormat.format(Date(utcMillis))
