/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

/**
 * Tells apart the versions providers list of one title: dubbed or subtitled, and in which picture
 * quality. A Brazilian playlist ships `Junk` (dubbed), `Junk [L]` (legendado) and often
 * `Junk 4K [DV][HDR]` as three separate entries in three categories; this is what lets the app show
 * them as one poster and offer the choice on the detail page.
 *
 * Everything here works from the provider's name and category, the only metadata a playlist has, and
 * the results are stored on each row at import (see [stamp]), so folding a 40,000-title catalogue is
 * a lookup by [Stamp.variantKey] rather than a regex per title on every screen.
 */
object VodVersions {

    /** [Stamp.qualityRank] from which a version counts as 4K, as ranked by [ChannelNameNormalizer]. */
    const val UHD_RANK = 400

    /** A bracketed or parenthesised tag: `[L]`, `[DV]`, `(Legendado)`, and also `(2021)` and `[REC]`. */
    private val TAG = Regex("""\[([^\[\]]*)]|\(([^()]*)\)""")

    /**
     * The tags that mark the subtitled version. `[L]` is the Brazilian convention (11,000 titles on
     * one playlist); an untagged title is that list's dubbed one.
     */
    private val SUBTITLED_TAGS = setOf(
        "l", "leg", "legenda", "legendado", "legendada", "legendados", "legendadas", "sub", "subs", "subtitled",
    )

    private val DUBBED_TAGS = setOf("dub", "dublado", "dublada", "dual", "dual audio", "dublagem nao oficial")

    /** Picture-quality tags and markers of how the copy was made, all of one film. */
    private val PICTURE_TAGS = setOf(
        "4k", "8k", "uhd", "fhd", "hd", "sd", "2160p", "1080p", "720p",
        "hdr", "hdr10", "sdr", "dv", "dolby vision", "hybrid", "hevc", "cinema",
    )

    private val NATIONAL_TAGS = setOf("nacional", "nacionais")

    /**
     * Everything that says *which copy* a row is rather than *what* it is. Only these are stripped
     * from the displayed title and the fold key; any other bracket stays, because `[REC]` is a film,
     * and `[Extended Edition]` or `[Corte do Diretor]` are different cuts that must stay apart.
     */
    private val VERSION_TAGS = SUBTITLED_TAGS + DUBBED_TAGS + PICTURE_TAGS + NATIONAL_TAGS

    /** Words of a category name that mark its titles as subtitled or as national productions. */
    private val SUBTITLED_CATEGORY_WORDS = setOf("legendado", "legendados", "legendada", "legendadas", "subtitled")
    private val NATIONAL_CATEGORY_WORDS =
        setOf("nacional", "nacionais", "brasileiro", "brasileiros", "brasileira", "brasileiras")

    /** A standalone 4-digit release year (19xx/20xx) as it appears inside a VOD title. */
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")

    /**
     * `&` is read as the word "e" before folding, and "and" as "e" after, so `Velozes E Furiosos 9 4K`
     * meets `Velozes & Furiosos 9`. Not the other way round (dropping every "e"): `É Amor?` is not
     * `Amor`, and the accent is already gone by then.
     */
    private val AMPERSAND = Regex("""\s*&\s*""")

    /** What import computes and stores for one movie or show. */
    data class Stamp(
        val searchKey: String,
        val variantKey: String,
        val subtitled: Boolean,
        val qualityRank: Int,
        val national: Boolean,
        val adult: Boolean,
    )

    fun stamp(name: String, year: Int?, categoryName: String?): Stamp {
        val title = VodTitleCleaner.clean(name)
        val quality = ChannelNameNormalizer.normalize(name).qualityRank
            .takeIf { it > 0 }
            ?: categoryName?.let { ChannelNameNormalizer.normalize(it).qualityRank }
            ?: 0
        return Stamp(
            // The cleaned title first, so "starts with" ranks on the real title even when the raw name
            // opens with a language tag; the raw name after it, so tags like 4K are still searchable.
            searchKey = SearchText.keyOf(title, name),
            variantKey = variantKeyOf(title, year),
            subtitled = isSubtitled(name, categoryName),
            qualityRank = quality,
            national = isNational(name, categoryName),
            adult = AdultContent.isAdult(name, categoryName),
        )
    }

    /**
     * The key shared by every version of one title: the displayed title folded to letters and digits,
     * plus the release year. The year stays in on purpose, because `Pinóquio` and `Pinóquio (2022)`
     * are different films; the cost is that the few shows listed with a year on one version and
     * without on the other stay apart.
     */
    fun variantKeyOf(cleanTitle: String, year: Int?): String {
        val embedded = YEAR.find(cleanTitle)?.value?.toIntOrNull()
        val words = SearchText.normalize(AMPERSAND.replace(YEAR.replace(cleanTitle, " "), " e "))
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { if (it == "and") "e" else it }
        // A title that is only a year (`2012`) keeps it as its name.
        val base = words.joinToString("").ifEmpty { SearchText.normalize(cleanTitle).replace(" ", "") }
        return base + "|" + ((year ?: embedded)?.toString() ?: "")
    }

    /** [name] with every version tag removed; used by [VodTitleCleaner] for the displayed title. */
    fun stripVersionTags(name: String): String =
        TAG.replace(name) { match -> if (tagOf(match) in VERSION_TAGS) " " else match.value }

    fun isSubtitled(name: String, categoryName: String?): Boolean =
        tagsOf(name).any { it in SUBTITLED_TAGS } ||
            (categoryName != null && (
                tagsOf(categoryName).any { it in SUBTITLED_TAGS } ||
                    SearchText.normalize(categoryName).split(' ').any { it in SUBTITLED_CATEGORY_WORDS }
                ))

    fun isNational(name: String, categoryName: String?): Boolean =
        tagsOf(name).any { it in NATIONAL_TAGS } ||
            (categoryName != null && SearchText.normalize(categoryName).split(' ').any { it in NATIONAL_CATEGORY_WORDS })

    /**
     * What the name says about this copy besides its language, as the provider wrote it, for the
     * version picker: `Velozes & Furiosos 10 4K [Hybrid]` → `4K`, `Hybrid`. A copy that is 4K only by
     * its category ([qualityRank]) still gets its `4K`.
     */
    fun pictureLabels(name: String, qualityRank: Int = 0): List<String> {
        val labels = LinkedHashMap<String, String>()
        fun add(label: String) { labels.putIfAbsent(label.lowercase(), label) }

        // Bare words first (`4K`), then bracketed tags (`[DV][HDR]`): the order people say it in.
        val tagged = ArrayList<String>()
        val untagged = TAG.replace(ChannelNameNormalizer.foldSuperscripts(name)) { match ->
            val tag = tagOf(match)
            if (tag in PICTURE_TAGS || tag in DUBBED_TAGS) {
                tagged += match.groupValues[1].ifEmpty { match.groupValues[2] }.trim().display()
            }
            " "
        }
        for (word in untagged.split(' ')) {
            val bare = word.trim('.', ',', ':', ';', '|', '-')
            if (bare.isEmpty()) continue
            if (ChannelNameNormalizer.qualityRankOfToken(bare) != null || ChannelNameNormalizer.isStreamMarker(bare)) {
                add(bare.uppercase())
            }
        }
        tagged.forEach(::add)
        val says4k = labels.keys.any { (ChannelNameNormalizer.qualityRankOfToken(it) ?: 0) >= UHD_RANK }
        return if (qualityRank >= UHD_RANK && !says4k) listOf("4K") + labels.values else labels.values.toList()
    }

    /** Short tags in capitals (`DV`, `HDR`), longer ones as written (`Hybrid`). */
    private fun String.display(): String = if (length <= 4) uppercase() else this

    private fun tagOf(match: MatchResult): String =
        SearchText.normalize(match.groupValues[1].ifEmpty { match.groupValues[2] })

    private fun tagsOf(text: String): Sequence<String> = TAG.findAll(text).map { tagOf(it) }
}
