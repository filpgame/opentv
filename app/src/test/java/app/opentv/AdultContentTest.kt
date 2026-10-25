/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.AdultContent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Adult detection. Every negative here is a real mainstream title or category from the playlist the
 * rules were written against; hiding one of them would be a bug a user notices.
 */
class AdultContentTest {

    @Test
    fun `adult categories are recognised by their label`() {
        listOf(
            "Filmes | [XXX] Adultos",
            "Filmes | [XXX] Adultos [L]",
            "Filmes | [XXX] Bella da Semana",
            "Filmes | [XXX] Pornochanchada",
            "Filmes +18",
            "18+ Movies",
            "Adult",
            "Hentai",
            "PORNÔ",
        ).forEach { assertWithMessage(it).that(AdultContent.isAdultCategory(it)).isTrue() }
    }

    @Test
    fun `ordinary categories are not`() {
        listOf(
            "Filmes | Legendados",
            "Filmes | Comédia Romântica",
            "Series | Kids",
            "Series | Adolescentes",
            "Filmes | 1980",
            "Stand Up Commedy",
            "",
        ).forEach { assertWithMessage(it).that(AdultContent.isAdultCategory(it)).isFalse() }
        assertThat(AdultContent.isAdultCategory(null)).isFalse()
    }

    @Test
    fun `adult titles are recognised by bracketed tags and adult prefixes`() {
        listOf(
            "XXX Thisgirlsucks Jooni KimForeveraloned [Adulto]",
            "Some Scene [XXX]",
            "Adulto: Elite Devassa",
            "ADULTOS: Outro",
            "XXX Wtf Pass Meet One Night Stand",
            "Filme (+18)",
            "Filme [18+]",
        ).forEach { assertWithMessage(it).that(AdultContent.isAdultTitle(it)).isTrue() }
    }

    @Test
    fun `mainstream titles with adult-looking words are not`() {
        listOf(
            "xXx: Reativado",
            "XXx: Reativado",
            "XXX: Reativado",
            "MaXXXine",
            "Rainha XXX [L]",
            "Vida Adulta 4K",
            "Adultos",
            "The Adults [L]",
            "A Arte de Ser Adulto (2020)",
            "Whindersson Nunes: Adulto",
            "Robot Chicken Adult Swim Special [L]",
            "Pornhub: Sexo Bilionário",
            "O País da Pornochanchada",
        ).forEach { assertWithMessage(it).that(AdultContent.isAdultTitle(it)).isFalse() }
    }

    @Test
    fun `a title is adult when either it or its category says so`() {
        assertThat(AdultContent.isAdult("Matou a Família e Foi ao Cinema (1991)", "Filmes | [XXX] Pornochanchada")).isTrue()
        assertThat(AdultContent.isAdult("Elite Devassa [XXX]", "Filmes | Drama")).isTrue()
        assertThat(AdultContent.isAdult("Vida Adulta", "Filmes | Comedia")).isFalse()
    }
}
