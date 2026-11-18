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
package com.vitorpamplona.quartz.llamagarden.release

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.llamagarden.release.tags.ReleaseVersionTag
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nipA0VoiceMessages.tags.HashSha256Tag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A llama.garden client release (kind 30100): a new build of the single-file viewer, uploaded to
 * Blossom (`url`, one per mirror) and pinned by its `sha256` and `size`. `d` names the client
 * (`waifu-magnet`); running viewers whose own version is lower offer to update, after hashing the
 * download against `sha256`. Only the viewer's whitelisted authors are trusted for it.
 *
 * **Kind 30100 is shared** with drss's feed lists and a CI bot's job reports. EventFactory tries
 * drss's stricter classifier first, then [isClientRelease], and parses the rest as
 * UnrecognizedKind30100Event.
 */
@Immutable
class ClientReleaseEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig) {
    /** The released client, the `d` (`waifu-magnet`). */
    fun clientId() = dTag()

    /** The version, as written (`22`). */
    fun version() = tags.version()

    /**
     * [version] as the viewer compares it, with JavaScript's `parseInt`: the leading digits (`22` for
     * `22`, `22.1` or `22a`); null when it does not start with one.
     */
    fun versionNumber(): Long? =
        version()
            ?.trim()
            ?.takeWhile { it.isDigit() }
            ?.ifEmpty { null }
            ?.toLongOrNull()

    /** SHA-256 (hex) the download must hash to. */
    fun sha256() = tags.sha256()

    /** Size in bytes of the released file. */
    fun fileSize() = tags.fileSize()

    /** Blossom mirrors of the release file. */
    fun downloadUrls() = tags.downloadUrls()

    companion object {
        const val KIND = 30100

        /** The `d` llama.garden's viewer publishes and listens for. */
        const val WAIFU_MAGNET = "waifu-magnet"

        /** True for llama.garden's shape: a `version` and a `sha256`. */
        fun isClientRelease(tags: TagArray): Boolean = tags.fastAny { ReleaseVersionTag.parse(it) != null } && tags.fastAny { HashSha256Tag.parse(it) != null }

        fun build(
            version: String,
            sha256: String,
            size: Long,
            downloadUrls: List<String>,
            clientId: String = WAIFU_MAGNET,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ClientReleaseEvent>.() -> Unit = {},
        ) = eventTemplate<ClientReleaseEvent>(KIND, "", createdAt) {
            dTag(clientId)
            version(version)
            sha256(sha256)
            fileSize(size)
            downloadUrls(downloadUrls)
            initializer()
        }
    }
}
