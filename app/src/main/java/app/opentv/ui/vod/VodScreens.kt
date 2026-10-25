/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.VodTitle
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.VariantGroup
import app.opentv.ui.CategoryRailEntry
import app.opentv.ui.CategoryRailPanel
import app.opentv.ui.VodViewModel
import app.opentv.ui.rememberCollapsibleRailState
import coil.compose.AsyncImage

/**
 * Movies: a modern, row-based home — Continue Watching, My list, Recommended, Recently added and a
 * row per genre, each a horizontally-scrolling shelf of poster cards. The category rail down the left
 * (the same one Live TV has) keeps whole-category browsing one press away and slides shut once focus
 * is on the posters. Clicking a film opens its detail page rather than playing straight away, the
 * streaming-app convention, and the place to pick between its dubbed, subtitled and 4K versions.
 */
@Composable
fun MoviesScreen(
    onOpenMovie: (Movie) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val categories by viewModel.movieCategories.collectAsState()
    val resume by viewModel.continueWatching.collectAsState()
    val myList by viewModel.myListMovies.collectAsState()
    val recommended by viewModel.recommendedMovies.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedMovies.collectAsState()
    val genreRows by viewModel.movieGenreRows.collectAsState()
    val movieBrowse by viewModel.movieBrowse.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedVodSource.collectAsState()

    // Pull the movie library the first time this tab is opened, not at login; refresh the computed
    // home rows (recommended, by-genre) on open too — cheap, and covers a library already on disk.
    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    // null = the curated home rows; [VodViewModel.MY_LIST] or a category id = that full grid.
    var browseCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // The view model reads the category; the screen only says which one. Keying on the saved value
    // (rather than calling from the rail's click) also re-issues the read after a restore, when the
    // view model is fresh but the screen still remembers a category.
    LaunchedEffect(browseCategory) { viewModel.selectMovieCategory(browseCategory) }

    val hasContent = resume.isNotEmpty() || myList.isNotEmpty() || recommended.isNotEmpty() ||
        recentlyAdded.isNotEmpty() || genreRows.isNotEmpty()

    VodWithRail(
        title = stringResource(R.string.nav_movies),
        sources = sources,
        selectedSource = selectedSource,
        onSelectSource = { id -> browseCategory = null; viewModel.selectVodSource(id) },
        categories = categories.map { it.id to it.name },
        selected = browseCategory,
        onSelect = { id -> browseCategory = id },
        onOpenSearch = onOpenSearch,
    ) {
        when {
            browseCategory != null -> VodCategoryGrid(
                browse = movieBrowse,
                categoryId = browseCategory,
                loadingText = stringResource(R.string.vod_loading_movies),
                emptyTitle = stringResource(R.string.vod_no_movies),
                onToggleFilter = viewModel::toggleMovieFilter,
                onOpen = onOpenMovie,
            )
            !hasContent -> when {
                vodLoading || isSyncing -> LoadingVod(stringResource(R.string.vod_loading_movies))
                hasSources -> EmptyVod(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_provider))
                else -> EmptyVod(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_add))
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (resume.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(resume, onResume) }
                if (myList.isNotEmpty()) item(key = "mylist") {
                    VodPosterRow(stringResource(R.string.vod_my_list), myList, onOpenMovie)
                }
                if (recommended.isNotEmpty()) item(key = "rec") {
                    VodPosterRow(stringResource(R.string.vod_recommended), recommended, onOpenMovie)
                }
                if (recentlyAdded.isNotEmpty()) item(key = "recent") {
                    VodPosterRow(stringResource(R.string.vod_recently_added), recentlyAdded, onOpenMovie)
                }
                items(genreRows, key = { "g:${it.genre}" }) { group ->
                    VodPosterRow(group.genre, group.items, onOpenMovie)
                }
            }
        }
    }
}

/**
 * Shows: the same row-based home as Movies (Continue Watching, My list, Recently added, genre rows)
 * with the category rail for whole-category browsing. A show opens its detail page — where episodes
 * are fetched on demand, since pulling every episode of every series up front is what makes a first
 * sync take forever.
 */
@Composable
fun SeriesScreen(
    onOpenSeries: (Series) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val categories by viewModel.seriesCategories.collectAsState()
    val resume by viewModel.continueWatching.collectAsState()
    val myList by viewModel.myListSeries.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedSeries.collectAsState()
    val genreRows by viewModel.seriesGenreRows.collectAsState()
    val seriesBrowse by viewModel.seriesBrowse.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedVodSource.collectAsState()

    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    var browseCategory by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(browseCategory) { viewModel.selectSeriesCategory(browseCategory) }

    val hasContent = resume.isNotEmpty() || myList.isNotEmpty() || recentlyAdded.isNotEmpty() ||
        genreRows.isNotEmpty()

    VodWithRail(
        title = stringResource(R.string.nav_shows),
        sources = sources,
        selectedSource = selectedSource,
        onSelectSource = { id -> browseCategory = null; viewModel.selectVodSource(id) },
        categories = categories.map { it.id to it.name },
        selected = browseCategory,
        onSelect = { id -> browseCategory = id },
        onOpenSearch = onOpenSearch,
    ) {
        when {
            browseCategory != null -> VodCategoryGrid(
                browse = seriesBrowse,
                categoryId = browseCategory,
                loadingText = stringResource(R.string.vod_loading_shows),
                emptyTitle = stringResource(R.string.vod_no_shows),
                onToggleFilter = viewModel::toggleSeriesFilter,
                onOpen = onOpenSeries,
            )
            !hasContent -> when {
                vodLoading || isSyncing -> LoadingVod(stringResource(R.string.vod_loading_shows))
                hasSources -> EmptyVod(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_provider))
                else -> EmptyVod(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_add))
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (resume.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(resume, onResume) }
                if (myList.isNotEmpty()) item(key = "mylist") {
                    VodPosterRow(stringResource(R.string.vod_my_list), myList, onOpenSeries)
                }
                if (recentlyAdded.isNotEmpty()) item(key = "recent") {
                    VodPosterRow(stringResource(R.string.vod_recently_added), recentlyAdded, onOpenSeries)
                }
                items(genreRows, key = { "g:${it.genre}" }) { group ->
                    VodPosterRow(group.genre, group.items, onOpenSeries)
                }
            }
        }
    }
}

// ---- Whole-category browse grids ---------------------------------------------------------------

/**
 * One category's titles (or My list) as a poster grid, with its filter chips above. Versions are
 * already folded into one card each, badged, and filtered by the view model off the main thread.
 * Until the category being asked for ([categoryId]) has landed, a spinner stands in, so a tap
 * answers at once instead of freezing on the read.
 */
@Composable
private fun <T : VodTitle> VodCategoryGrid(
    browse: VodViewModel.CategoryBrowse<VariantGroup<T>>,
    categoryId: String?,
    loadingText: String,
    emptyTitle: String,
    onToggleFilter: (VodViewModel.VodFilter) -> Unit,
    onOpen: (T) -> Unit,
) {
    if (browse.loading || browse.categoryId != categoryId) {
        LoadingVod(loadingText, showProviderNote = false)
        return
    }
    val groups = browse.items
    if (groups.isEmpty()) {
        if (categoryId == VodViewModel.MY_LIST) {
            EmptyVod(stringResource(R.string.vod_my_list_empty_title), stringResource(R.string.vod_my_list_empty_body))
        } else {
            EmptyVod(emptyTitle, stringResource(R.string.vod_category_empty))
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (browse.filters.isNotEmpty()) FilterChipRow(browse.filters, browse.filter, onToggleFilter)
        // A new list starts at the top; a filter that empties most of a scrolled grid would otherwise
        // leave the view past its end.
        val gridState = remember(categoryId, browse.filter) { LazyGridState() }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(groups, key = { it.primary.id }) { group ->
                VodPosterCard(group, onOpen)
            }
        }
    }
}

/**
 * Dublado · Legendado · Nacional · 4K. One at a time, pressed again to clear: on a remote a single
 * choice is one press, where combinations would need a "clear all" and an explanation of how they
 * combine. Only the chips that would change the list are passed in.
 */
@Composable
private fun FilterChipRow(
    filters: List<VodViewModel.VodFilter>,
    active: VodViewModel.VodFilter?,
    onToggle: (VodViewModel.VodFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        filters.forEach { filter ->
            FilterChip(
                label = when (filter) {
                    VodViewModel.VodFilter.DUBBED -> stringResource(R.string.vod_filter_dubbed)
                    VodViewModel.VodFilter.SUBTITLED -> stringResource(R.string.vod_filter_subtitled)
                    VodViewModel.VodFilter.NATIONAL -> stringResource(R.string.vod_filter_national)
                    VodViewModel.VodFilter.UHD -> stringResource(R.string.vod_filter_4k)
                },
                selected = filter == active,
                onClick = { onToggle(filter) },
            )
        }
    }
}

/**
 * A focusable, tappable pill: the app's focus cue (primary fill and border) when focused, a tick and
 * the container colour when on. Padded to a comfortable target for a finger on a car screen.
 */
@Composable
internal fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = content, maxLines = 1)
    }
}

// ---- Shared shelves ----------------------------------------------------------------------------

/** A titled horizontal shelf of poster cards, one per title. Shared by the home and the detail's "more like this". */
@Composable
internal fun <T : VodTitle> VodPosterRow(title: String, groups: List<VariantGroup<T>>, onOpen: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(groups, key = { it.primary.id }) { group -> VodPosterCard(group, onOpen) }
        }
    }
}

/** One title's card: its poster version's art and title, badged with what versions exist. */
@Composable
private fun <T : VodTitle> VodPosterCard(group: VariantGroup<T>, onOpen: (T) -> Unit) {
    PosterCard(
        title = group.primary.displayTitle,
        posterUrl = group.primary.posterUrl,
        subtitle = group.primary.year?.toString(),
        rating = group.primary.rating,
        badges = versionBadges(group),
        onClick = { onOpen(group.primary) },
    )
}

/**
 * DUB / LEG / 4K for a title's versions. DUB only beside LEG: on a list that never tags subtitles an
 * untagged title is not known to be dubbed, and badging every poster would say nothing.
 */
@Composable
internal fun versionBadges(group: VariantGroup<*>): List<String> {
    val dub = stringResource(R.string.vod_badge_dub)
    val sub = stringResource(R.string.vod_badge_sub)
    return buildList {
        if (group.offersLanguageChoice) add(dub)
        if (group.subtitled) add(sub)
        if (group.uhd) add("4K")
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * The reusable poster card: art, title and an optional year, with a rating chip, version badges and
 * a resume progress bar drawn over the art where the data is there. The focused card scales up and
 * gains a primary border — the app's established focus cue — and, being focusable, the lazy row
 * brings it into view on its own.
 */
@Composable
internal fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rating: Double? = null,
    badges: List<String> = emptyList(),
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    Column(
        modifier
            .width(POSTER_WIDTH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            rating?.takeIf { it > 0.0 }?.let {
                Badge(
                    text = "★ ${formatRating(it)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
            if (badges.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    badges.forEach { Badge(text = it, highlight = true) }
                }
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A small rounded chip drawn over poster art — a rating or a quality label. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.primary
    else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f)
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimary else androidx.compose.ui.graphics.Color.White
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Continue watching -------------------------------------------------------------------------

@Composable
internal fun ContinueWatchingRow(
    items: List<VodViewModel.ResumeItem>,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.vod_continue_watching))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.mediaKey }) { item ->
                ResumeCard(item) { onResume(item.mediaKey, item.streamUrl, item.title) }
            }
        }
    }
}

/** A landscape resume thumbnail with a progress fill — a movie or an episode part-way through. */
@Composable
private fun ResumeCard(item: VodViewModel.ResumeItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "resumeScale")
    Column(
        Modifier
            .width(190.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(107.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    else Modifier,
                ),
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Category rail -----------------------------------------------------------------------------

/**
 * The Movies/Shows layout: the same vertical category rail as Live TV on the left, the search entry
 * and [content] on the right. A rail rather than a chip strip for the same reason as Live — provider
 * category lists run to dozens of entries, and on a d-pad anything you cannot see you cannot reach.
 * "All" returns to the curated rows; "My list" and each category open a full grid.
 *
 * The rail slides shut once focus enters the content, and d-pad LEFT from the content's leftmost
 * element slides it back onto the selected category. That is done on the content container as a
 * whole, so the posters and shelves need no focus callbacks of their own.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VodWithRail(
    title: String,
    sources: List<Source>,
    selectedSource: Long?,
    onSelectSource: (Long?) -> Unit,
    categories: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val rail = rememberCollapsibleRailState()
    Row(Modifier.fillMaxSize()) {
        CategoryRailPanel(title, rail) {
            // Provider switch — only when there's more than one source: browse just that provider's
            // categories, or "All sources" to fold them.
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
                        onClick = { onSelectSource(null) },
                    )
                }
                items(sources, key = { "src-${it.id}" }) { source ->
                    CategoryRailEntry(
                        label = source.name,
                        selected = selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                    )
                }
                item(key = "provider-divider") { Spacer(Modifier.height(10.dp)) }
            }
            // The selected entry carries the rail's FocusRequester, so reopening the rail lands
            // focus straight back on the current category.
            item(key = "all") {
                CategoryRailEntry(
                    label = stringResource(R.string.vod_all),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    modifier = if (selected == null) Modifier.focusRequester(rail.focusRequester) else Modifier,
                )
            }
            item(key = "my-list") {
                CategoryRailEntry(
                    label = stringResource(R.string.vod_my_list),
                    selected = selected == VodViewModel.MY_LIST,
                    onClick = { onSelect(VodViewModel.MY_LIST) },
                    modifier = if (selected == VodViewModel.MY_LIST) Modifier.focusRequester(rail.focusRequester) else Modifier,
                )
            }
            items(categories, key = { it.first }) { (id, name) ->
                CategoryRailEntry(
                    label = name,
                    selected = selected == id,
                    onClick = { onSelect(id) },
                    modifier = if (selected == id) Modifier.focusRequester(rail.focusRequester) else Modifier,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                // Order matters: each of these applies to the focus group that follows it.
                .onFocusChanged { if (it.hasFocus) rail.onContentFocused() }
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Left && rail.reopen()) FocusRequester.Cancel
                        else FocusRequester.Default
                    }
                }
                .focusGroup(),
        ) {
            SearchAffordance(onOpenSearch)
            // Weighted so the shelves fill the space under the fixed search header, exactly and
            // unambiguously — the same reason Live TV weights its guide grid.
            Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
    }
}

// ---- Search / loading / empty ------------------------------------------------------------------

/**
 * The search entry at the top of the Movies and Shows home. The rail's global search already covers
 * movies and series; this makes it reachable without leaving the tab. Focusable for d-pad on TV and
 * tappable on touch, it just opens the existing search screen.
 */
@Composable
private fun SearchAffordance(onOpenSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onOpenSearch)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (focused) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(Icons.Filled.Search, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.vod_search_hint),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

@Composable
private fun EmptyVod(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingVod(message: String, showProviderNote: Boolean = true) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.headlineSmall)
            // The "couple of minutes" note is about the first provider download; opening a category
            // that is already on disk is a local read and the note would only worry people.
            if (showProviderNote) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.vod_large_provider_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Poster shelf card width; the grid uses an adaptive min size close to this. */
private val POSTER_WIDTH = 140.dp

/** Rating to one decimal place, locale-independent (the "★" is drawn beside it). */
internal fun formatRating(rating: Double): String = String.format(java.util.Locale.US, "%.1f", rating)
