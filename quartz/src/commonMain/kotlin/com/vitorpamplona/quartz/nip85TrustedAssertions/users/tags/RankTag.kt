/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.nip85TrustedAssertions.users.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class RankTag {
    companion object {
        const val TAG_NAME = "rank"

        fun parse(tag: Array<String>): Int? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotEmpty()) { return null }
            return tag[1].toIntOrNull()
        }

        /** The bounds of NIP-85's rank: an integer normalized to 0..100. */
        val SCALE = 0..100

        /**
         * [parse], clamped onto NIP-85's normalized [SCALE] (0..100): providers are not trusted to
         * stay on scale, so a negative rank reads as 0 and an over-scale one as 100. Null only when
         * the tag is not a rank tag or its value is not an integer.
         *
         * Rank 0 is a VALUE here ("the provider scores this subject at the bottom"), which is
         * what a store that ranks or floors on the score needs. Quartz's trust index
         * (`memberRank`, used by `TrustNetworkBuilder`) reads it differently on purpose: there a
         * card with rank 0 or no rank is a removal, because Brainstorm zeroes a card before the
         * kind 5 that deletes it. Pick the reading your consumer means; do not mix them over one
         * index.
         *
         * @see com.vitorpamplona.quartz.nip85TrustedAssertions.users.index.TrustNetworkBuilder
         */
        fun parseBounded(tag: Array<String>): Int? = parse(tag)?.coerceIn(SCALE)

        fun assemble(rank: Int) = arrayOf(TAG_NAME, rank.toString())
    }
}
