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
package com.vitorpamplona.quartz.nip47WalletConnect

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip04Dm.crypto.Nip04
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcResponseEvent
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.PayInvoiceSuccessResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Relays deliver events unverified: only a response the wallet signed may parse. */
class Nip47ClientResponseOriginTest {
    private val clientSigner = NostrSignerInternal(KeyPair())
    private val walletSigner = NostrSignerInternal(KeyPair())
    private val relay = NormalizedRelayUrl("wss://relay.example.com/")
    private val client = Nip47Client(walletSigner.pubKey, relay, clientSigner)

    private suspend fun walletResponse(): NwcResponseEvent {
        val request = client.payInvoice("lnbc50n1")
        return NwcResponseEvent.createResponse(
            PayInvoiceSuccessResponse(PayInvoiceSuccessResponse.PayInvoiceResultParams(preimage = "00")),
            request,
            walletSigner,
        )
    }

    @Test
    fun theWalletsResponseParses() =
        runTest {
            assertIs<PayInvoiceSuccessResponse>(client.parseResponse(walletResponse()))
        }

    @Test
    fun aResponseAuthoredAsTheClientByAnAttackerIsRejected() =
        runTest {
            // Self-authored as the client, encrypted to the client by the attacker: the client can
            // decrypt it, but the wallet never sent it.
            val attacker = KeyPair()
            val content =
                Nip04.encrypt(
                    """{"result_type":"pay_invoice","result":{"preimage":"00"}}""",
                    attacker.privKey!!,
                    clientSigner.pubKey.hexToByteArray(),
                )
            val forged =
                NwcResponseEvent(
                    "1".repeat(64),
                    clientSigner.pubKey,
                    1L,
                    arrayOf(arrayOf("p", attacker.pubKey.toHexKey()), arrayOf("e", "2".repeat(64))),
                    content,
                    "0".repeat(128),
                )

            assertFailsWith<IllegalArgumentException> { client.parseResponse(forged) }
        }

    @Test
    fun aWalletResponseWithAlteredTagsIsRejected() =
        runTest {
            val genuine = walletResponse()
            val retargeted =
                NwcResponseEvent(
                    genuine.id,
                    genuine.pubKey,
                    genuine.createdAt,
                    arrayOf(arrayOf("p", clientSigner.pubKey), arrayOf("e", "3".repeat(64))),
                    genuine.content,
                    genuine.sig,
                )

            assertFailsWith<IllegalArgumentException> { client.parseResponse(retargeted) }
        }
}
