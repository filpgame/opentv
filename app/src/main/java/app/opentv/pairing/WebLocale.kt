/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

/**
 * Chooses which of the app's languages a page served to a phone or laptop should be in.
 *
 * The page is read in the *browser*, so the browser's `Accept-Language` decides first. Only if none
 * of what it asks for is a language the app ships does the TV's own language apply, and only then
 * English. Pure and Android-free so the matching rules can be tested without a device.
 */
object WebLocale {

    const val DEFAULT = "en"

    /** Hostile or merely enormous headers are cut off; no real browser sends this many. */
    private const val MAX_TAGS = 20

    private val TAG_SHAPE = Regex("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$")

    /** Legacy codes browsers and JVMs still send, mapped to the tag the app's resources use. */
    private val ALIASES = mapOf("no" to "nb", "nn" to "nb", "in" to "id")

    /**
     * @param acceptLanguage the raw header, e.g. `pt-BR,pt;q=0.9,en;q=0.8`; null or blank if absent
     * @param supported the language tags the app has translations for ([app.opentv.core.LocaleUtils.languages])
     * @param fallbackTags tried, in order, when nothing in the header matches
     * @return one of [supported], or [DEFAULT]
     */
    fun pick(acceptLanguage: String?, supported: List<String>, fallbackTags: List<String> = emptyList()): String {
        for (tag in parseAcceptLanguage(acceptLanguage) + fallbackTags) {
            match(tag, supported)?.let { return it }
        }
        return DEFAULT
    }

    /** The header's language tags, most preferred first. Drops `*`, `q=0` and anything malformed. */
    internal fun parseAcceptLanguage(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(',')
            .take(MAX_TAGS)
            .mapNotNull { part ->
                val pieces = part.split(';')
                val tag = pieces[0].trim().replace('_', '-')
                if (!TAG_SHAPE.matches(tag)) return@mapNotNull null
                val q = pieces.drop(1)
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("q=", ignoreCase = true) }
                    ?.substring(2)?.toDoubleOrNull()
                    ?: 1.0
                if (q <= 0.0) null else tag to q
            }
            // sortedByDescending is stable, so equal weights keep the order the browser listed them in.
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * An exact tag match first (`pt-PT` must reach European Portuguese), then the bare language
     * (`pt-BR`, `pt-AO` and plain `pt` all land on the app's Brazilian `pt`, as its language picker
     * does; `zh-TW` lands on the one Chinese translation there is).
     */
    private fun match(rawTag: String, supported: List<String>): String? {
        val tag = rawTag.replace('_', '-')
        supported.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        val language = tag.substringBefore('-').lowercase().let { ALIASES[it] ?: it }
        return supported.firstOrNull { it.equals(language, ignoreCase = true) }
    }
}
