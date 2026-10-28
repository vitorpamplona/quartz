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
package com.vitorpamplona.quartz.nip59Giftwrap.wraps

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class EphemeralGiftWrapEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : GiftWrapEvent(id, pubKey, createdAt, tags, content, sig, KIND) {
    override fun copyNoContent(): EphemeralGiftWrapEvent {
        val copy =
            EphemeralGiftWrapEvent(
                id,
                pubKey,
                createdAt,
                tags,
                "",
                sig,
            )

        copy.innerEventId = innerEventId

        return copy
    }

    companion object {
        const val KIND = 21059
        const val ALT = "Ephemeral encrypted event"

        /** The unsigned wrap: [event] NIP-44 encrypted by [wrapSigner], the throwaway key that signs it, to [recipient]. */
        fun build(
            event: Event,
            recipient: PTag,
            wrapSigner: NostrSignerSync,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<EphemeralGiftWrapEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, wrapSigner.nip44Encrypt(event.toJson(), recipient.pubKey), createdAt) {
            recipient(recipient)
            initializer()
        }

        fun create(
            event: Event,
            recipientPubKey: HexKey,
            createdAt: Long = TimeUtils.now(),
        ): EphemeralGiftWrapEvent {
            val signer = NostrSignerSync(KeyPair())
            return signer.sign(build(event, PTag(recipientPubKey), signer, createdAt))
        }
    }
}
