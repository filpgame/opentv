/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.pairing.WebLocale
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which of the app's languages a phone's or laptop's browser gets the channel manager page in.
 * Pure string matching, so no Android is needed.
 */
class WebLocaleTest {

    // A cross-section of the real list: bare pt is Brazilian, pt-PT is its own translation, and
    // there is exactly one Chinese and one Norwegian.
    private val supported = listOf("en", "es", "pt", "pt-PT", "nb", "zh", "ar")

    private fun pick(header: String?, vararg fallback: String) =
        WebLocale.pick(header, supported, fallback.toList())

    @Test
    fun `a regional tag lands on the bare language`() {
        assertThat(pick("pt-BR,pt;q=0.9,en;q=0.8")).isEqualTo("pt")
        assertThat(pick("es-MX")).isEqualTo("es")
    }

    @Test
    fun `European Portuguese keeps its own translation`() {
        assertThat(pick("pt-PT,pt;q=0.9")).isEqualTo("pt-PT")
    }

    @Test
    fun `any other Portuguese region gets the Brazilian translation`() {
        assertThat(pick("pt-AO")).isEqualTo("pt")
    }

    @Test
    fun `the legacy Norwegian codes reach Bokmal`() {
        assertThat(pick("no")).isEqualTo("nb")
        assertThat(pick("nn-NO")).isEqualTo("nb")
    }

    @Test
    fun `every Chinese variant lands on the one Chinese translation`() {
        assertThat(pick("zh-TW")).isEqualTo("zh")
        assertThat(pick("zh-Hant-HK")).isEqualTo("zh")
    }

    @Test
    fun `quality weights decide the order, not the position`() {
        assertThat(pick("es;q=0.4,pt;q=0.9")).isEqualTo("pt")
    }

    @Test
    fun `equal weights keep the order the browser listed them in`() {
        assertThat(pick("es,pt")).isEqualTo("es")
    }

    @Test
    fun `an unsupported language is skipped in favour of a later supported one`() {
        assertThat(pick("lv,es;q=0.5")).isEqualTo("es")
    }

    @Test
    fun `a language the browser refuses outright is not used`() {
        assertThat(pick("es;q=0,pt")).isEqualTo("pt")
    }

    @Test
    fun `matching ignores case and accepts underscores`() {
        assertThat(pick("PT-pt")).isEqualTo("pt-PT")
        assertThat(pick("pt_BR")).isEqualTo("pt")
        assertThat(pick("ES")).isEqualTo("es")
    }

    @Test
    fun `when the browser asks for nothing we have, the TV's language is next`() {
        assertThat(pick("lv", "pt-BR")).isEqualTo("pt")
        assertThat(pick("lv", "xx", "es-ES")).isEqualTo("es")
    }

    @Test
    fun `English is the last resort`() {
        assertThat(pick("lv")).isEqualTo("en")
        assertThat(pick("lv", "xx")).isEqualTo("en")
    }

    @Test
    fun `missing, wildcard and garbage headers fall back instead of throwing`() {
        assertThat(pick(null)).isEqualTo("en")
        assertThat(pick("")).isEqualTo("en")
        assertThat(pick("*")).isEqualTo("en")
        assertThat(pick(";;;,<script>,,q=1", "pt")).isEqualTo("pt")
        assertThat(pick("es;q=banana")).isEqualTo("es")
    }

    @Test
    fun `a header with an absurd number of entries is cut off`() {
        val header = (1..100).joinToString(",") { "xx$it" } + ",es"
        // "xx1".. are not valid tags (a digit in the language subtag) and es is entry 101, past the cap.
        assertThat(pick(header)).isEqualTo("en")
    }
}
