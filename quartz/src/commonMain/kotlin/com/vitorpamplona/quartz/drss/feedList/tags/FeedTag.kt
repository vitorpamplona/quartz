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
package com.vitorpamplona.quartz.drss.feedList.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.utils.arrayOfNotNull
import com.vitorpamplona.quartz.utils.ensure

/**
 * A feed in a drss feed list: the feed's bot key, the relay that holds it and the feed's title,
 * `["p", <bot>, <relay>, <title>]`. A `p` tag with a petname, as NIP-02 writes one.
 */
@Immutable
data class FeedTag(
    val pubKey: HexKey,
    val relayHint: NormalizedRelayUrl? = null,
    val title: String? = null,
) {
    fun toTagArray() = assemble(pubKey, relayHint, title)

    companion object {
        const val TAG_NAME = PTag.TAG_NAME

        fun parse(tag: Array<String>): FeedTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }
            val relay = tag.getOrNull(2)?.let { RelayUrlNormalizer.normalizeHintOrNull(it) }
            return FeedTag(tag[1], relay, tag.getOrNull(3)?.ifBlank { null })
        }

        fun parseKey(tag: Array<String>): HexKey? = PTag.parseKey(tag)

        fun parseAsHint(tag: Array<String>): PubKeyHint? = PTag.parseAsHint(tag)

        /** The title of [tag], when it is a feed with one. */
        fun parseTitle(tag: Array<String>): String? {
            ensure(tag.has(3)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            return tag[3].ifBlank { null }
        }

        fun assemble(
            pubKey: HexKey,
            relayHint: NormalizedRelayUrl?,
            title: String?,
        ) = if (title.isNullOrBlank()) {
            arrayOfNotNull(TAG_NAME, pubKey, relayHint?.url)
        } else {
            arrayOf(TAG_NAME, pubKey, relayHint?.url ?: "", title)
        }

        fun assemble(feeds: List<FeedTag>) = feeds.map { it.toTagArray() }
    }
}
