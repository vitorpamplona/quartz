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
package com.vitorpamplona.quartz.nipB7Blossom

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.Serializable

@Serializable
data class BlossomUploadResult(
    // A publicly accessible URL to the BUD-01 GET /<sha256> endpoint (optionally with a file extension)
    val url: String?,
    // The sha256 hash of the blob
    val sha256: HexKey? = null,
    // The size of the blob in bytes
    val size: Long? = null,
    // (optional) The MIME type of the blob
    val type: String? = null,
    // upload time
    val uploaded: Long? = null,
    // (BUD-05) The sha256 hash of the *original* blob, before server-side
    // optimization. Only returned by the `/media` endpoint when the server
    // transforms the file (so sha256 != ox).
    val ox: HexKey? = null,
    // magnet link
    val magnet: String? = null,
    // info hash
    val infohash: String? = null,
    // ipfs link
    val ipfs: String? = null,
    // (BUD-08) Optional NIP-94 file-metadata tags describing this blob, so a
    // client gets standardized metadata (dimensions, blurhash, alt, …) without
    // a separate request. Each entry is a NIP-94 tag: [name, value, …].
    val nip94: List<List<String>>? = null,
)
