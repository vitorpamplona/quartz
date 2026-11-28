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
package com.vitorpamplona.quartz.nip34Git.git

import java.io.InputStream

/** The JVM form of [GitUploadPack], whose streamed pack is an [InputStream]. */
@Deprecated("Use GitUploadPack instead. Removed in 3.0.0.", ReplaceWith("GitUploadPack"))
object GitUploadPackV2 {
    fun lsRefsRequest(objectFormat: String): ByteArray = GitUploadPack.lsRefsRequest(objectFormat)

    fun fetchRequest(
        objectFormat: String,
        wants: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray = GitUploadPack.fetchRequest(objectFormat, wants, deepen, filter)

    /**
     * A `fetch` that also says what the client [haves], so the server sends only the objects
     * reachable from [wants] and not from them: a branch's own commits on top of a known base.
     */
    fun fetchRequest(
        objectFormat: String,
        wants: List<String>,
        haves: List<String>,
        deepen: Int?,
        filter: String?,
    ): ByteArray = GitUploadPack.fetchRequest(objectFormat, wants, haves, deepen, filter)

    fun parseCapabilities(lines: List<PktLine>): GitCapabilities = GitUploadPack.parseCapabilities(lines)

    fun parseRefs(lines: List<PktLine>): List<GitRef> = GitUploadPack.parseRefs(lines)

    /** Demuxes the sideband-64k `packfile` section of a fetch response into raw pack bytes. */
    fun extractPack(lines: List<PktLine>): ByteArray = GitUploadPack.extractPack(lines)

    /**
     * The `packfile` section of a fetch response read from [response] as it arrives: the pack
     * bytes of sideband channel 1, demuxed frame by frame, so a large pack is never held whole.
     * The sections before it (acknowledgments, shallow-info) are skipped; an `ERR` line, or a
     * fatal message on channel 3, is a [GitHttpException] when it is read. Closing it closes
     * [response].
     */
    fun packStream(response: InputStream): InputStream = GitUploadPack.packSource(response.asGitByteSource()).asInputStream { response.close() }
}
