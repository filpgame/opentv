/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpgChannelAliasDao
import app.opentv.data.db.EpgFeedDao
import app.opentv.data.db.ProgrammeDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the electronic programme guide, end to end: feeds in, matched channels out.
 *
 * ## The feed model
 *
 * Guide data can come from three places at once — the provider's own XMLTV (usually thin or
 * empty), the curated free sources shipped with the app, and URLs the user adds. Each is an
 * [EpgFeed] row; every enabled feed is downloaded and merged into one guide. After a sync,
 * [EpgMatcher] joins provider channels to guide channels by normalised name, which is what
 * makes a free national guide light up channels named `UK| BBC ONE FHD`.
 *
 * ## The failure this class exists to prevent
 *
 * The standard way to refresh an EPG is: delete everything, download the new XMLTV, insert
 * it. It is simple and it is why so many IPTV players lose their guide — one stalled
 * download and the user is left with nothing, plus advice to reinstall.
 *
 * OpenTV never deletes before it has the replacement:
 *
 * - Programmes are **upserted in batches** as they stream out of the parser. The unique index
 *   on `(feedId, epgChannelId, startUtcMillis)` makes a re-run idempotent, so a sync that
 *   dies at 60% leaves 60% of a fresher guide behind — strictly better than before.
 * - Old programmes are pruned **by age**, only after at least one feed succeeds.
 * - A failed feed leaves that feed's previous data intact and records the reason on the
 *   feed row, where the settings screen shows it — no silent failure.
 */
class EpgRepository(
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
) {

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return
        feedDao.insert(
            EpgFeed(name = name.trim().ifBlank { trimmed }, url = trimmed, enabled = true),
        )
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = feedDao.setEnabled(id, enabled)

    suspend fun removeFeed(feed: EpgFeed) {
        // A removed feed takes its guide data with it — orphaned programmes would otherwise
        // keep matching channels forever with content that never refreshes.
        programmeDao.deleteForFeed(feed.id)
        aliasDao.deleteForFeed(feed.id)
        feedDao.delete(feed.id)
    }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call.
     */
    suspend fun ensureFeeds() {
        for (source in sourceDao.enabled()) {
            if (feedDao.forProvider(source.id) == null) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }
        for ((name, url) in BUILT_IN_FEEDS) {
            if (feedDao.byUrl(url) == null) {
                // Off by default: shipping a switched-on 20 MB download for a country the
                // user may not live in would be rude. The EPG settings screen makes
                // enabling one a single click.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /** Downloads every enabled feed, merges, prunes, and re-runs the matcher. */
    suspend fun syncAll(nowUtcMillis: Long, force: Boolean = false): SyncSummary =
        withContext(Dispatchers.IO) {
            ensureFeeds()
            maybeAutoEnableRegionalFeed()

            var succeeded = 0
            var failed = 0
            var written = 0

            for (feed in feedDao.enabled()) {
                if (!force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS) {
                    succeeded++
                    continue
                }
                when (val result = syncFeed(feed, nowUtcMillis)) {
                    is FeedResult.Success -> {
                        succeeded++
                        written += result.programmes
                        feedDao.markSynced(
                            feed.id,
                            nowUtcMillis,
                            "${result.programmes} programmes, ${result.channels} channels",
                        )
                    }
                    is FeedResult.Failed -> {
                        failed++
                        // The stamp is NOT advanced on failure, so the next sync retries
                        // rather than waiting out the interval on a feed that never landed.
                        feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason)
                        Log.w(TAG, "Feed '${feed.name}' failed: ${result.reason}")
                    }
                }
            }

            if (succeeded > 0) {
                programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
            }

            val (matched, total) = runMatcher()
            SyncSummary(succeeded, failed, written, matched, total)
        }

    private sealed interface FeedResult {
        data class Success(val programmes: Int, val channels: Int) : FeedResult
        data class Failed(val reason: String) : FeedResult
    }

    private suspend fun syncFeed(feed: EpgFeed, nowUtcMillis: Long): FeedResult {
        val batch = ArrayList<Programme>(BATCH_SIZE)
        val aliases = ArrayList<EpgChannelAlias>(256)
        var written = 0

        try {
            openFeedStream(feed).use { stream ->
                val stats = XmltvParser.parse(
                    input = stream,
                    feedId = feed.id,
                    onChannelAlias = { id, displayName ->
                        aliases += EpgChannelAlias(
                            feedId = feed.id,
                            epgId = id,
                            displayName = displayName ?: id,
                            normalizedKey = ChannelNameNormalizer.normalize(displayName ?: id).groupKey,
                        )
                    },
                    onProgramme = { programme ->
                        // Skip anything that finished before the retention cut-off; no point
                        // writing rows we are about to prune.
                        if (programme.endUtcMillis >= nowUtcMillis - RETENTION_PAST_MILLIS) {
                            batch += programme
                            if (batch.size >= BATCH_SIZE) {
                                programmeDao.upsertAll(batch)
                                written += batch.size
                                batch.clear()
                            }
                        }
                    },
                )
                if (batch.isNotEmpty()) {
                    programmeDao.upsertAll(batch)
                    written += batch.size
                }
                if (aliases.isNotEmpty()) {
                    aliases.chunked(BATCH_SIZE).forEach { aliasDao.upsertAll(it) }
                }

                if (written == 0 && stats.programmeCount == 0) {
                    return FeedResult.Failed("Downloaded, but contained no programmes.")
                }
                return FeedResult.Success(written, stats.channelCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately no cleanup. Whatever was written is newer than what was there,
            // and what was there is still there.
            return FeedResult.Failed(e.message ?: "Download failed.")
        }
    }

    /**
     * Opens a feed as a decompressed XML stream.
     *
     * Several of the free guide sources publish `.gz` files (a national guide compresses
     * roughly 10:1), and providers occasionally gzip xmltv.php without saying so. Sniffing
     * the first two bytes for the gzip magic number handles both, regardless of what the
     * URL or the headers claim.
     */
    private suspend fun openFeedStream(feed: EpgFeed): InputStream {
        val raw: InputStream = when {
            feed.providerSourceId != null -> {
                val source: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                api.openEpgStream(source)
            }
            feed.url != null -> {
                val response = http.newCall(Request.Builder().url(feed.url).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("Guide download failed (HTTP ${response.code}).")
                }
                response.body?.byteStream()
                    ?: throw IllegalStateException("The server returned an empty guide.")
            }
            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }

        val buffered = BufferedInputStream(raw, 8 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * Joins every channel to the merged guide by normalised name.
     * Returns (channels with a working guide id, total channels).
     */
    suspend fun runMatcher(): Pair<Int, Int> {
        val startNanos = System.nanoTime()
        val aliases = aliasDao.all()
        val index = EpgMatcher.buildIndex(aliases.map { it.epgId to it.displayName })
        // Only ids that actually have programmes count as "working" — a match against a
        // channel the guide lists but never fills is a guide that looks broken.
        val populated = programmeDao.channelIdsWithProgrammes().toHashSet()

        var total = 0
        var matched = 0
        val changes = ArrayList<Pair<Long, String?>>()

        var afterId = 0L
        while (true) {
            val page = channelDao.matchKeysAfter(afterId, CHANNEL_PAGE_SIZE)
            if (page.isEmpty()) break
            for (channel in page) {
                val newMatch = index.match(channel.groupKey)
                if (newMatch != channel.matchedEpgId) {
                    changes += channel.id to newMatch
                }
                val works = channel.epgCandidates.any { it in populated } ||
                    (newMatch != null && newMatch in populated)
                if (works) matched++
            }
            total += page.size
            afterId = page.last().id
        }

        // Batched, not one write per channel: every commit invalidates `channels`, and each
        // invalidation re-runs the guide's whole-table queries — 300,000 rows, seconds apiece.
        changes.chunked(BATCH_SIZE).forEach { channelDao.setMatchedEpgIds(it) }

        Log.i(
            TAG,
            "Matcher: $matched of $total channels have a working guide " +
                "(${aliases.size} guide aliases, ${populated.size} populated guide channels, " +
                "${changes.size} updated, ${(System.nanoTime() - startNanos) / 1_000_000} ms)",
        )
        return matched to total
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    /**
     * Turns on the free guide for the user's region, once, on a pristine install.
     *
     * The reasoning: a provider guide is usually empty, and "go and find Guide settings"
     * is a hurdle most people meet as a blank guide and give up on. The app already knows
     * the region — the normaliser reads it off the `UK|` prefixes the provider itself
     * ships — so when a clear majority of channels agree, enable that region's built-in.
     *
     * Guard rails: only when every built-in is untouched (never enabled, never synced)
     * and no custom feed exists. Switch it off and it stays off — a choice the user has
     * made is never overridden.
     */
    private suspend fun maybeAutoEnableRegionalFeed() {
        val all = feedDao.all()
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return
        val pristine = builtIns.all { !it.enabled && it.lastSyncMillis == 0L } &&
            all.none { !it.builtIn && it.providerSourceId == null }
        if (!pristine) return

        // Votes on an evenly spread sample of ids rather than every channel. This runs on every
        // launch while the built-ins stay untouched, and a full normalise per name is ~25 s for a
        // 330,000-entry M3U on a car head unit; the majority a sample finds is the same. Lists
        // with fewer than REGION_VOTE_SAMPLE ids are still counted in full (step 1).
        val step = (channelDao.maxId() ?: return) / REGION_VOTE_SAMPLE + 1
        val regionCounts = HashMap<String, Int>()
        var sampled = 0
        var afterId = 0L
        while (true) {
            val page = channelDao.sampledNamesAfter(afterId, step, CHANNEL_PAGE_SIZE)
            if (page.isEmpty()) break
            for (channel in page) {
                sampled++
                val region = ChannelNameNormalizer.normalize(channel.name).region ?: continue
                regionCounts.merge(region, 1, Int::plus)
            }
            afterId = page.last().id
        }
        val top = regionCounts.maxByOrNull { it.value } ?: return
        // The floor is counted in whole-list channels, so scale the sample back up.
        if (top.value * step < MIN_CHANNELS_FOR_AUTO_REGION) return

        val feedName = REGION_TO_FEED[top.key] ?: return
        val feed = builtIns.firstOrNull { it.name == feedName } ?: return
        feedDao.setEnabled(feed.id, true)
        Log.i(
            TAG,
            "Auto-enabled '${feed.name}' — ${top.value} of $sampled sampled channels " +
                "tagged ${top.key}",
        )
    }

    companion object {
        private const val TAG = "EpgRepository"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        const val BATCH_SIZE = 500

        /** Channels per paged read: a page of the narrow projections fits one cursor window. */
        const val CHANNEL_PAGE_SIZE = 5_000

        /** Roughly how many channel names the region vote reads, however long the list. */
        const val REGION_VOTE_SAMPLE = 5_000

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /**
         * The curated built-in list. Criteria for being here: free, no key or signup,
         * openly licensed or explicitly public, and reachable as plain XMLTV(.gz).
         * All ship disabled; the user turns on the one for where they live.
         *
         * The UK Freeview entry is GPL-3.0 (same licence as OpenTV), regenerated every
         * 12 h, ~270 channels with logos — verified working before it earned this slot.
         */
        /** A region needs at least this many prefixed channels before we act on it. */
        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        /** Region tag (as providers write it) → built-in feed name to auto-enable. */
        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to "USA — epgshare01",
            "USA" to "USA — epgshare01",
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — Freeview (free-to-air)" to
                "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
            "UK — epgshare01 (Sky lineup)" to
                "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            "USA — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
            "Canada — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_CA1.xml.gz",
            "Australia — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
        )
    }
}
