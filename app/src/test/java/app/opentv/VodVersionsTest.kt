/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.parser.VodVersions
import app.opentv.data.repo.foldVersions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Telling one title's versions apart, and folding them back together. Real names from a playlist. */
class VodVersionsTest {

    private fun key(name: String, year: Int? = null) = VodVersions.variantKeyOf(VodTitleCleaner.clean(name), year)

    @Test
    fun `dubbed, subtitled and 4K copies of one film share a key`() {
        val keys = listOf(
            "Velozes & Furiosos 10",
            "Velozes & Furiosos 10 [L]",
            "Velozes & Furiosos 10 4K",
            "Velozes & Furiosos 10 4K [Hybrid]",
        ).map { key(it) }.toSet()
        assertThat(keys).hasSize(1)
        assertThat(key("Junk")).isEqualTo(key("Junk [L]"))
        assertThat(key("Todo Mundo em Pânico")).isEqualTo(key("Todo Mundo em Pânico 4K [DV][HDR]"))
        assertThat(key("Pinóquio")).isEqualTo(key("Pinóquio 4k [HDR]"))
        assertThat(key("Pinóquio: Maldição de Madeira")).isEqualTo(key("Pinóquio: Maldição de Madeira [L] [Cinema]"))
    }

    @Test
    fun `an ampersand and the word e or and fold together`() {
        assertThat(key("Velozes E Furiosos 9 4K")).isEqualTo(key("Velozes & Furiosos 9"))
        assertThat(key("Velozes e Furiosos 9")).isEqualTo(key("Velozes & Furiosos 9 [L]"))
        assertThat(key("Pride and Prejudice")).isEqualTo(key("Pride & Prejudice"))
        // A leading É is a word of the title, not a connector.
        assertThat(key("É Amor?")).isNotEqualTo(key("Amor"))
    }

    @Test
    fun `different films, cuts and real bracketed titles stay apart`() {
        assertThat(key("Pinóquio (2022)")).isNotEqualTo(key("Pinóquio"))
        assertThat(key("Pinóquio 3000")).isNotEqualTo(key("Pinóquio"))
        assertThat(key("Junkyard Dog [L]")).isNotEqualTo(key("Junk [L]"))
        assertThat(key("Duna [Extended Edition]")).isNotEqualTo(key("Duna"))
        assertThat(key("[REC]")).isNotEqualTo(key("[REC] 2"))
        assertThat(key("Velozes & Furiosos 9")).isNotEqualTo(key("Velozes & Furiosos 10"))
    }

    @Test
    fun `a stored year and the one in the name are the same year`() {
        assertThat(key("The Godfather", year = 1972)).isEqualTo(key("The Godfather 1972 HD"))
        assertThat(key("Not Yours (2026) [L]")).isEqualTo(key("Not Yours (2026)"))
    }

    @Test
    fun `the displayed title loses version tags but keeps everything else`() {
        assertThat(VodTitleCleaner.clean("Junk [L]")).isEqualTo("Junk")
        assertThat(VodTitleCleaner.clean("Velozes & Furiosos 10 4K [Hybrid]")).isEqualTo("Velozes & Furiosos 10")
        assertThat(VodTitleCleaner.clean("Moana 4K [DV][HDR]")).isEqualTo("Moana")
        assertThat(VodTitleCleaner.clean("Pinóquio: Maldição de Madeira [L] [Cinema]")).isEqualTo("Pinóquio: Maldição de Madeira")
        assertThat(VodTitleCleaner.clean("Elsbeth (2024) [L]")).isEqualTo("Elsbeth (2024)")
        assertThat(VodTitleCleaner.clean("Duna [Extended Edition]")).isEqualTo("Duna [Extended Edition]")
    }

    @Test
    fun `subtitled comes from the tag or the category`() {
        assertThat(VodVersions.isSubtitled("Junk [L]", "Filmes | Legendados")).isTrue()
        assertThat(VodVersions.isSubtitled("Junk [L]", null)).isTrue()
        assertThat(VodVersions.isSubtitled("Black Clover", "Series | Legendadas")).isTrue()
        assertThat(VodVersions.isSubtitled("Algo", "Filmes | [XXX] Adultos [L]")).isTrue()
        assertThat(VodVersions.isSubtitled("Junk", "Filmes | Acao")).isFalse()
        // A one-letter word in a title is not the tag.
        assertThat(VodVersions.isSubtitled("Plano L", null)).isFalse()
    }

    @Test
    fun `national comes from a Nacionais category or tag`() {
        assertThat(VodVersions.isNational("Tropa de Elite", "Filmes | Nacionais")).isTrue()
        assertThat(VodVersions.isNational("Tropa de Elite [Nacional]", null)).isTrue()
        assertThat(VodVersions.isNational("Tropa de Elite", "Filmes | Acao")).isFalse()
    }

    @Test
    fun `stamping ranks 4K from the name, else from the category`() {
        assertThat(VodVersions.stamp("Mother Mary 4K [HDR]", null, "Filmes | 4K").qualityRank).isAtLeast(VodVersions.UHD_RANK)
        assertThat(VodVersions.stamp("O Sorveteiro", null, "Filmes | 4K").qualityRank).isAtLeast(VodVersions.UHD_RANK)
        assertThat(VodVersions.stamp("Junk", null, "Filmes | Acao").qualityRank).isEqualTo(0)
    }

    @Test
    fun `stamping fills every field from name and category`() {
        val stamp = VodVersions.stamp("Pinóquio por Guillermo Del Toro [L]", null, "Filmes | Legendados")
        assertThat(stamp.searchKey).isEqualTo(" pinoquio por guillermo del toro l ")
        assertThat(stamp.variantKey).isEqualTo(key("Pinóquio por Guillermo Del Toro"))
        assertThat(stamp.subtitled).isTrue()
        assertThat(stamp.national).isFalse()
        assertThat(stamp.adult).isFalse()
        assertThat(VodVersions.stamp("Elite Devassa", null, "Filmes | [XXX] Adultos").adult).isTrue()
    }

    @Test
    fun `picture labels name what sets a copy apart`() {
        assertThat(VodVersions.pictureLabels("Velozes & Furiosos 10 4K [Hybrid]")).containsExactly("4K", "Hybrid").inOrder()
        assertThat(VodVersions.pictureLabels("Todo Mundo em Pânico 4K [DV][HDR]")).containsExactly("4K", "DV", "HDR").inOrder()
        assertThat(VodVersions.pictureLabels("Pinóquio 4k [HDR]")).containsExactly("4K", "HDR").inOrder()
        assertThat(VodVersions.pictureLabels("O Sorveteiro", qualityRank = 400)).containsExactly("4K")
        assertThat(VodVersions.pictureLabels("Filme [Dublagem Nao Oficial]")).containsExactly("Dublagem Nao Oficial")
        assertThat(VodVersions.pictureLabels("Junk [L]")).isEmpty()
    }

    private fun movie(id: Long, name: String, category: String = "Filmes | Acao", sourceId: Long = 1): Movie {
        val s = VodVersions.stamp(name, null, category)
        return Movie(
            id = id, sourceId = sourceId, streamId = "s$id", name = name, categoryId = category,
            posterUrl = null, rating = null, year = null, plot = null, durationSeconds = null,
            containerExtension = null, streamUrl = "http://x.invalid/$id",
            searchKey = s.searchKey, variantKey = s.variantKey, subtitled = s.subtitled,
            qualityRank = s.qualityRank, national = s.national, adult = s.adult,
        )
    }

    @Test
    fun `folding makes one poster per title, dubbed and not 4K on the poster`() {
        val sub = movie(1, "Velozes & Furiosos 10 [L]", "Filmes | Legendados")
        val uhd = movie(2, "Velozes & Furiosos 10 4K [Hybrid]", "Filmes | 4K")
        val dub = movie(3, "Velozes & Furiosos 10")
        val other = movie(4, "Junk")

        val groups = foldVersions(listOf(sub, uhd, dub, other))

        assertThat(groups.map { it.primary.id }).containsExactly(3L, 4L).inOrder()
        val velozes = groups.first()
        // Dubbed first, then by quality; subtitled last.
        assertThat(velozes.versions.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(velozes.subtitled).isTrue()
        assertThat(velozes.dubbed).isTrue()
        assertThat(velozes.uhd).isTrue()
        assertThat(velozes.offersLanguageChoice).isTrue()
        assertThat(groups[1].offersLanguageChoice).isFalse()
    }

    @Test
    fun `versions from outside the list join a group but never start one`() {
        val sub = movie(1, "Junk [L]", "Filmes | Legendados")
        val dub = movie(2, "Junk")
        val unrelated = movie(3, "Outro Filme")

        val groups = foldVersions(shown = listOf(sub), extra = listOf(dub, unrelated))

        assertThat(groups).hasSize(1)
        // The poster stays the copy that is in the list being shown.
        assertThat(groups.single().primary.id).isEqualTo(1L)
        assertThat(groups.single().versions.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `different sources and unstamped rows are never folded`() {
        val a = movie(1, "Junk", sourceId = 1)
        val b = movie(2, "Junk [L]", sourceId = 2)
        val blankA = movie(3, "Junk").copy(variantKey = "")
        val blankB = movie(4, "Junk [L]").copy(variantKey = "")

        assertThat(foldVersions(listOf(a, b, blankA, blankB))).hasSize(4)
    }
}
