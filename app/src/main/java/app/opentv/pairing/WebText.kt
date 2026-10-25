/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import app.opentv.core.AppSettings
import app.opentv.core.LocaleUtils
import java.util.Locale

/**
 * The strings of one web page, in the language picked for the browser that asked for it.
 *
 * They are ordinary Android string resources, so a translation of the page is just the usual
 * `values-xx/strings.xml`, and a language with a gap falls back to English like everywhere else in
 * the app. What differs from the UI is the lookup: it runs on a socket thread for a request from
 * another device, so it asks for a [Resources] in the *request's* language instead of using the
 * app's own (see [LocaleUtils.contextFor] for why that must not go through the process default).
 */
class WebText(private val resources: Resources, val tag: String) {

    /** Languages written right to left; the page mirrors its layout for these. */
    val rtl: Boolean get() = tag.substringBefore('-').lowercase() in RTL_LANGUAGES

    fun get(@StringRes id: Int): String = resources.getString(id)

    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    /** The string made safe to drop into HTML text or a double-quoted attribute. */
    fun html(@StringRes id: Int): String = escape(get(id))

    companion object {
        private val RTL_LANGUAGES = setOf("ar", "fa", "he", "ur")

        fun forRequest(base: Context, acceptLanguage: String?): WebText {
            // The TV's language only matters when the browser asks for none we have: an explicit
            // choice in the app first, then whatever the device is set to.
            val tvTags = listOf(AppSettings.savedLanguageTag(base), Locale.getDefault().toLanguageTag())
                .filter { it.isNotBlank() }
            val tag = WebLocale.pick(acceptLanguage, LocaleUtils.languages.map { it.first }, tvTags)
            val localized = LocaleUtils.contextFor(base, Locale.forLanguageTag(tag))
            return WebText(localized.resources, tag)
        }

        fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
