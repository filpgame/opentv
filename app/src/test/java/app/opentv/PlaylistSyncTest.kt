/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A playlist sync end to end: which URL is fetched, and where each kind of entry is stored. The
 * provider is an OkHttp interceptor serving canned playlists by the `type` query parameter.
 */
// Same Robolectric setup as EpgRegionVoteTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaylistSyncTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repo: CatalogRepository
    private lateinit var source: Source

    /** Playlist body per requested `type`; a missing type answers 404. */
    private val served = mutableMapOf<String, String>()
    private val requestedTypes = mutableListOf<String?>()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
            .allowMainThreadQueries().build()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val type = chain.request().url.queryParameter("type")
            requestedTypes += type
            val body = served[type]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200)
                .message("")
                .body((body ?: "").toResponseBody())
                .build()
        }.build()
        repo = CatalogRepository(
            sourceDao = db.sources(),
            channelDao = db.channels(),
            categoryDao = db.categories(),
            movieDao = db.movies(),
            seriesDao = db.series(),
            episodeDao = db.episodes(),
            positionDao = db.positions(),
            api = XtreamApi(http),
            stalkerApi = StalkerApi(http),
            http = http,
            settings = AppSettings.get(context),
        )
        val id = db.sources().insert(
            Source(
                name = "panel",
                kind = SourceKind.M3U,
                url = "http://panel.invalid:8080/get.php?username=u&password=p&type=m3u&output=ts",
            ),
        )
        source = db.sources().byId(id)!!
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sync(now: Long = 1_000L) = repo.syncLive(source, now)

    private suspend fun categories(kind: StreamKind) =
        db.categories().allByKind(kind).map { it.name }

    @Test
    fun `a plain export is split into channels, movies and series`() = runTest {
        served["m3u"] = PLAIN

        val result = sync()

        // m3u_plus first; this panel refuses it, so the URL as given.
        assertThat(requestedTypes).containsExactly("m3u_plus", "m3u").inOrder()
        assertThat(result).isEqualTo(CatalogRepository.SyncResult.Success(2, 2, 2))
        assertThat(db.channels().forSource(source.id).map { it.name })
            .containsExactly("GLOBO SP [FHD]", "GLOBO SP [HD]")
        assertThat(db.movies().all(includeAdult = true).map { it.name }).containsExactly("Ação Mutante", "007: Cassino Royale")
        assertThat(db.series().all(includeAdult = true).map { it.name }).containsExactly("Elsbeth [L]", "Elsbeth")
        assertThat(db.episodes().keysForSource(source.id)).hasSize(3)
        assertThat(categories(StreamKind.MOVIE)).containsExactly("#", "A").inOrder()
        assertThat(categories(StreamKind.SERIES)).containsExactly("E")
        assertThat(categories(StreamKind.LIVE)).isEmpty()
    }

    @Test
    fun `the m3u_plus variant is used when the panel serves it`() = runTest {
        served["m3u"] = PLAIN
        served["m3u_plus"] = PLUS

        sync()

        assertThat(requestedTypes).containsExactly("m3u_plus")
        assertThat(categories(StreamKind.LIVE)).containsExactly("Abertos")
        assertThat(categories(StreamKind.MOVIE)).containsExactly("Filmes | Lançamentos", "Filmes | Ação").inOrder()
        assertThat(categories(StreamKind.SERIES)).containsExactly("Séries | Drama")
        // Both quality variants survive their shared tvg-id.
        assertThat(db.channels().forSource(source.id)).hasSize(2)
        assertThat(db.movies().all(includeAdult = true).map { it.posterUrl }).containsExactly("http://img.invalid/acao.jpg", null)
    }

    @Test
    fun `a refresh keeps row ids and favourites and drops what the playlist no longer lists`() = runTest {
        served["m3u"] = PLAIN
        sync()
        val movie = db.movies().all(includeAdult = true).single { it.name == "Ação Mutante" }
        db.movies().setFavourite(movie.id, true)
        val episodeIds = db.episodes().keysForSource(source.id).associate { it.key to it.id }

        // The provider drops one film and adds another; the panel now also serves m3u_plus.
        served["m3u_plus"] = PLUS
            .replace("#EXTINF:-1 tvg-id=\"\" group-title=\"Filmes | Ação\",007: Cassino Royale\n", "")
            .replace("http://panel.invalid:8080/movie/u/p/202.mkv\n", "") +
            "\n#EXTINF:-1 group-title=\"Filmes | Ação\",Novo Filme\nhttp://panel.invalid:8080/movie/u/p/203.mp4"
        sync(now = 2_000L)

        val movies = db.movies().all(includeAdult = true)
        assertThat(movies.map { it.name }).containsExactly("Ação Mutante", "Novo Filme")
        val kept = movies.single { it.name == "Ação Mutante" }
        assertThat(kept.id).isEqualTo(movie.id)
        assertThat(kept.favourite).isTrue()
        assertThat(db.episodes().keysForSource(source.id).associate { it.key to it.id }).isEqualTo(episodeIds)
        // The A–Z buckets give way to the playlist's own categories.
        assertThat(categories(StreamKind.MOVIE)).containsExactly("Filmes | Lançamentos", "Filmes | Ação").inOrder()
    }

    @Test
    fun `old channel rows for VOD entries are cleared by the next sync`() = runTest {
        // What an older build stored: every entry, films included, as a channel.
        served["m3u"] = PLAIN
        sync()
        db.channels().upsertAll(
            listOf(
                db.channels().forSource(source.id).first().copy(
                    id = 0,
                    streamId = "url:legacy",
                    name = "Ação Mutante",
                    streamUrl = "http://panel.invalid:8080/movie/u/p/201.mp4",
                    lastSeenMillis = 1_000L,
                ),
            ),
        )

        sync(now = 2_000L)

        assertThat(db.channels().forSource(source.id).map { it.name })
            .containsExactly("GLOBO SP [FHD]", "GLOBO SP [HD]")
    }

    @Test
    fun `only Xtream type=m3u exports are upgraded`() {
        assertThat(CatalogRepository.richPlaylistUrl("http://h:8080/get.php?username=u&password=p&type=m3u&output=ts"))
            .isEqualTo("http://h:8080/get.php?username=u&password=p&output=ts&type=m3u_plus")
        assertThat(CatalogRepository.richPlaylistUrl("http://h/get.php?type=M3U")).isEqualTo("http://h/get.php?type=m3u_plus")
        assertThat(CatalogRepository.richPlaylistUrl("http://h/get.php?type=m3u_plus")).isNull()
        assertThat(CatalogRepository.richPlaylistUrl("http://h/get.php?username=u")).isNull()
        assertThat(CatalogRepository.richPlaylistUrl("http://h/lists/tv.m3u?type=m3u")).isNull()
        assertThat(CatalogRepository.richPlaylistUrl("not a url")).isNull()
    }

    private companion object {
        val PLAIN = """
            #EXTM3U
            #EXTINF:-1,GLOBO SP [FHD]
            http://panel.invalid:8080/u/p/101.ts
            #EXTINF:-1,GLOBO SP [HD]
            http://panel.invalid:8080/u/p/102.ts
            #EXTINF:-1,Ação Mutante
            http://panel.invalid:8080/movie/u/p/201.mp4
            #EXTINF:-1,007: Cassino Royale
            http://panel.invalid:8080/movie/u/p/202.mkv
            #EXTINF:-1,Elsbeth [L] S04E01
            http://panel.invalid:8080/series/u/p/301.mp4
            #EXTINF:-1,Elsbeth [L] S04E02
            http://panel.invalid:8080/series/u/p/302.mp4
            #EXTINF:-1,Elsbeth S01E01
            http://panel.invalid:8080/series/u/p/303.mp4
        """.trimIndent()

        val PLUS = """
            #EXTM3U
            #EXTINF:-1 tvg-id="globo.sp" tvg-logo="http://img.invalid/globo.png" group-title="Abertos",GLOBO SP [FHD]
            http://panel.invalid:8080/u/p/101.ts
            #EXTINF:-1 tvg-id="globo.sp" tvg-logo="http://img.invalid/globo.png" group-title="Abertos",GLOBO SP [HD]
            http://panel.invalid:8080/u/p/102.ts
            #EXTINF:-1 tvg-id="" tvg-logo="http://img.invalid/acao.jpg" group-title="Filmes | Lançamentos",Ação Mutante
            http://panel.invalid:8080/movie/u/p/201.mp4
            #EXTINF:-1 tvg-id="" group-title="Filmes | Ação",007: Cassino Royale
            http://panel.invalid:8080/movie/u/p/202.mkv
            #EXTINF:-1 tvg-id="" group-title="Séries | Drama",Elsbeth [L] S04E01
            http://panel.invalid:8080/series/u/p/301.mp4
            #EXTINF:-1 tvg-id="" group-title="Séries | Drama",Elsbeth [L] S04E02
            http://panel.invalid:8080/series/u/p/302.mp4
            #EXTINF:-1 tvg-id="" group-title="Séries | Drama",Elsbeth S01E01
            http://panel.invalid:8080/series/u/p/303.mp4
        """.trimIndent()
    }
}
