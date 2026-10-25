/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.LocaleUtils
import app.opentv.pairing.ManagerPage
import app.opentv.pairing.WebText
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The channel manager page is rendered from the same string resources as the TV's UI, in the
 * language of whoever asked for it. These render it through [WebText] the way [app.opentv.pairing.ManagerServer]
 * does and check what makes it into the HTML — the script itself runs in the user's browser.
 */
// Same Robolectric setup as the other Room/Android tests: no SDK 35 image, and a plain Application
// so OpenTvApp does not schedule WorkManager.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManagerPageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun render(acceptLanguage: String?, token: String = "abc123"): String =
        ManagerPage.page(token, WebText.forRequest(context, acceptLanguage))

    private fun english(id: Int): String =
        LocaleUtils.contextFor(context, Locale.ENGLISH).getString(id)

    private fun inLanguage(tag: String, id: Int): String =
        LocaleUtils.contextFor(context, Locale.forLanguageTag(tag)).getString(id)

    @Test
    fun `a Brazilian browser gets the page in Portuguese`() {
        val html = render("pt-BR,pt;q=0.9,en;q=0.8")

        assertThat(html).contains("lang=\"pt\"")
        assertThat(html).contains("dir=\"ltr\"")
        // The new strings, and an existing TV string the page reuses, both come through translated.
        assertThat(html).doesNotContain(english(R.string.webpage_prov_hint))
        assertThat(html).doesNotContain("<summary>${english(R.string.guide_add_provider_button)}</summary>")
        assertThat(html).contains(WebText.escape(inLanguage("pt", R.string.guide_add_provider_button)))
    }

    @Test
    fun `an unsupported browser language gets English`() {
        val html = render("lv")

        assertThat(html).contains("lang=\"en\"")
        assertThat(html).contains(english(R.string.webpage_title))
    }

    @Test
    fun `right-to-left languages mirror the layout`() {
        assertThat(render("ar")).contains("dir=\"rtl\"")
        assertThat(render("fa-IR")).contains("dir=\"rtl\"")
        assertThat(render("en-GB")).contains("dir=\"ltr\"")
    }

    @Test
    fun `the token is embedded and no template placeholder leaks into the page`() {
        val html = render("es", token = "tok42")

        assertThat(html).contains("var EMBEDDED_TOKEN = \"tok42\";")
        assertThat(html).doesNotContain("\${")
        assertThat(html).doesNotContain("\$token")
    }

    @Test
    fun `count placeholders are left for the script to fill`() {
        val html = render("en")

        assertThat(html).contains("channels loaded.")
        assertThat(html).contains("%1\$d")
    }

    @Test
    fun `every language the app ships renders a well-formed page`() {
        for ((tag, _) in LocaleUtils.languages) {
            val html = render(tag)

            assertThat(html).contains("lang=\"$tag\"")
            // A string that closed the script block early would leave a second </script>.
            assertThat(html.split("</script>")).hasSize(2)
            assertThat(html).doesNotContain("\${")
        }
    }

    @Test
    fun `a string cannot close the script block it is embedded in`() {
        val escaped = ManagerPage.escapeForScript("{\"a\":\"</script><!--\u2028\"}")

        assertThat(escaped).doesNotContain("<")
        assertThat(escaped).doesNotContain("\u2028")
        assertThat(escaped).contains("\\u003c/script>")
    }
}
