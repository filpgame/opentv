/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.text.Normalizer

/**
 * Folds titles and what the user types to one plain form, so search ignores accents, case and
 * punctuation: `Pinóquio` and `pinoquio`, `Homem-Aranha` and `homem aranha` become the same words.
 *
 * SQLite cannot do this itself. Its `LIKE` folds ASCII case only, and Android's SQLite has no ICU
 * or unaccent function to call, so `acao` never found `Ação`. The folded form is therefore computed
 * here, stored next to each title at import ([keyOf]), and the query is folded the same way
 * ([tokens]) before it reaches SQL.
 */
object SearchText {

    /** Letters that NFD leaves whole, spelled the way someone without the key would type them. */
    private val SPELLED_OUT = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l",
        'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i",
    )

    /** Dropped rather than turned into a space, so `Swim's` folds to `swims`, not `swim s`. */
    private val APOSTROPHES = setOf('\'', '’', '‘', '`', '´')

    /**
     * Lowercase letters and digits with accents removed, every other run of characters collapsed to
     * one space: `Velozes & Furiosos 10 4K [Hybrid]` → `velozes furiosos 10 4k hybrid`.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(ChannelNameNormalizer.foldSuperscripts(text), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var gap = false
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c in APOSTROPHES -> Unit
                c.isLetterOrDigit() -> {
                    if (gap && out.isNotEmpty()) out.append(' ')
                    gap = false
                    val lower = c.lowercaseChar()
                    val spelled = SPELLED_OUT[lower]
                    if (spelled != null) out.append(spelled) else out.append(lower)
                }
                else -> gap = true
            }
        }
        return out.toString()
    }

    /**
     * The stored `searchKey`: the first text normalised, then any words the others add, with a space
     * at each end. The padding is what lets one `LIKE '% word%'` find a word at the very start as well
     * as in the middle, which the ranking in [app.opentv.data.db.titleSearchQuery] relies on. Only new
     * words are appended, so `Junk [L]` keys as ` junk l `, not ` junk junk l `, and the length
     * tie-break in that ranking still prefers the shorter title.
     */
    fun keyOf(vararg texts: String): String {
        val first = normalize(texts.firstOrNull().orEmpty())
        val seen = first.split(' ').toHashSet()
        val added = texts.drop(1).flatMap { normalize(it).split(' ') }.filter { it.isNotEmpty() && seen.add(it) }
        return (listOf(first) + added).filter { it.isNotEmpty() }.joinToString(" ", prefix = " ", postfix = " ")
    }

    /** The words of a query, folded like [keyOf], without repeats. Empty when nothing searchable is left. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()
}
