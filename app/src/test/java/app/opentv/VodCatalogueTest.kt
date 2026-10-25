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
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
 * Movies and Shows over a real (in-memory) database: a playlist imported through the repository is
 * stamped, then searched, folded, filtered and favourited the way the screens do it. The titles are
 * the ones that failed on a car head unit before this existed.
 */
// Same Robolectric setup as PlaylistSyncTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodCatalogueTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repo: CatalogRepository
    private lateinit var source: Source

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
            .allowMainThreadQueries().build()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(PLAYLIST.toResponseBody())
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
            Source(name = "lista", kind = SourceKind.M3U, url = "http://panel.invalid/lista.m3u"),
        )
        source = db.sources().byId(id)!!
        repo.syncLive(source, 1_000L)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun movieSearch(query: String, includeAdult: Boolean = false): List<String> =
        db.movies().search(query, includeAdult).first().map { it.name }

    private suspend fun movie(name: String): Movie = db.movies().all(includeAdult = true).single { it.name == name }

    @Test
    fun `search ignores accents, case and punctuation`() = runTest {
        assertThat(movieSearch("pinoquio")).containsExactly("Pinóquio", "Pinóquio [L]", "Pinóquio (2022)")
        assertThat(movieSearch("PINÓQUIO")).hasSize(3)
        assertThat(movieSearch("homem aranha")).containsExactly("Homem-Aranha: Sem Volta Para Casa")
        assertThat(movieSearch("acao")).containsExactly("Ação Mutante", "Velozes & Furiosos 5: Operação Rio")
    }

    @Test
    fun `every word must match, in any order`() = runTest {
        assertThat(movieSearch("velozes 10")).containsExactly(
            "Velozes & Furiosos 10",
            "Velozes & Furiosos 10 [L]",
            "Velozes & Furiosos 10 4K [Hybrid]",
        )
        assertThat(movieSearch("aranha homem")).containsExactly("Homem-Aranha: Sem Volta Para Casa")
        assertThat(movieSearch("velozes 10 mutante")).isEmpty()
        assertThat(movieSearch("!!")).isEmpty()
    }

    @Test
    fun `results rank a title start, then a word start, then the rest, shortest first`() = runTest {
        assertThat(movieSearch("amor")).containsExactly(
            "Amor Bandido",
            "O Amor Acontece",
            "Glamorosa",
        ).inOrder()
        // Same rank: the shorter title is the closer match, which puts the plain dubbed copy first.
        assertThat(movieSearch("velozes 10").first()).isEqualTo("Velozes & Furiosos 10")
    }

    @Test
    fun `the ranking happens before the limit`() = runTest {
        // Alphabetically "Glamorosa" comes first; ranked, the title that starts with the word wins.
        assertThat(db.movies().search("amor", includeAdult = false, limit = 1).first().map { it.name })
            .containsExactly("Amor Bandido")
    }

    @Test
    fun `adult titles stay out of search, recent rows and categories until unlocked`() = runTest {
        // One adult by its category, one by its own tag in an ordinary category.
        assertThat(movieSearch("devassa")).isEmpty()
        assertThat(movieSearch("devassa", includeAdult = true)).containsExactly("Elite Devassa")
        assertThat(movieSearch("cena")).isEmpty()
        assertThat(movieSearch("cena", includeAdult = true)).containsExactly("Cena Qualquer [XXX]")
        assertThat(db.movies().observeRecentlyAdded(100, includeAdult = false).first().map { it.name })
            .containsNoneOf("Elite Devassa", "Cena Qualquer [XXX]")
        assertThat(db.movies().all(includeAdult = false).map { it.name }).doesNotContain("Cena Qualquer [XXX]")
        // A mainstream film with an adult-looking name is not caught.
        assertThat(movieSearch("reativado")).containsExactly("xXx: Reativado")
    }

    @Test
    fun `search folds the versions of a title into one result`() = runTest {
        val groups = repo.searchMovies("velozes 10", includeAdult = false).first()
        assertThat(groups).hasSize(1)
        val velozes = groups.single()
        assertThat(velozes.primary.name).isEqualTo("Velozes & Furiosos 10")
        assertThat(velozes.primary.displayTitle).isEqualTo("Velozes & Furiosos 10")
        assertThat(velozes.versions).hasSize(3)
        assertThat(velozes.offersLanguageChoice).isTrue()
        assertThat(velozes.uhd).isTrue()
    }

    @Test
    fun `a category grid shows its own titles with every version from other categories`() = runTest {
        val legendados = repo.observeMovieGroups("Filmes | Legendados", includeAdult = false).first()
        val names = legendados.map { it.primary.name }
        assertThat(names).containsExactly("Velozes & Furiosos 10 [L]", "Pinóquio [L]", "Junk [L]")
        val velozes = legendados.single { it.primary.name == "Velozes & Furiosos 10 [L]" }
        assertThat(velozes.versions.map { it.name }).containsExactly(
            "Velozes & Furiosos 10",
            "Velozes & Furiosos 10 4K [Hybrid]",
            "Velozes & Furiosos 10 [L]",
        ).inOrder()
        // Junk is only listed subtitled: no dubbed copy, so no language choice.
        assertThat(legendados.single { it.primary.name == "Junk [L]" }.offersLanguageChoice).isFalse()
    }

    @Test
    fun `a version lookup returns every copy in picker order`() = runTest {
        val versions = repo.movieVersions(movie("Velozes & Furiosos 10 4K [Hybrid]"), includeAdult = false)
        assertThat(versions.map { it.name }).containsExactly(
            "Velozes & Furiosos 10",
            "Velozes & Furiosos 10 4K [Hybrid]",
            "Velozes & Furiosos 10 [L]",
        ).inOrder()
    }

    @Test
    fun `favouriting a title favourites every version, and My list shows it once`() = runTest {
        repo.setMovieFavourite(movie("Velozes & Furiosos 10 [L]"), true)

        assertThat(db.movies().all(includeAdult = false).filter { it.favourite }).hasSize(3)
        val myList = repo.observeFavouriteMovies(includeAdult = false).first()
        assertThat(myList).hasSize(1)
        assertThat(myList.single().primary.name).isEqualTo("Velozes & Furiosos 10")

        repo.setMovieFavourite(movie("Velozes & Furiosos 10"), false)
        assertThat(repo.observeFavouriteMovies(includeAdult = false).first()).isEmpty()
    }

    @Test
    fun `shows fold their subtitled copy too`() = runTest {
        val groups = repo.searchSeries("the boys", includeAdult = false).first()
        assertThat(groups).hasSize(1)
        assertThat(groups.single().versions.map { it.name }).containsExactly("The Boys", "The Boys [L]").inOrder()
    }

    @Test
    fun `channel search is accent-blind too`() = runTest {
        assertThat(db.channels().search("tnt series").first().map { it.name }).containsExactly("BR| TNT SÉRIES HD")
    }

    @Test
    fun `rows stored before the stamp columns existed are stamped once, locally`() = runTest {
        // What the v13 migration leaves behind: rows with every stamp column at its default.
        val stored = db.movies().all(includeAdult = true)
        db.movies().insertOrReplace(
            stored.map { it.copy(searchKey = "", variantKey = "", subtitled = false, qualityRank = 0, national = false, adult = false) },
        )
        db.series().insertOrReplace(
            db.series().all(includeAdult = true).map { it.copy(searchKey = "", variantKey = "") },
        )
        assertThat(movieSearch("pinoquio")).isEmpty()

        val changed = repo.restampVod()

        assertThat(changed).isEqualTo(stored.size + 2)
        assertThat(db.movies().all(includeAdult = true)).containsExactlyElementsIn(stored)
        assertThat(movieSearch("pinoquio")).hasSize(3)
        assertThat(repo.restampVod()).isEqualTo(0)
    }

    private companion object {
        fun entry(group: String, name: String, id: Int, kind: String = "movie") =
            "#EXTINF:-1 group-title=\"$group\",$name\nhttp://panel.invalid:8080/$kind/u/p/$id.mp4"

        val PLAYLIST = listOf(
            "#EXTM3U",
            "#EXTINF:-1 group-title=\"Canais\",BR| TNT SÉRIES HD\nhttp://panel.invalid:8080/u/p/1.ts",
            entry("Filmes | Legendados", "Velozes & Furiosos 10 [L]", 101),
            entry("Filmes | Legendados", "Pinóquio [L]", 102),
            entry("Filmes | Legendados", "Junk [L]", 103),
            entry("Filmes | Acao", "Velozes & Furiosos 10", 104),
            entry("Filmes | Acao", "Velozes & Furiosos 5: Operação Rio", 105),
            entry("Filmes | Acao", "Homem-Aranha: Sem Volta Para Casa", 106),
            entry("Filmes | Acao", "Ação Mutante", 107),
            entry("Filmes | Acao", "xXx: Reativado", 108),
            entry("Filmes | 4K", "Velozes & Furiosos 10 4K [Hybrid]", 109),
            entry("Filmes | Familia", "Pinóquio", 110),
            entry("Filmes | Familia", "Pinóquio (2022)", 111),
            entry("Filmes | Romance", "Glamorosa", 112),
            entry("Filmes | Romance", "O Amor Acontece", 113),
            entry("Filmes | Romance", "Amor Bandido", 114),
            entry("Filmes | [XXX] Adultos", "Elite Devassa", 115),
            entry("Filmes | Drama", "Cena Qualquer [XXX]", 116),
            entry("Series | Amazon Prime Video", "The Boys S01E01", 201, "series"),
            entry("Series | Legendadas", "The Boys [L] S01E01", 202, "series"),
        ).joinToString("\n")
    }
}
