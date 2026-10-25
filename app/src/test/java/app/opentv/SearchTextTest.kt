/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.SearchText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The folding both sides of a search go through. Names are real titles from a Brazilian playlist. */
class SearchTextTest {

    @Test
    fun `accents and case fold away`() {
        assertThat(SearchText.normalize("Pinóquio")).isEqualTo("pinoquio")
        assertThat(SearchText.normalize("AÇÃO")).isEqualTo("acao")
        assertThat(SearchText.normalize("Comédia Romântica")).isEqualTo("comedia romantica")
        assertThat(SearchText.normalize("Pokémon: Mewtwo Contra-Ataca")).isEqualTo("pokemon mewtwo contra ataca")
    }

    @Test
    fun `punctuation becomes one space`() {
        assertThat(SearchText.normalize("Homem-Aranha: Sem Volta Para Casa")).isEqualTo("homem aranha sem volta para casa")
        assertThat(SearchText.normalize("Velozes & Furiosos 10 4K [Hybrid]")).isEqualTo("velozes furiosos 10 4k hybrid")
        assertThat(SearchText.normalize("  --  ")).isEmpty()
    }

    @Test
    fun `apostrophes join the word instead of splitting it`() {
        assertThat(SearchText.normalize("Adult Swim's The Elephant")).isEqualTo("adult swims the elephant")
        assertThat(SearchText.normalize("Ocean’s Eleven")).isEqualTo("oceans eleven")
    }

    @Test
    fun `invisible formatting characters inside a word are dropped, not split on`() {
        assertThat(SearchText.normalize("O Pistoleir\u202Ao\u202C")).isEqualTo("o pistoleiro")
        assertThat(SearchText.normalize("Peni\u00ADnsula Iberica")).isEqualTo("peninsula iberica")
        assertThat(SearchText.normalize("\u200EFilme")).isEqualTo("filme")
    }

    @Test
    fun `letters NFD keeps whole are spelled out, superscripts folded`() {
        assertThat(SearchText.normalize("Straße")).isEqualTo("strasse")
        assertThat(SearchText.normalize("Ærø")).isEqualTo("aero")
        assertThat(SearchText.normalize("[REC]³ Gênesis")).isEqualTo("rec 3 genesis")
    }

    @Test
    fun `the stored key is padded so a first word can be matched as a word start`() {
        assertThat(SearchText.keyOf("Pinóquio")).isEqualTo(" pinoquio ")
        // The cleaned title, then only the words the raw name adds.
        assertThat(SearchText.keyOf("Barbie (2023)", "4K-EN - Barbie (2023)")).isEqualTo(" barbie 2023 4k en ")
        assertThat(SearchText.keyOf("Junk", "Junk")).isEqualTo(" junk ")
    }

    @Test
    fun `query tokens are folded, split and de-duplicated`() {
        assertThat(SearchText.tokens("Homem Aranha")).containsExactly("homem", "aranha").inOrder()
        assertThat(SearchText.tokens("velozes  10")).containsExactly("velozes", "10").inOrder()
        assertThat(SearchText.tokens("o o")).containsExactly("o")
        assertThat(SearchText.tokens("%_")).isEmpty()
    }
}
