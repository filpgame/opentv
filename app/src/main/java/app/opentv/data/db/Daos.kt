/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import kotlinx.coroutines.flow.Flow

/** Rows per upsert statement. Keeps a 40,000-channel playlist from building one huge query. */
private const val UPSERT_CHUNK = 500

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<Source>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): Source?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    @Update
    suspend fun update(source: Source)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastCatalogSyncMillis = :millis WHERE id = :id")
    suspend fun markCatalogSynced(id: Long, millis: Long)
}

@Dao
interface ChannelDao {
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortIndex, displayName
        """
    )
    fun observe(sourceId: Long?, categoryId: String?): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 ORDER BY sortIndex, displayName")
    fun observeFavourites(): Flow<List<Channel>>

    /**
     * Channels across a SET of provider categories. Needed because one logical category
     * ("General") is often shipped as several codec-split ones ("UK| GENERAL HD/RAW",
     * "UK| GENERAL hevc") — the UI merges them into a single rail entry. The list is a
     * handful of ids, nowhere near SQLite's bound-variable cap.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategories(categoryIds: List<String>): Flow<List<Channel>>

    /**
     * Channels across a set of categories INCLUDING hidden ones, optionally scoped to one source
     * — the channel manager's browse query.
     *
     * The guide's [observe]/[observeInCategories] filter `hidden = 0`; the manager can't, because
     * its whole job is finding a hidden channel and switching it back on. Still scoped to a
     * category set (never the whole table) so the list stays a size a remote can actually scroll.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategoriesIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>>

    /**
     * One-shot (non-Flow) read of ONE category's channels INCLUDING hidden, optionally scoped to a
     * single source — the web channel manager's per-category fetch. That server handles one HTTP
     * request at a time on a socket thread and wants a plain list, not a subscription, so this is
     * the suspend `List` sibling of [observeInCategoriesIncludingHidden], narrowed to one category.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId = :categoryId
        ORDER BY sortIndex, displayName
        """
    )
    suspend fun channelsInCategoryIncludingHidden(sourceId: Long?, categoryId: String): List<Channel>

    /**
     * Channel count per (source, category), hidden rows included — the web manager's category list
     * shows how many channels each holds. Grouped by (sourceId, categoryId) because a category id
     * is only unique within a source.
     */
    @Query(
        """
        SELECT sourceId, categoryId, COUNT(*) AS count
        FROM channels
        WHERE categoryId IS NOT NULL
        GROUP BY sourceId, categoryId
        """
    )
    suspend fun channelCountsByCategory(): List<CategoryChannelCount>

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int = 200): Flow<List<Channel>>

    /** Like [search] but keeps hidden channels in — the channel manager needs them to unhide. */
    @Query(
        """
        SELECT * FROM channels
        WHERE (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY hidden, favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun searchIncludingHidden(query: String, limit: Int = 200): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): Channel?

    /** Every quality variant of one logical channel, best first. */
    @Query(
        """
        SELECT * FROM channels
        WHERE groupKey = :groupKey AND groupKey != '' AND hidden = 0
        ORDER BY qualityRank DESC, displayName
        """
    )
    suspend fun variantsInGroup(groupKey: String): List<Channel>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    /**
     * Reactive count of channels the guide can actually show (hidden rows excluded). Lets the home
     * screen tell "the guide is still building from channels on disk" apart from "nothing loaded",
     * so a failed or empty sync surfaces an error instead of spinning forever.
     */
    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0")
    fun observeVisibleCount(): Flow<Int>

    @Query("UPDATE channels SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE channels SET customName = :name WHERE id = :id")
    suspend fun setCustomName(id: Long, name: String?)

    @Query("UPDATE channels SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Int)

    /** Every channel for a source. The channels table is live-only, so these are its live channels. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Channel>

    /** Rewrites one channel's resolved playback URL — used when a source's live stream format changes. */
    @Query("UPDATE channels SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: Long, url: String)

    // --- Sync: identify favourites/hidden by stream URL (stable across devices) ---
    @Query("SELECT streamUrl FROM channels WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    @Query("SELECT streamUrl FROM channels WHERE hidden = 1")
    suspend fun hiddenUrls(): List<String>

    @Query("UPDATE channels SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    @Query("UPDATE channels SET hidden = 1 WHERE streamUrl = :url")
    suspend fun markHiddenByUrl(url: String)

    @Query("UPDATE channels SET matchedEpgId = :epgId WHERE id = :id")
    suspend fun setMatchedEpgId(id: Long, epgId: String?)

    /**
     * Applies a batch of matcher results (channel id to guide id) in one transaction, so
     * observers of `channels` re-query once per batch rather than once per channel.
     */
    @Transaction
    suspend fun setMatchedEpgIds(changes: List<Pair<Long, String?>>) {
        changes.forEach { (id, epgId) -> setMatchedEpgId(id, epgId) }
    }

    @Query("UPDATE channels SET epgOverrideId = :epgId WHERE id = :id")
    suspend fun setEpgOverride(id: Long, epgId: String?)

    /** The matcher's working set: id, group key, and what is already known. */
    @Query("SELECT * FROM channels")
    suspend fun allForMatching(): List<Channel>

    /**
     * One page of the matcher's working set, in id order, with only the columns it reads.
     *
     * Paged by id rather than read in one query: Android fills a cursor in 2 MB windows and
     * re-steps the statement from the first row for every window, so reading a 300,000-row
     * table in one go costs time quadratic in its size (~20 s on a car head unit).
     */
    @Query(
        """
        SELECT id, groupKey, epgChannelId, matchedEpgId, epgOverrideId FROM channels
        WHERE id > :afterId ORDER BY id LIMIT :limit
        """
    )
    suspend fun matchKeysAfter(afterId: Long, limit: Int): List<ChannelMatchKeys>

    /**
     * One page of provider names for the region vote, in id order, keeping only ids that are a
     * multiple of [step] — an evenly spread sample; a step of 1 is every channel. Paged like
     * [matchKeysAfter].
     */
    @Query(
        """
        SELECT id, name FROM channels
        WHERE id > :afterId AND id % :step = 0 ORDER BY id LIMIT :limit
        """
    )
    suspend fun sampledNamesAfter(afterId: Long, step: Long, limit: Int): List<ChannelName>

    @Query("SELECT MAX(id) FROM channels")
    suspend fun maxId(): Long?

    @Upsert
    suspend fun upsertAll(channels: List<Channel>)

    /**
     * Deletes channels the provider no longer lists.
     *
     * Compares a sync stamp rather than using `streamId NOT IN (:allCurrentIds)`. The latter
     * is the obvious implementation and it breaks on exactly the providers where it matters:
     * SQLite caps bound variables (999 on older Android, 32766 on newer) and a large playlist
     * has 40,000 channels, so the query throws and the catalogue never converges.
     *
     * Note also the deliberate absence of any "delete everything then insert" method. Wiping
     * first means a sync that fails halfway leaves the user with an empty app — which is how
     * a player ends up telling people to reinstall to get their channels back.
     */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND lastSeenMillis < :syncStamp")
    suspend fun deleteStale(sourceId: Long, syncStamp: Long)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /**
     * Preserves user state (favourites, hidden, manual order, custom names, EPG overrides) across a
     * catalogue refresh. Upsert would otherwise overwrite them with the defaults from the
     * freshly parsed rows.
     */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Channel>, syncStamp: Long) {
        if (incoming.isEmpty()) return

        val existing = userStateForSource(sourceId).associateBy { it.streamId }
        val merged = incoming.map { channel ->
            val previous = existing[channel.streamId]
            if (previous == null) {
                channel.copy(lastSeenMillis = syncStamp)
            } else {
                channel.copy(
                    id = previous.id,
                    favourite = previous.favourite,
                    // OR, not overwrite: the importer hides separator rows ('### UK ###')
                    // at parse time, and that decision must stick even for rows that
                    // predate the rule. User hides are preserved the same way.
                    hidden = previous.hidden || channel.hidden,
                    sortIndex = previous.sortIndex,
                    // A user's rename must outlive the provider's fresh name on every refresh.
                    customName = previous.customName,
                    epgOverrideId = previous.epgOverrideId,
                    matchedEpgId = previous.matchedEpgId,
                    lastSeenMillis = syncStamp,
                )
            }
        }
        // Chunked so a 40,000-channel playlist does not build one enormous statement.
        merged.chunked(UPSERT_CHUNK).forEach { upsertAll(it) }
        deleteStale(sourceId, syncStamp)
    }

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun userStateForSource(sourceId: Long): List<Channel>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    fun observe(kind: StreamKind): Flow<List<Category>>

    /** One-shot list of every category of a kind, ordered like [observe] — the web manager reads
     * the live categories once per request rather than subscribing to a Flow. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    suspend fun allByKind(kind: StreamKind): List<Category>

    /** Minimal id+name rows for the re-normalise pass. */
    @Query("SELECT id, name FROM categories WHERE id IN (:ids)")
    suspend fun namesFor(ids: Set<String>): List<CategoryName>

    @Upsert
    suspend fun upsertAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun deleteForSourceAndKind(sourceId: Long, kind: StreamKind)

    /** Makes a source's categories of one kind exactly [categories], dropping ones it no longer has. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, kind: StreamKind, categories: List<Category>) {
        deleteForSourceAndKind(sourceId, kind)
        if (categories.isNotEmpty()) upsertAll(categories)
    }
}

/** Projection for [CategoryDao.namesFor]. */
data class CategoryName(val id: String, val name: String)

/** Projection for [ChannelDao.matchKeysAfter]. */
data class ChannelMatchKeys(
    val id: Long,
    val groupKey: String,
    val epgChannelId: String?,
    val matchedEpgId: String?,
    val epgOverrideId: String?,
) {
    /** Same ids, same order as [Channel.epgCandidates]. */
    val epgCandidates: List<String>
        get() = listOfNotNull(epgOverrideId, epgChannelId, matchedEpgId)
}

/** Projection for [ChannelDao.sampledNamesAfter]. */
data class ChannelName(val id: Long, val name: String)

/** Projection for [ChannelDao.channelCountsByCategory]. */
data class CategoryChannelCount(val sourceId: Long, val categoryId: String, val count: Int)

/** A stored movie, series or episode's row id and user state, by its provider key. */
data class VodRowKey(val id: Long, val key: String, val favourite: Boolean)

/**
 * The shared half of the `replaceForSource` methods on the VOD DAOs: carries each existing row's id
 * over to its incoming copy, so resume positions (keyed by row id) and favourites survive a
 * refresh. Returns the rows to write and the ids of rows the source no longer lists.
 */
private fun <T> mergeByKey(
    existing: List<VodRowKey>,
    incoming: List<T>,
    keyOf: (T) -> String,
    carry: (T, VodRowKey) -> T,
): Pair<List<T>, List<Long>> {
    val byKey = existing.associateBy { it.key }
    val merged = incoming.map { row -> byKey[keyOf(row)]?.let { carry(row, it) } ?: row }
    val kept = incoming.mapTo(HashSet(incoming.size * 2)) { keyOf(it) }
    return merged to existing.filter { it.key !in kept }.map { it.id }
}

@Dao
interface EpgFeedDao {
    @Query("SELECT * FROM epg_feeds ORDER BY builtIn DESC, id")
    fun observeAll(): Flow<List<EpgFeed>>

    @Query("SELECT * FROM epg_feeds")
    suspend fun all(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE enabled = 1")
    suspend fun enabled(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE providerSourceId = :sourceId")
    suspend fun forProvider(sourceId: Long): EpgFeed?

    @Query("SELECT * FROM epg_feeds WHERE url = :url")
    suspend fun byUrl(url: String): EpgFeed?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: EpgFeed): Long

    @Update
    suspend fun update(feed: EpgFeed)

    @Query("UPDATE epg_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE epg_feeds SET lastSyncMillis = :millis, lastResult = :result WHERE id = :id")
    suspend fun markSynced(id: Long, millis: Long, result: String)

    @Query("DELETE FROM epg_feeds WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EpgChannelAliasDao {
    @Query("SELECT * FROM epg_channels")
    suspend fun all(): List<EpgChannelAlias>

    @Query("SELECT COUNT(*) FROM epg_channels")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(aliases: List<EpgChannelAlias>)

    @Query("DELETE FROM epg_channels WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface ProgrammeDao {
    /**
     * The guide grid: everything overlapping the visible window.
     *
     * Filtered by time only, never by a list of channel ids. `epgChannelId IN (:ids)` is the
     * natural way to write this and it breaks on any provider large enough to matter — SQLite
     * caps bound variables at 999 on older Android, and a real catalogue has thousands of
     * channels. Callers group the result by channel in memory instead.
     */
    @Query(
        """
        SELECT * FROM programmes
        WHERE endUtcMillis > :fromUtcMillis
          AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
        """
    )
    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>>

    /** What is on right now, for the channel list's "now playing" line. */
    @Query(
        """
        SELECT * FROM programmes
        WHERE startUtcMillis <= :nowUtcMillis AND endUtcMillis > :nowUtcMillis
        """
    )
    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>>

    @Query("SELECT COUNT(*) FROM programmes WHERE feedId = :feedId")
    suspend fun countForFeed(feedId: Long): Int

    /** Distinct guide channels that actually have programmes — the match report's baseline. */
    @Query("SELECT DISTINCT epgChannelId FROM programmes")
    suspend fun channelIdsWithProgrammes(): List<String>

    @Upsert
    suspend fun upsertAll(programmes: List<Programme>)

    /** Housekeeping: drop anything that finished before the retention cut-off. */
    @Query("DELETE FROM programmes WHERE endUtcMillis < :beforeUtcMillis")
    suspend fun deleteEndedBefore(beforeUtcMillis: Long)

    @Query("DELETE FROM programmes WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface MovieDao {
    @Query(
        """
        SELECT * FROM movies
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE favourite = 1 ORDER BY name")
    fun observeFavourites(): Flow<List<Movie>>

    @Query("SELECT * FROM movies WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Movie>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Movie>>

    /**
     * Every movie, newest first — the working set for the Kotlin-side feeds (by-genre grouping,
     * genre-affinity recommendations, more-like-this). One pass over a few thousand rows is cheap;
     * per-genre `LIKE` queries would multiply round-trips and risk substring false positives.
     */
    @Query("SELECT * FROM movies ORDER BY addedMillis DESC")
    suspend fun all(): List<Movie>

    /** How many movies are on disk. A cheap COUNT the home feeds use to tell "the library grew"
     *  from "same as last time" without loading every row (see VodViewModel.loadHomeFeeds). */
    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int

    /**
     * Fallback for More-Like-This when a movie has no genre to match on: other titles from the same
     * source (and category, when it has one), best-rated first. Never returns the movie itself.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Movie>

    /**
     * Movies a person is billed in — the Plex-style "click an actor, see everything with them".
     * A substring `LIKE` over the comma-separated `cast` string: cheap and correct enough for a few
     * thousand VOD rows, where a full credits table would be over-engineering. Blank/absent cast is
     * excluded so an empty name never matches every row. Best-rated, then newest, first.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun moviesWithActor(name: String, limit: Int): List<Movie>

    /** Movies a person directed. See [moviesWithActor]; same shape over the `director` field. */
    @Query(
        """
        SELECT * FROM movies
        WHERE director IS NOT NULL AND director != '' AND director LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun moviesByDirector(name: String, limit: Int): List<Movie>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun byId(id: Long): Movie?

    /** Stream URL is the cross-device key: the same film has the same URL on the same provider. */
    @Query("SELECT * FROM movies WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Movie?

    @Query("UPDATE movies SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    // --- Sync: favourites by stream URL ---
    @Query("SELECT streamUrl FROM movies WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    @Query("UPDATE movies SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    @Upsert
    suspend fun upsertAll(movies: List<Movie>)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("SELECT id, streamId AS `key`, favourite FROM movies WHERE sourceId = :sourceId")
    suspend fun keysForSource(sourceId: Long): List<VodRowKey>

    /** Plain REPLACE, not [Upsert]: every row arrives with its id already set, and an upsert pays
     *  for a caught constraint exception per row that already exists. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(movies: List<Movie>)

    @Query("DELETE FROM movies WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Makes a playlist source's movies exactly [incoming]. See [mergeByKey]. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, incoming: List<Movie>) {
        val (rows, stale) = mergeByKey(keysForSource(sourceId), incoming, Movie::streamId) { movie, old ->
            movie.copy(id = old.id, favourite = old.favourite)
        }
        rows.chunked(UPSERT_CHUNK).forEach { insertOrReplace(it) }
        stale.chunked(UPSERT_CHUNK).forEach { deleteByIds(it) }
    }
}

@Dao
interface SeriesDao {
    @Query(
        """
        SELECT * FROM series
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedMillis DESC, name
        """
    )
    fun observe(categoryId: String?): Flow<List<Series>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Series>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Series>>

    /** Every series, newest first — working set for the Kotlin-side by-genre / more-like-this feeds. */
    @Query("SELECT * FROM series ORDER BY addedMillis DESC")
    suspend fun all(): List<Series>

    /** How many series are on disk — the cheap "did the library grow" check for the home feeds. */
    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int

    /** Fallback for More-Like-This when a series has no genre: same source/category, best-rated first. */
    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId AND id != :excludeId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun similarByCategory(sourceId: Long, categoryId: String?, excludeId: Long, limit: Int): List<Series>

    /** Series a person is billed in — the show half of "click an actor, see everything". See
     *  [MovieDao.moviesWithActor]. */
    @Query(
        """
        SELECT * FROM series
        WHERE `cast` IS NOT NULL AND `cast` != '' AND `cast` LIKE '%' || :name || '%'
        ORDER BY rating DESC, addedMillis DESC
        LIMIT :limit
        """
    )
    suspend fun seriesWithActor(name: String, limit: Int): List<Series>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): Series?

    @Query("UPDATE series SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    // --- Sync: favourites by provider series id (series carry no stream URL) ---
    @Query("SELECT seriesId FROM series WHERE favourite = 1")
    suspend fun favouriteSeriesIds(): List<String>

    @Query("UPDATE series SET favourite = 1 WHERE seriesId = :seriesId")
    suspend fun markFavouriteBySeriesId(seriesId: String)

    @Upsert
    suspend fun upsertAll(items: List<Series>)

    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("SELECT id, seriesId AS `key`, favourite FROM series WHERE sourceId = :sourceId")
    suspend fun keysForSource(sourceId: Long): List<VodRowKey>

    /** See [MovieDao.insertOrReplace]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(items: List<Series>)

    @Query("DELETE FROM series WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Makes a playlist source's series exactly [incoming]. See [mergeByKey]. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, incoming: List<Series>) {
        val (rows, stale) = mergeByKey(keysForSource(sourceId), incoming, Series::seriesId) { series, old ->
            series.copy(id = old.id, favourite = old.favourite)
        }
        rows.chunked(UPSERT_CHUNK).forEach { insertOrReplace(it) }
        stale.chunked(UPSERT_CHUNK).forEach { deleteByIds(it) }
    }
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeForSeries(sourceId: Long, seriesId: String): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: Long): Episode?

    @Query("SELECT * FROM episodes WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Episode?

    @Upsert
    suspend fun upsertAll(episodes: List<Episode>)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("SELECT id, episodeId AS `key`, 0 AS favourite FROM episodes WHERE sourceId = :sourceId")
    suspend fun keysForSource(sourceId: Long): List<VodRowKey>

    /** See [MovieDao.insertOrReplace]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(episodes: List<Episode>)

    @Query("DELETE FROM episodes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Makes a playlist source's episodes exactly [incoming]. See [mergeByKey]. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, incoming: List<Episode>) {
        val (rows, stale) = mergeByKey(keysForSource(sourceId), incoming, Episode::episodeId) { episode, old ->
            episode.copy(id = old.id)
        }
        rows.chunked(UPSERT_CHUNK).forEach { insertOrReplace(it) }
        stale.chunked(UPSERT_CHUNK).forEach { deleteByIds(it) }
    }
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun get(profileId: Long, key: String): PlaybackPosition?

    @Query(
        "SELECT * FROM playback_positions WHERE profileId = :profileId " +
            "ORDER BY updatedAtMillis DESC LIMIT :limit",
    )
    fun observeRecent(profileId: Long, limit: Int = 30): Flow<List<PlaybackPosition>>

    @Query("SELECT * FROM playback_positions")
    suspend fun all(): List<PlaybackPosition>

    /** One profile's whole watch history — the input to genre-affinity recommendations. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId")
    suspend fun forProfile(profileId: Long): List<PlaybackPosition>

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun delete(profileId: Long, key: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun all(): List<Profile>

    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Profile?

    @Insert
    suspend fun insert(profile: Profile): Long

    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    /** Removing a profile takes its watch history with it. */
    @Query("DELETE FROM playback_positions WHERE profileId = :id")
    suspend fun deletePositions(id: Long)
}
