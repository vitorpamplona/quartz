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
package com.vitorpamplona.quartz.experimental.audio.track.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Tag
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.people.PubKeyReferenceTag
import com.vitorpamplona.quartz.utils.arrayOfNotNull
import com.vitorpamplona.quartz.utils.ensure

/**
 * A participant of an audio track (kind 31337), with the role they had in it.
 *
 * Zapstr, whose kind this is, writes `["p", <pubkey>, <role>]`: the role in slot 2, where NIP-01
 * puts a relay hint ("author", "Host", "Guest", "Producer", "Artist, Producer"; nostr.mom,
 * 2023). The NIP-53 shape, `["p", <pubkey>, <relay>, <role>]`, is read too: a slot 2 that is a
 * relay url (or empty) is the [relayHint] and the role is in slot 3; any other slot 2 is the [role].
 */
@Immutable
data class ParticipantTag(
    override val pubKey: String,
    override val relayHint: NormalizedRelayUrl?,
    val role: String? = null,
) : PubKeyReferenceTag {
    @Deprecated("Kept for binary compatibility; use the constructor with a role.", level = DeprecationLevel.HIDDEN)
    constructor(pubKey: String, relayHint: NormalizedRelayUrl?) : this(pubKey, relayHint, null)

    @Deprecated("Kept for binary compatibility; use copy with a role.", level = DeprecationLevel.HIDDEN)
    fun copy(
        pubKey: String = this.pubKey,
        relayHint: NormalizedRelayUrl? = this.relayHint,
    ) = ParticipantTag(pubKey, relayHint, role)

    /** The [role] split on commas, as Zapstr writes several (`"Artist, Producer"`), trimmed and without empties. */
    fun roles(): List<String> = role?.split(',')?.mapNotNull { it.trim().ifEmpty { null } } ?: emptyList()

    fun toTagArray() = assemble(pubKey, relayHint, role)

    companion object {
        const val TAG_NAME = "p"

        fun parse(tag: Tag): ParticipantTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }

            val slot2 = tag.getOrNull(2) ?: return ParticipantTag(tag[1], null, null)

            return if (slot2.isEmpty() || RelayUrlNormalizer.isRelayUrl(slot2)) {
                ParticipantTag(tag[1], RelayUrlNormalizer.normalizeHintOrNull(slot2), tag.getOrNull(3)?.ifBlank { null })
            } else {
                ParticipantTag(tag[1], null, slot2.ifBlank { null })
            }
        }

        fun parseKey(tag: Tag): String? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }
            return tag[1]
        }

        fun assemble(
            pubkey: HexKey,
            relayHint: NormalizedRelayUrl? = null,
        ) = arrayOfNotNull(TAG_NAME, pubkey, relayHint?.url)

        /**
         * Zapstr's `["p", pubkey, role]` without a [relayHint]; with one, the NIP-53 order
         * `["p", pubkey, relay, role]`. [parse] reads both back.
         */
        fun assemble(
            pubkey: HexKey,
            relayHint: NormalizedRelayUrl?,
            role: String?,
        ) = if (relayHint != null) {
            arrayOfNotNull(TAG_NAME, pubkey, relayHint.url, role)
        } else {
            arrayOfNotNull(TAG_NAME, pubkey, role)
        }
    }
}
