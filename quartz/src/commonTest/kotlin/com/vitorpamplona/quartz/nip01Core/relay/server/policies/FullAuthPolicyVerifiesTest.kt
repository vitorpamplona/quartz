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
package com.vitorpamplona.quartz.nip01Core.relay.server.policies

import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.AuthCmd
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import kotlin.test.Test
import kotlin.test.assertIs

/** FullAuthPolicy checks the AUTH event's signature itself, with no VerifyPolicy in front. */
class FullAuthPolicyVerifiesTest {
    private val relayUrl = NormalizedRelayUrl("wss://relay.example.com/")

    @Test
    fun aForgedAuthIsRefusedWithoutAVerifyPolicyInFront() {
        val policy = FullAuthPolicy(relayUrl)
        val signed = NostrSignerSync().sign(RelayAuthEvent.build(relayUrl, policy.challenge))
        val impostor = KeyPair().pubKey.toHexKey()
        val forged = RelayAuthEvent(signed.id, impostor, signed.createdAt, signed.tags, signed.content, signed.sig)

        assertIs<PolicyResult.Accepted<*>>(policy.accept(AuthCmd(signed)), "a genuine AUTH passes")
        assertIs<PolicyResult.Rejected<*>>(policy.accept(AuthCmd(forged)), "a forged one does not")
    }
}
