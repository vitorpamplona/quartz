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
package com.vitorpamplona.quartz.nip30CustomEmoji.selection

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip30CustomEmoji.EmojiUrlTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiListAccessorsTest {
    private val pk = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val other = "99bb5591c9116600f845107d31f9b59e2f7c7e09a1ff802e84f1d43da557ca64"
    private val third = "7d7ffd720b907fe597a7f454afe02f2dc1eca440baa029e9117b1c3209839377"
    private val eventId = "43575072239da152afe3d7b5c70ed2beb48db2b10e60c60da45229c09c877d2a"
    private val eventId2 = "b1a2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val addressId = "30023:$other:my-article"
    private val relay = "wss://relay.damus.io/"
    private val id = "00".repeat(32)
    private val sig = "00".repeat(64)

    @Test
    fun hasEmojiPackChecksTheSelectedPacks() {
        val pack = "30030:$other:cats"
        val list = EmojiListEvent(id, pk, 1L, arrayOf(arrayOf("a", pack, relay)), "", sig)

        assertTrue(list.hasEmojiPack(pack))
        assertFalse(list.hasEmojiPack("30030:$other:dogs"))
    }

    /** NIP-51: kind 10030's `a` tags point at kind 30030 emoji sets; any other `a` is not a pack. */
    @Test
    fun emojiPacksAreOnlyTheKind30030Addresses() {
        val pack = "30030:$other:cats"
        val list = EmojiListEvent(id, pk, 1L, arrayOf(arrayOf("a", pack, relay), arrayOf("a", addressId)), "", sig)

        assertEquals(listOf(pack), list.emojiPackIds())
        assertEquals(listOf(pack), list.emojiPacks().map { it.toValue() })
        assertEquals(listOf(pack), list.linkedAddressIds())
    }

    /** NIP-51: kind 10030 also carries the user's own preferred emojis as NIP-30 `emoji` tags. */
    @Test
    fun emojisAreTheListsOwnValidEmojiTags() {
        val url = "https://gleasonator.com/emoji/Gleasonator/soapbox.png"
        val pack = "30030:$other:cats"
        val list =
            EmojiListEvent(
                id,
                pk,
                1L,
                arrayOf(arrayOf("emoji", "soapbox", url, pack), arrayOf("emoji", "pepe laugh", url), arrayOf("a", pack), arrayOf("emoji", "blank", "")),
                "",
                sig,
            )

        assertEquals(listOf(EmojiUrlTag("soapbox", url, Address(30030, other, "cats"))), list.emojis())
    }
}
