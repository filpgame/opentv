/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import java.io.BufferedReader
import java.io.InputStream
import java.text.Normalizer

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * Deliberately reads line-by-line rather than loading the whole playlist into memory:
 * real provider playlists routinely run to tens of megabytes and 50,000+ entries, and
 * loading that as a single String is a reliable way to OOM a cheap Android TV box.
 *
 * Tolerant by design. A malformed entry is skipped, not fatal — one bad line in a
 * 40,000-line playlist must never cost the user their entire channel list.
 *
 * Most provider playlists are exports from an Xtream panel, which lists its whole VOD library
 * alongside the live channels. Each entry is sorted into live, movie or series by the path the
 * panel serves it on (see [kindOf]), so a 300,000-entry export does not land in the TV guide.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    /** `Show Name (2021) [L] S03E02 Episode title` → the show, season, episode and the rest. */
    private val EPISODE_REGEX =
        Regex("""^(.*?)[\s._-]*\bS(\d{1,3})\s*E(\d{1,4})\b[\s._:–-]*(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * An image on episodes of more differently-titled shows than this is the panel's stand-in for
     * a missing one, not anyone's cover: one panel put a single film poster on 24,000 episodes of
     * 1,400 shows. Titles are compared by [baseTitleOf], so the subtitled, dubbed and dated
     * releases of one show share their art freely; two allows a spin-off to share it too.
     */
    private const val MAX_TITLES_PER_COVER = 2

    /** Bracketed and parenthesised tags: `[L]`, `(2021)`, `[Dublado]`. */
    private val TAG_REGEX = Regex("""\[[^\]]*]|\([^)]*\)""")

    /** Category id prefix for the A–Z buckets given to titles the playlist leaves ungrouped. */
    const val ALPHABET_CATEGORY_PREFIX = "az:"

    data class Result(
        /** Live channels. */
        val channels: List<Channel>,
        val movies: List<Movie>,
        val series: List<Series>,
        val episodes: List<Episode>,
        /** Every kind's categories, ready to store. See [parse]. */
        val categories: List<Category>,
        /** Value of `url-tvg`/`x-tvg-url` on the #EXTM3U header, if the playlist declares one. */
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
    ) {
        val isEmpty: Boolean
            get() = channels.isEmpty() && movies.isEmpty() && episodes.isEmpty()
    }

    fun parse(input: InputStream, sourceId: Long): Result =
        input.bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result =
        parse(text.reader().buffered(), sourceId)

    /**
     * Live categories are the playlist's group-titles, alphabetical. Movie and series categories
     * keep the playlist's own order, as an Xtream panel's do. A plain `type=m3u` export carries
     * no group-title at all, so ungrouped movies and series are filed under their initial letter
     * instead: 40,000 titles with no categories could only be reached through search.
     */
    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val seriesByTitle = LinkedHashMap<String, Series>()
        val episodes = ArrayList<Episode>()
        val episodeCounts = HashMap<String, Int>()
        // Logo use, for picking each show's cover once every episode is in. See [MAX_TITLES_PER_COVER].
        val logosByShow = HashMap<String, LinkedHashMap<String, Int>>()
        val titlesByLogo = HashMap<String, MutableSet<String>>()
        val seenStreamIds = HashSet<String>()
        val seenMovieIds = HashSet<String>()
        val seenEpisodeIds = HashSet<String>()
        val liveGroups = HashSet<String>()
        val vodGroups = mapOf(StreamKind.MOVIE to LinkedHashSet<String>(), StreamKind.SERIES to LinkedHashSet())
        val letters = mapOf(StreamKind.MOVIE to sortedSetOf<String>(), StreamKind.SERIES to sortedSetOf())
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        var index = 0

        /** The entry's group-title, or its A–Z bucket when it has none. */
        fun vodCategory(kind: StreamKind, group: String?, title: String): String {
            if (group != null) {
                vodGroups.getValue(kind) += group
                return group
            }
            val letter = initialOf(VodTitleCleaner.clean(title))
            letters.getValue(kind) += letter
            return ALPHABET_CATEGORY_PREFIX + letter
        }

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    declaredEpgUrl = attributes["url-tvg"]
                        ?: attributes["x-tvg-url"]
                        ?: declaredEpgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                }

                // Any other directive: ignore, but keep the pending EXTINF alive.
                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank()) {
                        // A URL with no preceding #EXTINF. Nothing sensible to label it with.
                        skipped++
                    } else {
                        val attributes = pendingAttributes
                        val group = attributes["group-title"]?.takeIf { it.isNotBlank() }
                        val logo = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.isNotBlank() }
                        val urlId = "url:${stableHash(line)}"

                        when (kindOf(line)) {
                            StreamKind.LIVE -> {
                                // Providers list SD/HD/FHD variants under one tvg-id. The first
                                // keeps the tvg id; the rest fall back to their URL, so they are
                                // kept as variants instead of colliding on the unique index. The
                                // URL is claimed either way, so a stream listed twice stays one row.
                                val streamId = attributes["tvg-id"]
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { "tvg:$it" }
                                    ?.takeIf { it !in seenStreamIds }
                                    ?: urlId
                                if (seenStreamIds.add(streamId)) {
                                    seenStreamIds += urlId
                                    if (group != null) liveGroups += group
                                    channels += Channel(
                                        sourceId = sourceId,
                                        streamId = streamId,
                                        name = name,
                                        categoryId = group,
                                        logoUrl = logo,
                                        epgChannelId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                                        number = pendingNumber,
                                        streamUrl = line,
                                        sortIndex = index++,
                                    )
                                }
                            }

                            StreamKind.MOVIE -> if (seenMovieIds.add(urlId)) {
                                movies += Movie(
                                    sourceId = sourceId,
                                    streamId = urlId,
                                    name = name,
                                    categoryId = vodCategory(StreamKind.MOVIE, group, name),
                                    posterUrl = logo,
                                    rating = null,
                                    year = null,
                                    plot = null,
                                    durationSeconds = null,
                                    containerExtension = extensionOf(line),
                                    streamUrl = line,
                                )
                            }

                            StreamKind.SERIES -> if (seenEpisodeIds.add(urlId)) {
                                val match = EPISODE_REGEX.matchEntire(name)
                                val title = match?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: name
                                val show = seriesByTitle.getOrPut(title) {
                                    Series(
                                        sourceId = sourceId,
                                        // Playlists give a series no id of its own; its title is
                                        // the stable key, and what favourites sync across devices.
                                        seriesId = title,
                                        name = title,
                                        categoryId = vodCategory(StreamKind.SERIES, group, title),
                                        posterUrl = null,
                                        rating = null,
                                        year = null,
                                        plot = null,
                                    )
                                }
                                if (logo != null) {
                                    logosByShow.getOrPut(title) { LinkedHashMap() }.merge(logo, 1, Int::plus)
                                    val titles = titlesByLogo.getOrPut(logo) { HashSet(2) }
                                    if (titles.size <= MAX_TITLES_PER_COVER) titles += baseTitleOf(title)
                                }
                                episodes += Episode(
                                    sourceId = sourceId,
                                    seriesId = title,
                                    episodeId = urlId,
                                    season = match?.groupValues?.get(2)?.toInt() ?: 1,
                                    // No SxxEyy in the name: number the show's entries in order.
                                    episodeNumber = match?.groupValues?.get(3)?.toInt()
                                        ?: episodeCounts.merge(title, 1, Int::plus)!!,
                                    title = match?.groupValues?.get(4)?.trim()?.ifEmpty { null } ?: title,
                                    plot = null,
                                    durationSeconds = null,
                                    stillUrl = logo,
                                    streamUrl = line,
                                )
                            }
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                }
            }
        }

        val categories = ArrayList<Category>()
        liveGroups.sorted().forEachIndexed { i, name ->
            categories += Category(id = name, sourceId = sourceId, name = name, kind = StreamKind.LIVE, sortIndex = i)
        }
        for (kind in listOf(StreamKind.MOVIE, StreamKind.SERIES)) {
            val groups = vodGroups.getValue(kind)
            groups.forEachIndexed { i, name ->
                categories += Category(id = name, sourceId = sourceId, name = name, kind = kind, sortIndex = i)
            }
            letters.getValue(kind).forEachIndexed { i, letter ->
                categories += Category(
                    id = ALPHABET_CATEGORY_PREFIX + letter,
                    sourceId = sourceId,
                    name = letter,
                    kind = kind,
                    sortIndex = groups.size + i,
                )
            }
        }

        fun isPlaceholder(logo: String?) =
            logo != null && titlesByLogo.getValue(logo).size > MAX_TITLES_PER_COVER

        return Result(
            channels = channels,
            movies = movies,
            // The image a show's episodes carry most often, which is its cover when the panel
            // repeats one, and otherwise one of its own episode stills.
            series = seriesByTitle.values.map { show ->
                val cover = logosByShow[show.seriesId]?.entries
                    ?.filterNot { isPlaceholder(it.key) }
                    ?.maxByOrNull { it.value }
                    ?.key
                show.copy(posterUrl = cover)
            },
            episodes = if (titlesByLogo.values.none { it.size > MAX_TITLES_PER_COVER }) episodes
            else episodes.map { if (isPlaceholder(it.stillUrl)) it.copy(stillUrl = null) else it },
            categories = categories,
            declaredEpgUrl = declaredEpgUrl,
            skippedEntries = skipped,
        )
    }

    /**
     * What an entry is, read from its URL. An Xtream panel serves each type from its own path:
     * `/movie/<user>/<pass>/<id>.mp4`, `/series/<user>/<pass>/<id>.mkv`, and live channels as
     * `/<user>/<pass>/<id>` or `/live/<user>/<pass>/<id>.ts`. The type segment is read from the
     * end, so a panel behind a path prefix still classifies. Anything else is live.
     */
    internal fun kindOf(url: String): StreamKind {
        val path = url.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 4) return StreamKind.LIVE
        return when (segments[segments.size - 4].lowercase()) {
            "movie", "movies" -> StreamKind.MOVIE
            "series" -> StreamKind.SERIES
            else -> StreamKind.LIVE
        }
    }

    /** A show's title without its tags or punctuation: `Elsbeth [L]` and `Elsbeth (2024)` → `elsbeth`. */
    internal fun baseTitleOf(title: String): String =
        TAG_REGEX.replace(title, "").filter { it.isLetterOrDigit() }.lowercase()

    /** `A`–`Z` by the title's first character with accents folded (`É` → `E`); `#` for the rest. */
    internal fun initialOf(title: String): String {
        val first = title.firstOrNull() ?: return "#"
        val folded = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
        return if (folded in 'A'..'Z') folded.toString() else "#"
    }

    private fun extensionOf(url: String): String? =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 2..4 }

    /**
     * The display name is whatever follows the last comma on the #EXTINF line. Falling back
     * to `tvg-name` matters because a fair number of playlists emit `#EXTINF:-1 ...,` with
     * nothing after the comma.
     */
    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(line: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

    /** FNV-1a. Stable across processes and platforms, unlike [String.hashCode] guarantees. */
    private fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325uL.toLong() // FNV-1a 64-bit offset basis
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV-1a 64-bit prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
