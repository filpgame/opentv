/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The region vote and the matcher read the channel table in id-ordered pages, and the vote
 * only samples a long list. These run against a real (in-memory) Room database, because the
 * paging and sampling live in the SQL.
 */
// Same Robolectric setup as XmltvChannelParseTest: no SDK 35 image, and a plain Application
// so OpenTvApp does not schedule WorkManager.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgRegionVoteTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repo: EpgRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()
        val http = OkHttpClient()
        repo = EpgRepository(
            programmeDao = db.programmes(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(http),
            http = http,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addChannels(names: List<String>) {
        val sourceId = db.sources().insert(
            Source(name = "test", kind = SourceKind.M3U, url = "http://example.invalid"),
        )
        db.channels().upsertAll(
            names.mapIndexed { i, name ->
                Channel(
                    sourceId = sourceId,
                    streamId = "s$i",
                    name = name,
                    categoryId = null,
                    logoUrl = null,
                    epgChannelId = null,
                    number = null,
                    streamUrl = "http://example.invalid/$i",
                )
            },
        )
    }

    /** `now = 0` keeps every feed inside its refresh window, so the sync downloads nothing. */
    private suspend fun syncWithoutDownloads() = repo.syncAll(nowUtcMillis = 0L)

    private suspend fun enabledBuiltIns() =
        db.epgFeeds().enabled().filter { it.builtIn }.map { it.name }

    @Test
    fun `a long list votes on a sample and still finds its majority`() = runTest {
        addChannels(List(20_000) { i -> if (i % 10 == 0) "US| Channel $i" else "UK| Channel $i" })

        val summary = syncWithoutDownloads()

        assertThat(enabledBuiltIns()).containsExactly(EpgRepository.REGION_TO_FEED["UK"])
        // The matcher's paged read still sees every channel, across several pages.
        assertThat(summary.channelsTotal).isEqualTo(20_000)
    }

    @Test
    fun `the sample is evenly spread and bounded`() = runTest {
        addChannels(List(20_000) { "UK| Channel $it" })

        val step = db.channels().maxId()!! / EpgRepository.REGION_VOTE_SAMPLE + 1
        val sample = db.channels().sampledNamesAfter(0L, step, EpgRepository.CHANNEL_PAGE_SIZE)

        assertThat(step).isEqualTo(5L)
        assertThat(sample).hasSize(4_000)
        assertThat(sample.all { it.id % step == 0L }).isTrue()
    }

    @Test
    fun `a short list is counted in full against the noise floor`() = runTest {
        addChannels(List(5) { "UK| Channel $it" } + List(100) { "Channel $it" })

        syncWithoutDownloads()

        assertThat(enabledBuiltIns()).containsExactly(EpgRepository.REGION_TO_FEED["UK"])
    }

    @Test
    fun `a short list below the noise floor enables nothing`() = runTest {
        addChannels(List(4) { "UK| Channel $it" } + List(100) { "Channel $it" })

        syncWithoutDownloads()

        assertThat(enabledBuiltIns()).isEmpty()
    }
}
