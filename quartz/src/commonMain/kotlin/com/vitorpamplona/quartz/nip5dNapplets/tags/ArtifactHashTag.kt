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
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.XTag
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-5D artifact tag: `["x", "<sha256>"]`, the content address of a napplet's single
 * self-contained `/index.html`. A manifest MUST carry exactly one.
 *
 * Same tag name as NIP-5A's aggregate `["x", "<sha256>", "aggregate"]`, which hashes a list of
 * `path` tags instead of one blob; a tag carrying that marker is not an artifact hash.
 */
class ArtifactHashTag {
    companion object {
        const val TAG_NAME = "x"

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): HexKey? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            // The hash names the blob in Blossom URLs and on disk, so anything but a sha256 is refused.
            ensure(tag[1].length == 64 && Hex.isHex64(tag[1])) { return null }
            ensure(!tag.has(2) || tag[2] != XTag.AGGREGATE_MARKER) { return null }
            return tag[1].lowercase()
        }

        fun assemble(artifactHash: HexKey) = arrayOf(TAG_NAME, artifactHash)
    }
}
