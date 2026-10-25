/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.vod.AlphabetIndex
import app.opentv.ui.vod.TitleAlphabet
import app.opentv.ui.vod.sortedByTitle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The A–Z jump bar's bucketing, sort and index. Titles are the kind a Brazilian M3U ships, where
 * accented first letters, digits and bracketed tags are common.
 */
class AlphabetIndexTest {

    private fun label(title: String): Char = TitleAlphabet.LABELS[TitleAlphabet.bucketOf(title)]

    private fun indexOf(titles: List<String>) = AlphabetIndex.of(titles) { it }

    private fun List<String>.sortedForBar(): List<String> = runBlocking { sortedByTitle { it } }

    @Test
    fun `accented first letters file under their base letter`() {
        assertThat(label("Órfãos")).isEqualTo('O')
        assertThat(label("Êxtase")).isEqualTo('E')
        assertThat(label("Ação Mortal")).isEqualTo('A')
        assertThat(label("Ñandú")).isEqualTo('N')
        assertThat(label("Çarşı")).isEqualTo('C')
        assertThat(label("Último Tango")).isEqualTo('U')
    }

    @Test
    fun `invisible marks at the start are skipped`() {
        // A real provider title: it opens with a left-to-right mark.
        assertThat(label("\u200EUm Grande Despertar [L]")).isEqualTo('U')
        assertThat(label("\uFEFFAvatar")).isEqualTo('A')
        assertThat(label("\u200B007")).isEqualTo('#')
    }

    @Test
    fun `case does not matter`() {
        assertThat(label("avatar")).isEqualTo('A')
        assertThat(label("AVATAR")).isEqualTo('A')
    }

    @Test
    fun `digits, symbols and other scripts file under the hash`() {
        assertThat(label("+ Velozes + Furiosos [L]")).isEqualTo('#')
        assertThat(label("[XXX] Something")).isEqualTo('#')
        assertThat(label("007 Contra Goldfinger")).isEqualTo('#')
        assertThat(label("(500) Dias com Ela")).isEqualTo('#')
        assertThat(label("¡Three Amigos!")).isEqualTo('#')
        assertThat(label("Москва")).isEqualTo('#')
        assertThat(label("東京物語")).isEqualTo('#')
        assertThat(label("")).isEqualTo('#')
    }

    @Test
    fun `Latin letters that do not decompose still fold to a letter`() {
        assertThat(label("Æon Flux")).isEqualTo('A')
        assertThat(label("Øresund")).isEqualTo('O')
        assertThat(label("Łódź")).isEqualTo('L')
        // Fullwidth letters decompose under NFKD.
        assertThat(label("Ａｍｅｌｉｅ")).isEqualTo('A')
    }

    @Test
    fun `sort puts the hash first, then folds accents and case`() {
        val sorted = listOf(
            "Os Outros",
            "zodíaco",
            "Órfãos",
            "007",
            "Avatar",
            "Orange County",
            "{Colchetes}",
            "Москва",
            "+ Velozes + Furiosos",
            "Êxtase",
        ).sortedForBar()

        // `{` and Cyrillic sort after `z` in code-point order; they still belong to the leading `#` run.
        assertThat(sorted.take(4)).containsExactly("+ Velozes + Furiosos", "007", "{Colchetes}", "Москва").inOrder()
        assertThat(sorted.drop(4))
            .containsExactly("Avatar", "Êxtase", "Orange County", "Órfãos", "Os Outros", "zodíaco")
            .inOrder()
    }

    @Test
    fun `sort keeps incoming order for titles that fold the same`() {
        data class Film(val title: String, val id: Int)
        val sorted = runBlocking {
            listOf(Film("Batman", 1), Film("Avatar", 2), Film("batman", 3), Film("Bátman", 4)).sortedByTitle { it.title }
        }

        assertThat(sorted.map { it.id }).containsExactly(2, 1, 3, 4).inOrder()
    }

    @Test
    fun `index gives the first position of each letter in the sorted grid`() {
        val grid = listOf("2012", "+ Velozes", "Avatar", "Ação", "Batman", "Êxtase", "Órfãos", "Os Outros").sortedForBar()
        val index = indexOf(grid)

        assertThat(grid).containsExactly("+ Velozes", "2012", "Ação", "Avatar", "Batman", "Êxtase", "Órfãos", "Os Outros")
            .inOrder()
        assertThat(index.firstIndexOf('#')).isEqualTo(0)
        assertThat(index.firstIndexOf('A')).isEqualTo(2)
        assertThat(index.firstIndexOf('B')).isEqualTo(4)
        assertThat(index.firstIndexOf('E')).isEqualTo(5)
        assertThat(index.firstIndexOf('O')).isEqualTo(6)
        assertThat(index.firstIndexOf('C')).isEqualTo(-1)
        assertThat(index.bucketAt(7)).isEqualTo(TitleAlphabet.LABELS.indexOf('O'))
    }

    @Test
    fun `index over a filtered list follows what is left`() {
        val full = listOf("Avatar", "Batman", "Batman Begins", "Cars", "Duna").sortedForBar()
        val filtered = full.filter { !it.startsWith("Batman") }
        val index = indexOf(filtered)

        assertThat(index.itemCount).isEqualTo(3)
        assertThat(index.hasTitles(TitleAlphabet.LABELS.indexOf('B'))).isFalse()
        assertThat(index.firstIndexOf('C')).isEqualTo(1)
        assertThat(index.firstIndexOf('D')).isEqualTo(2)
    }

    @Test
    fun `order inside a letter does not matter, only that letters run in bar order`() {
        // Grouped by letter but newest first within each, as a later re-ordering might leave it.
        val index = indexOf(listOf("1917", "Avatar", "Amor", "Ação", "Duna", "Dumbo", "Zorro"))

        assertThat(index.firstIndexOf('#')).isEqualTo(0)
        assertThat(index.firstIndexOf('A')).isEqualTo(1)
        assertThat(index.firstIndexOf('D')).isEqualTo(4)
        assertThat(index.firstIndexOf('Z')).isEqualTo(6)
        assertThat(index.bucketAt(5)).isEqualTo(TitleAlphabet.LABELS.indexOf('D'))
    }

    @Test
    fun `a sorted category of thousands indexes every letter at its first title`() {
        val titles = (0 until 5_000).map { i -> "${TitleAlphabet.LABELS[1 + i % 26]}${"%04d".format(i)}" } +
            listOf("Órfãos", "Êxtase", "+ Velozes", "[XXX] Algo")
        val grid = titles.sortedForBar()
        val index = indexOf(grid)

        for (bucket in 0 until TitleAlphabet.BUCKETS) {
            val expected = grid.indexOfFirst { TitleAlphabet.bucketOf(it) == bucket }
            assertThat(index.firstIndexOf(bucket)).isEqualTo(expected)
        }
        grid.indices.forEach { i -> assertThat(index.bucketAt(i)).isEqualTo(TitleAlphabet.bucketOf(grid[i])) }
    }

    @Test
    fun `an empty list has no letters and answers safely`() {
        val index = indexOf(emptyList())

        assertThat(index.itemCount).isEqualTo(0)
        assertThat((0 until TitleAlphabet.BUCKETS).map { index.firstIndexOf(it) }.distinct()).containsExactly(-1)
        assertThat(index.nearestWithTitles(5)).isEqualTo(-1)
        assertThat(index.bucketAt(0)).isEqualTo(TitleAlphabet.OTHER)
    }

    @Test
    fun `scrubbing over an empty letter lands on the next one down, or the last one above`() {
        val index = indexOf(listOf("Avatar", "Duna", "Matrix"))
        val b = { c: Char -> TitleAlphabet.LABELS.indexOf(c) }

        assertThat(index.nearestWithTitles(b('A'))).isEqualTo(b('A'))
        assertThat(index.nearestWithTitles(b('B'))).isEqualTo(b('D'))
        assertThat(index.nearestWithTitles(b('#'))).isEqualTo(b('A'))
        assertThat(index.nearestWithTitles(b('Z'))).isEqualTo(b('M'))
    }

    @Test
    fun `bucketAt clamps an index from a list that has since shrunk`() {
        val index = indexOf(listOf("Avatar", "Batman"))

        assertThat(index.bucketAt(99)).isEqualTo(TitleAlphabet.LABELS.indexOf('B'))
        assertThat(index.bucketAt(-1)).isEqualTo(TitleAlphabet.LABELS.indexOf('A'))
    }
}
