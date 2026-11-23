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
package com.vitorpamplona.quartz.nip02FollowList

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKey
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [ContactTag.parseValid] keeps a hex key's own string instead of decoding and re-encoding it;
 * every key must still come out exactly as the decode path made it.
 */
class ContactTagParseValidTest {
    private val pk = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val npub = pk.hexToByteArray().toNpub()

    /** What parseValid returned before the fast path. */
    private fun decoded(key: String): String? =
        try {
            decodePublicKey(key).toHexKey()
        } catch (e: Exception) {
            null
        }

    private val values =
        listOf(
            pk,
            pk.uppercase(),
            pk.substring(0, 32).uppercase() + pk.substring(32),
            "g" + pk.drop(1),
            "😀" + pk.drop(2),
            // not hex, and long enough to hide a whole npub that the regex picks out
            npub + "x",
            " " + pk.drop(1),
        )

    @Test
    fun matchesTheDecodePath() {
        values.forEach { value ->
            assertEquals(value.length, 64, "fixture $value must be 64 chars")
            assertEquals(decoded(value), ContactTag.parseValid(arrayOf("p", value))?.pubKey, value)
        }
    }

    @Test
    fun aLowercaseKeyIsTheTagsOwnString() {
        assertSame(pk, ContactTag.parseValid(arrayOf("p", pk))!!.pubKey)
    }

    @Test
    fun keepsTheHintAndPetname() {
        val tag = ContactTag.parseValid(arrayOf("p", pk.uppercase(), "wss://relay.damus.io", "vitor"))!!
        assertEquals(pk, tag.pubKey)
        assertEquals("wss://relay.damus.io/", tag.relayUri?.url)
        assertEquals("vitor", tag.petname)
        assertNull(ContactTag.parseValid(arrayOf("p", pk.drop(1))))
    }
}
