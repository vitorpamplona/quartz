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
package com.vitorpamplona.quartz.nip5dNapplets.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-5D artifact tag: `["x", "<sha256>"]`, the lowercase-hex sha256 of the napplet's single
 * self-contained `/index.html`. A manifest MUST carry exactly one; the runtime fetches that blob
 * from Blossom and verifies it before running anything.
 *
 * Same tag name as NIP-5A's `["x", "<sha256>", "aggregate"]`, which hashes a site's `path` tags
 * rather than one blob. [parse] refuses that marker, and [isTag] still counts the tag, so a
 * manifest carrying one has no valid single `x` and is rejected.
 */
class ArtifactTag {
    companion object {
        const val TAG_NAME = "x"
        private const val AGGREGATE_MARKER = "aggregate"

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): HexKey? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(isLowercaseSha256(tag[1])) { return null }
            ensure(!tag.has(2) || tag[2] != AGGREGATE_MARKER) { return null }
            return tag[1]
        }

        fun assemble(artifactHash: HexKey) = arrayOf(TAG_NAME, artifactHash)

        /** The hash names a blob in Blossom URLs and on disk, so nothing but a lowercase sha256 passes. */
        internal fun isLowercaseSha256(value: String): Boolean {
            if (value.length != 64) return false
            for (c in value) if (c !in '0'..'9' && c !in 'a'..'f') return false
            return true
        }
    }
}
