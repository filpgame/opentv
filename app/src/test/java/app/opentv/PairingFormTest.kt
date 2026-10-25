/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.SourceKind
import app.opentv.pairing.PairingHttp
import app.opentv.pairing.PairingHttp.FormResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the phone's setup form turns into once it reaches the device. */
class PairingFormTest {

    private fun parse(vararg fields: Pair<String, String>) =
        PairingHttp.sourceFromForm(fields.toMap(), providerName = "My provider", playlistName = "My playlist")

    private fun source(result: FormResult) = (result as FormResult.Ok).source

    @Test
    fun `an Xtream login keeps its credentials and guide`() {
        val s = source(
            parse(
                "kind" to "xtream", "name" to " Sala ", "url" to " http://host:8080 ",
                "username" to "ana", "password" to " pa ss ", "epg" to "http://guide/x.xml",
            ),
        )

        assertThat(s.kind).isEqualTo(SourceKind.XTREAM)
        assertThat(s.name).isEqualTo("Sala")
        assertThat(s.url).isEqualTo("http://host:8080")
        assertThat(s.username).isEqualTo("ana")
        // A password may legitimately contain spaces; only the username is trimmed.
        assertThat(s.password).isEqualTo(" pa ss ")
        assertThat(s.epgUrl).isEqualTo("http://guide/x.xml")
        assertThat(s.macAddress).isNull()
    }

    @Test
    fun `a Stalker portal keeps the MAC and drops a login typed under another type`() {
        val s = source(
            parse(
                "kind" to "stalker", "url" to "http://portal/c/", "mac" to " 00:1A:79:AA:BB:CC ",
                "username" to "left-over", "password" to "left-over",
            ),
        )

        assertThat(s.kind).isEqualTo(SourceKind.STALKER)
        assertThat(s.macAddress).isEqualTo("00:1A:79:AA:BB:CC")
        assertThat(s.username).isNull()
        assertThat(s.password).isNull()
        assertThat(s.name).isEqualTo("My provider")
    }

    @Test
    fun `a playlist drops credentials and MAC and gets the playlist name`() {
        val s = source(
            parse(
                "kind" to "m3u", "url" to "http://list/x.m3u",
                "username" to "left-over", "password" to "left-over", "mac" to "00:1A:79:AA:BB:CC",
            ),
        )

        assertThat(s.kind).isEqualTo(SourceKind.M3U)
        assertThat(s.name).isEqualTo("My playlist")
        assertThat(s.username).isNull()
        assertThat(s.password).isNull()
        assertThat(s.macAddress).isNull()
    }

    @Test
    fun `an unknown or missing type is treated as Xtream`() {
        assertThat(source(parse("url" to "http://h")).kind).isEqualTo(SourceKind.XTREAM)
        assertThat(source(parse("kind" to "bogus", "url" to "http://h")).kind).isEqualTo(SourceKind.XTREAM)
    }

    @Test
    fun `a blank address is refused whatever the type`() {
        assertThat(parse("kind" to "xtream", "url" to "  ")).isEqualTo(FormResult.MissingUrl)
        assertThat(parse("kind" to "stalker", "mac" to "00:1A:79:AA:BB:CC")).isEqualTo(FormResult.MissingUrl)
    }

    @Test
    fun `a Stalker portal without a MAC is refused`() {
        assertThat(parse("kind" to "stalker", "url" to "http://portal/c/", "mac" to "  "))
            .isEqualTo(FormResult.MissingMac)
    }
}
