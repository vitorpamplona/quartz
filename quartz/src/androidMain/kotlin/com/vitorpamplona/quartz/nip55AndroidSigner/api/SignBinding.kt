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
package com.vitorpamplona.quartz.nip55AndroidSigner.api

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.nip57Zaps.tags.AnonTag

/**
 * Whether [signed] is [unsignedEvent] signed: same pubkey, and its id the hash of exactly the
 * fields we asked to have signed. An unsigned private zap is the exception, since the signer
 * replaces it with an event under a throwaway key over encrypted content (NIP-57).
 */
internal fun isAnswerTo(
    signed: Event,
    unsignedEvent: Event,
): Boolean {
    if (unsignedEvent.kind == ZapRequestEvent.KIND && unsignedEvent.tags.any(AnonTag::isUnsignedPrivate)) return true
    if (signed.pubKey != unsignedEvent.pubKey) return false
    val expectedId = EventHasher.hashId(unsignedEvent.pubKey, unsignedEvent.createdAt, unsignedEvent.kind, unsignedEvent.tags, unsignedEvent.content)
    return signed.id == expectedId
}
