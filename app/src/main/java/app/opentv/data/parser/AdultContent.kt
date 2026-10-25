/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

/**
 * Spots adult movies, shows and VOD categories, so Movies and Shows can keep them out of sight until
 * the parental-control unlock. On a real Brazilian playlist a quarter of all films are adult, and
 * without this they fill Recommended, Recently added and search results.
 *
 * Conservative on purpose: a mainstream film wrongly hidden is a confusing gap, so this only trusts
 * signals a provider puts there to label adult content.
 *  - **Category names** are labels, so a whole word is enough: `Filmes | [XXX] Adultos`, `+18`,
 *    `Pornochanchada`, `Hentai`.
 *  - **Titles** are not labels. `xXx: Reativado`, `MaXXXine`, `Vida Adulta` and the series
 *    `Adultos` are ordinary titles, so a title only counts when the word is a bracketed tag
 *    (`[XXX]`, `[Adulto]`) or a prefix providers stamp on adult entries (`Adulto: …`, `XXX …`).
 *
 * The category rule does most of the work: on the playlist this was written against, every title
 * with an adult tag sat in an adult category already. The title rule catches providers that file
 * them elsewhere.
 */
object AdultContent {

    /** Whole words that mark a category as adult, after [SearchText.normalize]. */
    private val CATEGORY_WORDS = setOf("xxx", "adult", "adults", "adulto", "adultos", "adulta", "adultas", "hentai")

    /** `+18` / `18+`, which the normaliser would reduce to a bare (and innocent) `18`. */
    private val EIGHTEEN_PLUS = Regex("""\+\s?18\b|\b18\s?\+""")

    /** An adult marker inside brackets or parentheses anywhere in a title: `[XXX]`, `(Adulto)`, `[+18]`. */
    private val TITLE_TAG = Regex(
        """[\[(]\s*(?:xxx|adult[oa]?s?|porn\w*|\+\s?18|18\s?\+)\s*[])]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `Adulto: …` (any case) or `XXX …` (capitals only) at the start of a title. Case matters for XXX
     * because the Vin Diesel films are listed as `xXx`, and the colon in `xXx: Reativado` keeps it out
     * of the prefix rule even in an all-capitals list.
     */
    private val TITLE_PREFIX = Regex("""^\s*(?:(?i:adult[oa]?s?)\s*:|XXX\s)""")

    fun isAdultCategory(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (EIGHTEEN_PLUS.containsMatchIn(name)) return true
        return SearchText.normalize(name).split(' ').any { it in CATEGORY_WORDS || it.startsWith("porn") }
    }

    fun isAdultTitle(name: String): Boolean =
        (('[' in name || '(' in name) && TITLE_TAG.containsMatchIn(name)) || TITLE_PREFIX.containsMatchIn(name)

    /** A title is hidden as adult when it says so itself or sits in an adult category. */
    fun isAdult(title: String, categoryName: String?): Boolean =
        isAdultTitle(title) || isAdultCategory(categoryName)
}
