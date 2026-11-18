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
package com.vitorpamplona.quartz.nipFCMavenRepositories.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-FC `["manifest", "<sha256>", "<size>"]`: the manifest blob the event pins. The hash is read
 * in lower case and must be 32 bytes of hex; a missing or unreadable size reads as `-1`.
 */
@Immutable
class ManifestTag(
    val sha256: HexKey,
    val size: Long,
) {
    companion object {
        const val TAG_NAME = "manifest"

        fun isTag(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && Hex.isHexKey(tag[1])

        fun parse(tag: Array<String>): ManifestTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(Hex.isHexKey(tag[1])) { return null }
            return ManifestTag(tag[1].lowercase(), tag.getOrNull(2)?.toLongOrNull() ?: -1)
        }

        fun assemble(
            sha256: HexKey,
            size: Long,
        ) = arrayOf(TAG_NAME, sha256.lowercase(), size.toString())

        fun assemble(manifest: ManifestTag) = assemble(manifest.sha256, manifest.size)
    }
}
