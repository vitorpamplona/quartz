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
package com.vitorpamplona.quartz.nip19Bech32

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A setting, a config file or a form that asks for a PUBLIC key must never accept a private one:
 * [decodePublicKeyAsHexOrNull] turns an nsec into its pubkey, so a pasted nsec "works" and the
 * secret now sits in a public field. [decodePublicKeyOnlyAsHexOrNull] refuses it.
 */
class DecodePublicKeyOnlyTest {
    private val secret = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val pubkey = KeyPair(secret.hexToByteArray()).pubKey.toHexKey()
    private val nsec = secret.hexToByteArray().toNsec()
    private val npub = pubkey.hexToByteArray().toNpub()

    @Test
    fun publicKeyFormsDecode() {
        assertEquals(pubkey, decodePublicKeyOnlyAsHexOrNull(npub))
        assertEquals(pubkey, decodePublicKeyOnlyAsHexOrNull("nostr:$npub"))
        assertEquals(pubkey, decodePublicKeyOnlyAsHexOrNull(NProfile.create(pubkey, "wss://relay.example.com".normalizeRelayUrl())))
        assertEquals(pubkey, decodePublicKeyOnlyAsHexOrNull(pubkey))
        assertEquals(pubkey, decodePublicKeyOnlyAsHexOrNull(pubkey.uppercase()), "hex is folded to lowercase")
    }

    @Test
    fun aPrivateKeyIsNeverAPublicOne() {
        assertNull(decodePublicKeyOnlyAsHexOrNull(nsec))
        assertNull(decodePublicKeyOnlyAsHexOrNull("nostr:$nsec"))
        // The lenient decoder is unchanged: it still derives the pubkey, which is the footgun.
        assertEquals(pubkey, decodePublicKeyAsHexOrNull(nsec))
    }

    @Test
    fun otherEntitiesAndMalformedInputAreRefused() {
        assertNull(decodePublicKeyOnlyAsHexOrNull(""))
        assertNull(decodePublicKeyOnlyAsHexOrNull("abcd"), "hex that is not 32 bytes")
        assertNull(decodePublicKeyOnlyAsHexOrNull(pubkey + "00"))
        assertNull(decodePublicKeyOnlyAsHexOrNull("zz".repeat(32)))
        @Suppress("DEPRECATION") // note1 is deprecated, but still an entity this must refuse
        assertNull(decodePublicKeyOnlyAsHexOrNull(pubkey.hexToByteArray().toNote()))
        assertNull(decodePublicKeyOnlyAsHexOrNull(npub.dropLast(1) + if (npub.last() == 'q') "p" else "q"), "a bad checksum")
        // The lenient decoder accepts any even-length hex, for comparison.
        assertEquals("abcd", decodePublicKeyAsHexOrNull("abcd"))
    }
}
