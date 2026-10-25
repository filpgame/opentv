/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.StreamKind
import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These tests are built from the shapes real providers actually emit, including the broken
 * ones. Every "malformed" case here is something that should cost the user one channel, never
 * the whole playlist.
 */
class M3uParserTest {

    @Test
    fun `parses a well formed playlist`() {
        val playlist = """
            #EXTM3U url-tvg="http://example.com/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://img/bbc1.png" group-title="UK",BBC One HD
            http://example.com:8080/live/u/p/1.m3u8
            #EXTINF:-1 tvg-id="itv1.uk" tvg-logo="http://img/itv.png" group-title="UK",ITV 1
            http://example.com:8080/live/u/p/2.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 7)

        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrl)
            .isEqualTo("http://example.com/xmltv.php?username=u&password=p")

        val first = result.channels.first()
        assertThat(first.name).isEqualTo("BBC One HD")
        assertThat(first.epgChannelId).isEqualTo("bbc1.uk")
        assertThat(first.categoryId).isEqualTo("UK")
        assertThat(first.logoUrl).isEqualTo("http://img/bbc1.png")
        assertThat(first.sourceId).isEqualTo(7)
        assertThat(first.streamUrl).isEqualTo("http://example.com:8080/live/u/p/1.m3u8")
    }

    @Test
    fun `a URL with no EXTINF is skipped without losing the rest`() {
        val playlist = """
            #EXTM3U
            http://example.com/orphan.m3u8
            #EXTINF:-1 tvg-id="good",Good Channel
            http://example.com/good.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Good Channel")
    }

    @Test
    fun `falls back to tvg-name when the display name after the comma is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" tvg-name="Fallback Name",
            http://example.com/x.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `duplicate tvg-ids do not collide`() {
        // Providers routinely list SD and HD variants under one tvg-id. The unique index on
        // (sourceId, streamId) would otherwise abort the entire import; dropping the later ones
        // would lose the variants the player switches between.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel SD
            http://example.com/sd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel HD
            http://example.com/hd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel SD again
            http://example.com/sd.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels.map { it.name }).containsExactly("Channel SD", "Channel HD").inOrder()
        assertThat(channels.first().streamId).isEqualTo("tvg:dup")
        assertThat(channels.last().streamId).startsWith("url:")
        // Both still point the guide at the shared id.
        assertThat(channels.map { it.epgChannelId }).containsExactly("dup", "dup")
    }

    @Test
    fun `channels without a tvg-id still get a stable id derived from the url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,No Id Channel
            http://example.com/noid.m3u8
        """.trimIndent()

        val first = M3uParser.parse(playlist, sourceId = 1).channels.single()
        val second = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(first.streamId).startsWith("url:")
        // Stability matters: an unstable id means favourites are lost on every refresh.
        assertThat(first.streamId).isEqualTo(second.streamId)
        assertThat(first.epgChannelId).isNull()
    }

    @Test
    fun `unknown directives between EXTINF and the url are ignored`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTHTTP:{"User-Agent":"SomePlayer/1.0"}
            http://example.com/a.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(1)
        assertThat(channels.single().streamUrl).isEqualTo("http://example.com/a.m3u8")
    }

    @Test
    fun `channel numbers come from tvg-chno when present`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-chno="101",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().number).isEqualTo(101)
    }

    @Test
    fun `an empty playlist yields no channels rather than throwing`() {
        val result = M3uParser.parse("#EXTM3U\n", sourceId = 1)

        assertThat(result.channels).isEmpty()
        assertThat(result.skippedEntries).isEqualTo(0)
    }

    @Test
    fun `entries are sorted into live, movies and series by their Xtream path`() {
        assertThat(M3uParser.kindOf("http://h:80/u/p/1")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uParser.kindOf("http://h:80/u/p/1.ts")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uParser.kindOf("http://h:80/live/u/p/1.m3u8")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uParser.kindOf("http://h:80/movie/u/p/2.mp4")).isEqualTo(StreamKind.MOVIE)
        assertThat(M3uParser.kindOf("http://h:80/MOVIE/u/p/2.MP4?token=x")).isEqualTo(StreamKind.MOVIE)
        assertThat(M3uParser.kindOf("http://h:80/series/u/p/3.mkv")).isEqualTo(StreamKind.SERIES)
        // A panel behind a path prefix.
        assertThat(M3uParser.kindOf("https://h/xc/series/u/p/3.mkv")).isEqualTo(StreamKind.SERIES)
        // 'movie' anywhere else in a path says nothing about the entry.
        assertThat(M3uParser.kindOf("http://cdn/movie/channel.m3u8")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uParser.kindOf("http://cdn/hls/movie/a/b/c/index.m3u8")).isEqualTo(StreamKind.LIVE)
    }

    @Test
    fun `a plain Xtream export files ungrouped movies and series under their initial`() {
        // `get.php?type=m3u`: names and URLs only, no group-title, no logos.
        val playlist = """
            #EXTM3U
            #EXTINF:-1,GLOBO SP [FHD]
            http://h:80/u/p/101.ts
            #EXTINF:-1,Ação Mutante
            http://h:80/movie/u/p/201.mp4
            #EXTINF:-1,007: Cassino Royale
            http://h:80/movie/u/p/202.mkv
            #EXTINF:-1,Elsbeth [L] S04E02
            http://h:80/series/u/p/302.mp4
            #EXTINF:-1,Elsbeth [L] S04E01
            http://h:80/series/u/p/301.mp4
            #EXTINF:-1,Elsbeth S01E01
            http://h:80/series/u/p/303.mp4
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 3)

        assertThat(result.channels.map { it.name }).containsExactly("GLOBO SP [FHD]")
        assertThat(result.movies.map { it.name to it.categoryId }).containsExactly(
            "Ação Mutante" to "az:A",
            "007: Cassino Royale" to "az:#",
        ).inOrder()
        assertThat(result.movies.map { it.containerExtension }).containsExactly("mp4", "mkv").inOrder()

        // Subtitled and dubbed releases are separate shows, as the provider lists them.
        assertThat(result.series.map { it.seriesId }).containsExactly("Elsbeth [L]", "Elsbeth").inOrder()
        assertThat(result.series.map { it.categoryId }).containsExactly("az:E", "az:E")
        val subtitled = result.episodes.filter { it.seriesId == "Elsbeth [L]" }
        assertThat(subtitled.map { it.season to it.episodeNumber }).containsExactly(4 to 2, 4 to 1).inOrder()
        assertThat(subtitled.first().title).isEqualTo("Elsbeth [L]")

        assertThat(result.categories.filter { it.kind == StreamKind.LIVE }).isEmpty()
        assertThat(result.categories.filter { it.kind == StreamKind.MOVIE }.map { it.id to it.name })
            .containsExactly("az:#" to "#", "az:A" to "A").inOrder()
        assertThat(result.categories.filter { it.kind == StreamKind.SERIES }.map { it.name })
            .containsExactly("E")
    }

    @Test
    fun `group-titles become each kind's categories`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://img/globo.png" group-title="Abertos",GLOBO SP
            http://h:80/u/p/101.ts
            #EXTINF:-1 tvg-logo="http://img/band.png" group-title="Abertos",BAND
            http://h:80/u/p/102.ts
            #EXTINF:-1 tvg-logo="http://img/espn.png" group-title="Esportes",ESPN
            http://h:80/u/p/103.ts
            #EXTINF:-1 tvg-logo="http://img/a.jpg" group-title="Filmes | Lançamentos",Zebra
            http://h:80/movie/u/p/201.mp4
            #EXTINF:-1 group-title="Filmes | Ação",Abelha
            http://h:80/movie/u/p/202.mp4
            #EXTINF:-1 group-title="Séries | Drama",Silo S01E01
            http://h:80/series/u/p/301.mp4
            #EXTINF:-1 tvg-logo="http://img/silo.jpg" group-title="Séries | Drama",Silo S01E02 - Machines
            http://h:80/series/u/p/302.mp4
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 3)
        fun names(kind: StreamKind) = result.categories.filter { it.kind == kind }.map { it.name }

        assertThat(names(StreamKind.LIVE)).containsExactly("Abertos", "Esportes").inOrder()
        // The panel's own order, not alphabetical.
        assertThat(names(StreamKind.MOVIE)).containsExactly("Filmes | Lançamentos", "Filmes | Ação").inOrder()
        assertThat(names(StreamKind.SERIES)).containsExactly("Séries | Drama")

        assertThat(result.movies.first().posterUrl).isEqualTo("http://img/a.jpg")
        assertThat(result.movies.first().categoryId).isEqualTo("Filmes | Lançamentos")
        val silo = result.series.single()
        // The only image any episode carries becomes the show's poster.
        assertThat(silo.posterUrl).isEqualTo("http://img/silo.jpg")
        assertThat(result.episodes.map { it.title }).containsExactly("Silo", "Machines").inOrder()
    }

    @Test
    fun `a show's cover is its own most used image, never the panel's placeholder`() {
        // Episodes carry their own stills, and the panel fills gaps with one stock image.
        val playlist = buildString {
            appendLine("#EXTM3U")
            fun entry(name: String, logo: String, id: Int) {
                appendLine("""#EXTINF:-1 tvg-logo="$logo",$name""")
                appendLine("http://h:80/series/u/p/$id.mp4")
            }
            entry("Silo S01E01", "http://img/stock.jpg", 1)
            entry("Silo S01E02", "http://img/silo-cover.jpg", 2)
            entry("Silo S01E03", "http://img/silo-cover.jpg", 3)
            entry("Silo S01E04", "http://img/silo-e4.jpg", 4)
            entry("Silo [L] S01E01", "http://img/silo-cover.jpg", 5)
            entry("Dark S01E01", "http://img/stock.jpg", 6)
            entry("Lost S01E01", "http://img/stock.jpg", 7)
            // Three releases of one show, one shared cover.
            entry("Elsbeth S01E01", "http://img/elsbeth.jpg", 8)
            entry("Elsbeth [L] S01E01", "http://img/elsbeth.jpg", 9)
            entry("Elsbeth (2024) [L] S01E01", "http://img/elsbeth.jpg", 10)
        }

        val result = M3uParser.parse(playlist, sourceId = 1)
        val covers = result.series.associate { it.name to it.posterUrl }

        assertThat(covers["Silo"]).isEqualTo("http://img/silo-cover.jpg")
        // Shared with the subtitled release only: still a cover.
        assertThat(covers["Silo [L]"]).isEqualTo("http://img/silo-cover.jpg")
        assertThat(covers["Elsbeth (2024) [L]"]).isEqualTo("http://img/elsbeth.jpg")
        // The stock image is on three different shows: no cover beats a wrong one.
        assertThat(covers["Dark"]).isNull()
        assertThat(covers["Lost"]).isNull()
        assertThat(result.episodes.filter { it.stillUrl == "http://img/stock.jpg" }).isEmpty()
        assertThat(result.episodes.single { it.streamUrl.endsWith("/4.mp4") }.stillUrl)
            .isEqualTo("http://img/silo-e4.jpg")
    }

    @Test
    fun `episodes without a season and episode marker are numbered in playlist order`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Documentário Especial
            http://h:80/series/u/p/1.mp4
            #EXTINF:-1,Documentário Especial
            http://h:80/series/u/p/2.mp4
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.series.map { it.name }).containsExactly("Documentário Especial")
        assertThat(result.episodes.map { it.season to it.episodeNumber }).containsExactly(1 to 1, 1 to 2).inOrder()
    }

    @Test
    fun `base titles drop release tags and punctuation`() {
        assertThat(M3uParser.baseTitleOf("Elsbeth [L]")).isEqualTo("elsbeth")
        assertThat(M3uParser.baseTitleOf("Vereda Tropical (1984)")).isEqualTo("veredatropical")
        assertThat(M3uParser.baseTitleOf("NCIS: Investigação Naval")).isEqualTo("ncisinvestigaçãonaval")
    }

    @Test
    fun `initials fold accents and send everything else to the hash bucket`() {
        assertThat(M3uParser.initialOf("Évora")).isEqualTo("E")
        assertThat(M3uParser.initialOf("ônibus")).isEqualTo("O")
        assertThat(M3uParser.initialOf("12 Homens")).isEqualTo("#")
        assertThat(M3uParser.initialOf("[XXX] Something")).isEqualTo("#")
        assertThat(M3uParser.initialOf("")).isEqualTo("#")
    }
}
