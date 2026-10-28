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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/** A content-addressed napplet icon: the blob's sha256 and its declared image type. */
@Immutable
data class NappletIcon(
    val hash: HexKey,
    val mimeType: String,
) {
    /** The usual file extension for [mimeType], so a cache or decoder can treat the blob as an image. */
    fun extension() =
        when (mimeType) {
            IconTag.PNG -> "png"
            IconTag.JPEG -> "jpg"
            else -> "webp"
        }
}

/**
 * NIP-5D icon: `["icon", "<sha256>", "<mime-type>"]`, at most one. The MIME type MUST be
 * `image/png`, `image/jpeg` or `image/webp` (no SVG). A client fetches the blob by hash from the
 * manifest's `server`s, verifies the hash and that the bytes decode as the declared format, and
 * shows generic artwork when anything is off. The icon never blocks loading the napplet and is not
 * part of its identity.
 */
class IconTag {
    companion object {
        const val TAG_NAME = "icon"
        const val PNG = "image/png"
        const val JPEG = "image/jpeg"
        const val WEBP = "image/webp"

        val SUPPORTED_TYPES = setOf(PNG, JPEG, WEBP)

        fun isTag(tag: Array<String>) = tag.has(0) && tag[0] == TAG_NAME

        fun parse(tag: Array<String>): NappletIcon? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(ArtifactTag.isLowercaseSha256(tag[1])) { return null }
            ensure(tag[2] in SUPPORTED_TYPES) { return null }
            return NappletIcon(tag[1], tag[2])
        }

        fun assemble(icon: NappletIcon) = arrayOf(TAG_NAME, icon.hash, icon.mimeType)
    }
}
