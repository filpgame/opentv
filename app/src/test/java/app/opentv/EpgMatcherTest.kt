/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.EpgMatcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The matcher's contract: prefer no match to a wrong match. A missing guide entry is an
 * annoyance with a manual override; a wrong one convinces the user the whole guide lies.
 */
class EpgMatcherTest {

    /** Shorthand: build an index from (epgId, displayName) pairs. */
    private fun index(vararg aliases: Pair<String, String>) =
        EpgMatcher.buildIndex(aliases.toList())

    private fun keyOf(providerName: String) =
        ChannelNameNormalizer.normalize(providerName).groupKey

    @Test
    fun `provider channel matches guide channel by normalised name`() {
        val idx = index("bbc1.uk" to "BBC One", "itv1.uk" to "ITV1")

        assertThat(idx.match(keyOf("UK| BBC ONE FHD"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK - ITV 1 HD"))).isEqualTo("itv1.uk")
    }

    @Test
    fun `the raw guide id is matchable too`() {
        // Providers that do fill in epg_channel_id often use the id form directly.
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(ChannelNameNormalizer.groupKeyOf("bbc1.uk"))).isEqualTo("bbc1.uk")
    }

    @Test
    fun `unique prefix matches are accepted`() {
        val idx = index("bbc1london.uk" to "BBC One London")

        // Provider says just "BBC One"; the only candidate is the London region — take it.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isEqualTo("bbc1london.uk")
    }

    @Test
    fun `ambiguous prefix matches are refused`() {
        val idx = index(
            "bbc1london.uk" to "BBC One London",
            "bbc1wales.uk" to "BBC One Wales",
        )

        // Two regions fit and nothing exact does. Guessing here shows the wrong regional
        // news against the channel — refuse, and let the manual override decide.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isNull()
    }

    @Test
    fun `colliding display names disable exact matching for that key`() {
        val idx = index(
            "cnn.us" to "CNN",
            "cnn.int" to "CNN",
        )

        assertThat(idx.match(keyOf("CNN"))).isNull()
    }

    @Test
    fun `unknown channels match nothing`() {
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(keyOf("UK| SOME SHOP CHANNEL"))).isNull()
    }

    @Test
    fun `keys too short to mean anything never match`() {
        val idx = index("e.uk" to "E")

        assertThat(idx.match("e")).isNull()
        // But two characters is a real channel name in Britain.
        val e4 = index("e4.uk" to "E4")
        assertThat(e4.match(keyOf("UK| E4 HD"))).isEqualTo("e4.uk")
    }

    @Test
    fun `end to end - the exact names from the reporting provider`() {
        // The names that started all this, verbatim from the screen.
        val idx = index(
            "bbc1.uk" to "BBC One",
            "bbc2.uk" to "BBC Two",
            "channel4.uk" to "Channel 4",
            "skyatlantic.uk" to "Sky Atlantic",
        )

        assertThat(idx.match(keyOf("UK| BBC ONE HD/RAW"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK| BBC TWO FHD"))).isEqualTo("bbc2.uk")
        assertThat(idx.match(keyOf("UK| CHANNEL 4 hevc"))).isEqualTo("channel4.uk")
        assertThat(idx.match(keyOf("UK| SKY ATLANTIC ᴴᴰ"))).isEqualTo("skyatlantic.uk")
    }

    @Test
    fun `a shorter and a longer guide key that disagree are ambiguous`() {
        val idx = index(
            "skysports.uk" to "Sky Sports",
            "skysportsnews.uk" to "Sky Sports News",
        )

        // The provider name extends one guide key and is extended by the other.
        assertThat(idx.match("skysportsne")).isNull()
        assertThat(idx.match("skysportsnews")).isEqualTo("skysportsnews.uk")
    }

    @Test
    fun `indexed prefix tier agrees with a linear scan over every alias`() {
        // Small alphabet, so keys constantly prefix one another and collide.
        val random = java.util.Random(42)
        fun randomKey(maxLength: Int) =
            (0 until random.nextInt(maxLength + 1)).map { "abc"[random.nextInt(3)] }.joinToString("")

        repeat(20) {
            val aliases = (0 until 150).map { "id${random.nextInt(60)}" to randomKey(7) }
            val idx = EpgMatcher.buildIndex(aliases)
            val queries = (0 until 2_000).map { randomKey(8) } +
                aliases.flatMap { (_, name) -> name.indices.map { name.substring(0, it + 1) } }

            for (query in queries) {
                assertThat(idx.match(query)).isEqualTo(linearScan(aliases, query))
            }
        }
    }

    /** The matcher as it was before the prefix tier was indexed: the reference behaviour. */
    private fun linearScan(aliases: List<Pair<String, String>>, groupKey: String): String? {
        if (groupKey.length < 2) return null
        val exact = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        val all = ArrayList<Pair<String, String>>()
        for ((epgId, name) in aliases) {
            for (candidate in listOf(name, epgId)) {
                val key = ChannelNameNormalizer.normalize(candidate).groupKey
                if (key.length < 2) continue
                all += epgId to key
                val existing = exact.putIfAbsent(key, epgId)
                if (existing != null && existing != epgId) ambiguous += key
            }
        }
        ambiguous.forEach(exact::remove)

        exact[groupKey]?.let { return it }
        var found: String? = null
        for ((epgId, key) in all) {
            if (!(key.startsWith(groupKey) || groupKey.startsWith(key))) continue
            if (found == null) found = epgId else if (found != epgId) return null
        }
        return found
    }
}
