/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.LocaleUtils
import app.opentv.pairing.PairingPage
import app.opentv.pairing.WebText
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page the phone sees after scanning the QR code. It takes a six-digit code and IPTV credentials,
 * so besides being in the phone's language it must keep escaping everything it interpolates.
 */
// Same Robolectric setup as the other Android tests: no SDK 35 image, and a plain Application
// so OpenTvApp does not schedule WorkManager.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairingPageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(acceptLanguage: String?) = WebText.forRequest(context, acceptLanguage)

    private fun english(id: Int): String =
        LocaleUtils.contextFor(context, Locale.ENGLISH).getString(id)

    @Test
    fun `a Brazilian phone gets the form in Portuguese`() {
        val html = PairingPage.form("tok", null, text("pt-BR,pt;q=0.9"))

        assertThat(html).contains("lang=\"pt\"")
        assertThat(html).doesNotContain(english(R.string.pairpage_sub))
        assertThat(html).doesNotContain(english(R.string.pairpage_send))
        // The form still posts the token and the code field is there for the TV's six digits.
        assertThat(html).contains("<input type=\"hidden\" name=\"t\" value=\"tok\">")
        assertThat(html).contains("name=\"code\"")
    }

    @Test
    fun `the label and hint the script swaps carry both texts`() {
        val en = text("en")
        val html = PairingPage.form("tok", null, en)

        assertThat(html).contains("data-xtream=\"${english(R.string.onboarding_server_address)}\"")
        assertThat(html).contains("data-m3u=\"${english(R.string.onboarding_playlist_url)}\"")
        assertThat(html).contains("data-m3u=\"${english(R.string.onboarding_playlist_help)}\"")
    }

    @Test
    fun `an error message is escaped, not injected as markup`() {
        val html = PairingPage.form("tok", "<script>alert(1)</script> & \"x\"", text("en"))

        assertThat(html).doesNotContain("<script>alert(1)")
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;")
    }

    @Test
    fun `a token with markup in it cannot break out of its attribute`() {
        val html = PairingPage.form("a\"><b>", null, text("en"))

        assertThat(html).doesNotContain("value=\"a\"><b>")
        assertThat(html).contains("value=\"a&quot;&gt;&lt;b&gt;\"")
    }

    @Test
    fun `the result page is localised and escaped too`() {
        val html = PairingPage.message("A & B", "<i>done</i>", text("de"))

        assertThat(html).contains("lang=\"de\"")
        assertThat(html).contains("<h1>A &amp; B</h1>")
        assertThat(html).contains("&lt;i&gt;done&lt;/i&gt;")
    }

    @Test
    fun `right-to-left languages mirror the page`() {
        assertThat(PairingPage.form("tok", null, text("ar"))).contains("dir=\"rtl\"")
        assertThat(PairingPage.form("tok", null, text("fa"))).contains("dir=\"rtl\"")
        assertThat(PairingPage.form("tok", null, text("en"))).contains("dir=\"ltr\"")
    }

    @Test
    fun `every language the app ships renders a well-formed form`() {
        for ((tag, _) in LocaleUtils.languages) {
            val t = text(tag)
            val html = PairingPage.form("tok", t.get(R.string.pairpage_err_code, 3), t)

            assertThat(html).contains("lang=\"$tag\"")
            assertThat(html.split("</script>")).hasSize(2)
            assertThat(html).doesNotContain("\${")
            // The attempts-left message must have had its count filled in, in every translation.
            assertThat(t.get(R.string.pairpage_err_code, 3)).doesNotContain("%1")
        }
    }
}
