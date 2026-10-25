/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.compose.runtime.Immutable
import app.opentv.data.model.VodTitle
import app.opentv.data.parser.VodVersions

/**
 * One title with every version the provider lists of it — dubbed, subtitled, 4K — shown as one poster.
 * The VOD analogue of a channel's quality group, extended to language because a Brazilian playlist
 * lists most films twice (`Junk`, `Junk [L]`) and often a third time in 4K.
 *
 * A plain class rather than a data class: grids hold thousands of these, and data-class equality
 * would deep-compare every version list whenever a StateFlow checks for a change.
 */
@Immutable
class VariantGroup<T : VodTitle>(
    /** The version the poster shows and the detail page opens on; always one from the list being shown. */
    val primary: T,
    /** Every version, including [primary]: dubbed before subtitled, then by picture quality. */
    val versions: List<T>,
) {
    /** A subtitled version exists (the LEG badge, the "Legendado" filter). */
    val subtitled: Boolean = versions.any { it.subtitled }

    /** A version that is not the subtitled one exists (the "Dublado" filter). */
    val dubbed: Boolean = versions.any { !it.subtitled }

    val uhd: Boolean = versions.any { it.qualityRank >= VodVersions.UHD_RANK }

    val national: Boolean = versions.any { it.national }

    /**
     * Whether to badge the language at all. On a list that never tags subtitles, "dubbed" just means
     * "untagged", so DUB is only shown next to a LEG, where there really is a choice.
     */
    val offersLanguageChoice: Boolean get() = subtitled && dubbed
}

/** Which rows are versions of one title: same source and key. An unstamped row stands alone. */
internal fun versionKeyOf(title: VodTitle): Any =
    if (title.variantKey.isEmpty()) title.id else title.sourceId to title.variantKey

/** Order of versions in a group and in the detail page's picker: dubbed, then subtitled; SD to 4K. */
internal val VERSION_ORDER: Comparator<VodTitle> =
    compareBy<VodTitle> { it.subtitled }.thenBy { it.qualityRank }.thenBy { it.name }

/**
 * The version a poster stands for, among those in the list being shown: dubbed before subtitled, then
 * anything before 4K, then the list's own order. Not 4K first: a 4K copy is often HEVC with Dolby
 * Vision, which a cheap box or a car unit may not decode, and the detail page puts it one press away.
 */
private val PRIMARY_ORDER: Comparator<VodTitle> =
    compareBy<VodTitle> { it.subtitled }.thenBy { it.qualityRank >= VodVersions.UHD_RANK }

/**
 * Folds [shown] into one [VariantGroup] per title, in the order each title first appears. [extra] are
 * other versions of those titles from outside the list (another category, say): they join a group's
 * [VariantGroup.versions] but never start a group or become its poster.
 */
fun <T : VodTitle> foldVersions(shown: List<T>, extra: List<T> = emptyList()): List<VariantGroup<T>> {
    val groups = LinkedHashMap<Any, MutableList<T>>()
    for (title in shown) groups.getOrPut(versionKeyOf(title)) { ArrayList(2) } += title
    val others = HashMap<Any, MutableList<T>>()
    for (title in extra) {
        val key = versionKeyOf(title)
        if (key in groups) others.getOrPut(key) { ArrayList(2) } += title
    }
    return groups.map { (key, members) ->
        val primary = members.minWith(PRIMARY_ORDER)
        val all = (members + others[key].orEmpty()).distinctBy { it.id }.sortedWith(VERSION_ORDER)
        VariantGroup(primary, all)
    }
}

/** Every title of an already-loaded library by version key, for [foldVersionsIn]. */
fun <T : VodTitle> indexVersions(library: List<T>): Map<Any, List<T>> = library.groupBy(::versionKeyOf)

/**
 * [foldVersions] for rows computed from a library already in memory (the home feeds' single scan),
 * taking each title's other versions from that [library] index instead of another query.
 */
fun <T : VodTitle> foldVersionsIn(shown: List<T>, library: Map<Any, List<T>>): List<VariantGroup<T>> =
    foldVersions(shown, shown.flatMap { library[versionKeyOf(it)].orEmpty() })
