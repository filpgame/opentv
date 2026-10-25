/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the user's chosen UI language by wrapping a base [Context] with an overridden locale.
 *
 * Done at attachBaseContext time (rather than the newer per-app-language API) so it works the same
 * on every supported version — minSdk 23 through the latest — and on Fire OS, without pulling in
 * AppCompat. A blank tag means "follow the device", so nothing is overridden.
 */
object LocaleUtils {

    /**
     * The UI languages OpenTV ships translations for, each labelled with its own endonym. The tag is
     * both the stored language tag and the resource qualifier (values-<tag>), so adding a language is
     * just a new `values-xx/strings.xml` plus a line here.
     *
     * Bare "pt" is Brazilian: values-pt holds it so that a device set to plain "Português" (no region,
     * which Android never matches against a `-rBR` folder) gets it too. European Portuguese is the
     * "pt-PT" tag, i.e. values-pt-rPT.
     */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "ru" to "Русский",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "hi" to "हिन्दी",
        "sv" to "Svenska",
        "da" to "Dansk",
        "fi" to "Suomi",
        "nb" to "Norsk",
        "cs" to "Čeština",
        "el" to "Ελληνικά",
        "ro" to "Română",
        "hu" to "Magyar",
        "uk" to "Українська",
        "id" to "Bahasa Indonesia",
        "th" to "ไทย",
        "vi" to "Tiếng Việt",
        "bg" to "Български",
        "sk" to "Slovenčina",
        "hr" to "Hrvatski",
        "fa" to "فارسی",
    )

    fun wrap(base: Context): Context = wrap(base, AppSettings.savedLanguageTag(base))

    fun wrap(base: Context, tag: String): Context {
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return contextFor(base, locale)
    }

    /**
     * [base] with its resources in [locale], and nothing else changed. Unlike [wrap] it does not
     * touch `Locale.setDefault`: that is process-wide, so a request thread of the web manager
     * rendering a page for a phone must not be able to flip the date and number formatting of the
     * TV's own UI.
     */
    fun contextFor(base: Context, locale: Locale): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
