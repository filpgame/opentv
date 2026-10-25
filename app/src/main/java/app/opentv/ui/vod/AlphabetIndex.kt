/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The letters of the A–Z jump bar, and the pure functions behind it: which letter a title files
 * under, the order a category grid is sorted in, and where each letter starts in that grid.
 *
 * A title files under the first character of its *displayed* title, folded: `Órfãos` is O and
 * `Êxtase` is E, because that is the letter a person looks for. Digits, symbols and other scripts
 * (`+ Velozes + Furiosos`, `[XXX] …`, `007`) go under `#`. A leading symbol is not skipped to find
 * a letter behind it: the bucket has to match the first character on the poster, or a jump to V
 * lands on a title that visibly starts with `+`.
 */
internal object TitleAlphabet {
    /** The bar, top to bottom. Bucket `n` is `LABELS[n]`. */
    const val LABELS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val BUCKETS = 27

    /** The `#` bucket: digits, symbols, other scripts and empty titles. */
    const val OTHER = 0

    /**
     * [title] decomposed (NFKD, so fullwidth letters and ligatures fold too), stripped of accents
     * and lower-cased: the key both the sort and the bucketing read. Plain `String` comparison on
     * it rather than a `Collator`: a collator is slower per comparison over a 10,000-title category,
     * and orders punctuation by locale rules nobody can predict from the screen.
     */
    fun fold(title: String): String {
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            // Accents, and invisible format characters: providers ship titles that open with a
            // left-to-right mark (U+200E), which filed `‎Um Grande Despertar` under # on the car.
            when (c.category) {
                CharCategory.NON_SPACING_MARK, CharCategory.FORMAT, CharCategory.CONTROL -> continue
                else -> Unit
            }
            when (val lower = c.lowercaseChar()) {
                // Latin letters NFKD leaves whole. Without these, Ørsted and Łódź would file under #.
                'æ' -> out.append("ae")
                'œ' -> out.append("oe")
                'ø' -> out.append('o')
                'ł' -> out.append('l')
                'đ', 'ð' -> out.append('d')
                'þ' -> out.append("th")
                'ß' -> out.append("ss")
                'ı' -> out.append('i')
                else -> out.append(lower)
            }
        }
        return out.toString()
    }

    /** Bucket of an already-[fold]ed title. */
    fun bucketOfFolded(folded: String): Int {
        val first = folded.firstOrNull() ?: return OTHER
        return if (first in 'a'..'z') 1 + (first - 'a') else OTHER
    }

    fun bucketOf(title: String): Int = bucketOfFolded(fold(title))
}

/**
 * [this] sorted for an A–Z grid: `#` titles first, then by folded title. Stable, so titles that fold
 * to the same key keep the order they came in (newest first, from the DAO).
 *
 * `#` is forced first instead of left to code-point order because that order scatters it: `+` and
 * digits sort before `a`, but `{`, `~` and every non-Latin script sort after `z`, and the bar needs
 * each letter in one run.
 *
 * [title] runs once per item (keys are computed up front, not in the comparator): it is
 * [app.opentv.data.parser.displayTitle], a handful of regexes, and a comparator would call it
 * n log n times. Even once per item it is most of the cost (about 0.6 s for 11,000 films on the
 * car head unit, on one thread), so the keys are computed in chunks across the default dispatcher's
 * threads; the chunks are small so a fast core takes over from a slow one.
 */
internal suspend fun <T> List<T>.sortedByTitle(title: (T) -> String): List<T> {
    if (size < 2) return this
    val items = this
    val keys = arrayOfNulls<String>(size)
    coroutineScope {
        for (start in indices step SORT_KEY_CHUNK) {
            launch(Dispatchers.Default) {
                for (i in start until minOf(start + SORT_KEY_CHUNK, size)) keys[i] = TitleAlphabet.fold(title(items[i]))
            }
        }
    }
    val buckets = IntArray(size) { TitleAlphabet.bucketOfFolded(keys[it]!!) }
    val order = indices.sortedWith { a, b ->
        val byBucket = buckets[a].compareTo(buckets[b])
        if (byBucket != 0) byBucket else keys[a]!!.compareTo(keys[b]!!)
    }
    return order.map { items[it] }
}

private const val SORT_KEY_CHUNK = 512

/**
 * Where each letter starts in the list a grid displays.
 *
 * Built from the displayed list itself, not from the category: when the list is filtered (quality or
 * language chips) the index is rebuilt over what is left, and a letter with no titles left simply
 * goes dark.
 *
 * The list must run in bar order, letter by letter, which [sortedByTitle] guarantees and filtering
 * keeps. That lets each letter's start be found by binary search, a few hundred title cleans
 * instead of one per item: a linear pass cost 0.6 s for 11,000 films on the car head unit, time in
 * which the bar sat empty. The order inside a letter does not matter.
 */
internal class AlphabetIndex private constructor(
    private val firstIndices: IntArray,
    val itemCount: Int,
) {
    /** Index of the first item under [bucket], or -1 when there is none. */
    fun firstIndexOf(bucket: Int): Int = firstIndices[bucket]

    /** [firstIndexOf] by label (`'#'`, `'A'`…`'Z'`). */
    fun firstIndexOf(label: Char): Int = TitleAlphabet.LABELS.indexOf(label).let { if (it < 0) -1 else firstIndices[it] }

    fun hasTitles(bucket: Int): Boolean = firstIndices[bucket] >= 0

    /**
     * The bucket item [itemIndex] files under: the last letter starting at or before it. Clamped, so
     * a stale index during a refresh is harmless.
     */
    fun bucketAt(itemIndex: Int): Int {
        if (itemCount == 0) return TitleAlphabet.OTHER
        val i = itemIndex.coerceIn(0, itemCount - 1)
        var bucket = TitleAlphabet.OTHER
        for (b in 0 until TitleAlphabet.BUCKETS) {
            val start = firstIndices[b]
            if (start in 0..i) bucket = b
        }
        return bucket
    }

    /**
     * The letter a finger resting on [bucket] should go to: [bucket] itself if it has titles, else
     * the next one down that does, else the nearest one above. Scrubbing over an empty letter then
     * keeps moving the grid the way the finger moves. -1 only for an empty list.
     */
    fun nearestWithTitles(bucket: Int): Int {
        for (b in bucket until TitleAlphabet.BUCKETS) if (firstIndices[b] >= 0) return b
        for (b in bucket - 1 downTo 0) if (firstIndices[b] >= 0) return b
        return -1
    }

    companion object {
        fun <T> of(items: List<T>, title: (T) -> String): AlphabetIndex {
            val first = IntArray(TitleAlphabet.BUCKETS) { -1 }
            val probed = HashMap<Int, Int>()
            fun bucketAt(i: Int) = probed.getOrPut(i) { TitleAlphabet.bucketOf(title(items[i])) }
            // Lower bound of each letter in turn; each search starts where the previous one ended.
            var from = 0
            for (b in 0 until TitleAlphabet.BUCKETS) {
                var lo = from
                var hi = items.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (bucketAt(mid) < b) lo = mid + 1 else hi = mid
                }
                if (lo < items.size && bucketAt(lo) == b) first[b] = lo
                from = lo
            }
            return AlphabetIndex(first, items.size)
        }
    }
}
