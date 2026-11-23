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
package com.vitorpamplona.quartz.nip46RemoteSigner.signer

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponse
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponseError
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerResponseEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.nip57Zaps.tags.AnonTag

class SignResponse {
    companion object {
        /**
         * [parse], then bound to the request: the event must be [template] signed by [pubKey] (by
         * whoever signed it while the user's key is still unknown, null), its id the hash of exactly
         * those fields. A valid signature alone only proves the bunker signed *something*: a buggy
         * bunker (or a replayed response) could hand back a different event, or one under another
         * key, and the caller would publish it as asked.
         */
        fun parse(
            response: BunkerResponse,
            pubKey: HexKey?,
            template: EventTemplate<*>,
        ): SignerResult.RequestAddressed<SignResult> {
            val result = parse(response)
            if (result !is SignerResult.RequestAddressed.Successful<SignResult>) return result
            // An unsigned private zap is signed by a throwaway key over encrypted content: a
            // different event by design (NIP-57 Appendix), so there is nothing to bind it to.
            if (template.kind == ZapRequestEvent.KIND && template.tags.any(AnonTag::isUnsignedPrivate)) return result
            val event = result.result.event
            if (pubKey != null && event.pubKey != pubKey) return SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent(event)
            val expectedId = EventHasher.hashId(event.pubKey, template.createdAt, template.kind, template.tags, template.content)
            return if (event.id == expectedId) {
                result
            } else {
                SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent(event)
            }
        }

        fun parse(response: BunkerResponse): SignerResult.RequestAddressed<SignResult> =
            if (response is BunkerResponseEvent) {
                if (!response.event.verify()) {
                    SignerResult.RequestAddressed.ReceivedButCouldNotVerifyResultingEvent(response.event)
                } else {
                    SignerResult.RequestAddressed.Successful(SignResult(response.event))
                }
            } else if (response is BunkerResponseError) {
                SignerResult.RequestAddressed.Rejected(response.error)
            } else {
                SignerResult.RequestAddressed.ReceivedButCouldNotPerform()
            }
    }
}
