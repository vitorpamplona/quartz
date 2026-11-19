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
package com.vitorpamplona.quartz.concord.cord04Roles

import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.crypto.EditionHash
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CORD-04 §1 marked editions (`eh`/`eb`) and the Roles v2 coordinates of CORD-02 A.6. */
class MarkedEditionTest {
    private fun bytes(b: Int) = ByteArray(32) { b.toByte() }

    @Test
    fun markedHashMatchesTheSpecVector() {
        val content = """{"name":"Mods"}""".encodeToByteArray()
        assertEquals(
            "3c1d4111bff28cea4c1f237dcce34efbc227db6e304cdd1146e1413657972475",
            EditionHash.hash(bytes(0x11), 2, bytes(0x22), content).toHexKey(),
        )
        assertEquals(
            "462cc81a6f74622e5ff660ab3efcc007527c8d108e8242942f7ba7787a27d0ed",
            EditionHash.markedHash(bytes(0x11), 2, bytes(0x22), content, bytes(0x33)).toHexKey(),
        )
    }

    @Test
    fun rolesV2CoordinatesMatchTheSpecVectors() {
        val cid = bytes(0x01)
        val channel = bytes(0x02)
        assertEquals("3fbe677b7b2bbddc50d08a428d4e61c8e4c7fdba586d13f1b0f421ab906eeebd", ConcordKeyDerivation.everyoneCoordinate(cid).toHexKey())
        assertEquals("d09129fb5961dc61a181cdfaa2c6588396465ebbdb2c45d79b3fae0b9ad71955", ConcordKeyDerivation.editionBinding(cid).toHexKey())
        assertEquals("e252eea5e2d7172d166c2dc9c6902df736180fb2105bfaf859f990f509be57d1", ConcordKeyDerivation.tenureCoordinate(cid, bytes(0x03), bytes(0x04)).toHexKey())
        assertEquals("6b08541be1e17ef80f2552273d2534d5b6eac9ab244c4044d2cf150cecfab49f", ConcordKeyDerivation.syncCoordinate(cid, channel).toHexKey())
        assertEquals("f79c6f82f7076ca71112846c985a42acee711f95e2b37aaf05ed4777b0779324", ConcordKeyDerivation.writersCoordinate(cid, channel).toHexKey())
        val everyone = ConcordKeyDerivation.everyoneCoordinate(cid)
        assertEquals("5b337620f7f8d929a0434be597e20ce5c4440bce8c377eb41e0b3b250b6a0ffe", ConcordKeyDerivation.aclCoordinate(cid, channel, "everyone", everyone).toHexKey())
        assertEquals("cf60d1469c48371fe46a8a2643b4dc0e9da025ba3ac1b08c07acc63ca27d1393", ConcordKeyDerivation.aclCoordinate(cid, channel, "role", bytes(0x05)).toHexKey())
        assertEquals("1ac57124b4398297a2ac7c96099cef0f21b206e3602921f740c89408c3e69ed1", ConcordKeyDerivation.aclCoordinate(cid, channel, "member", bytes(0x03)).toHexKey())
    }

    @Test
    fun accessAndWriterAddressesMatchTheSpecVectors() {
        val secret = ByteArray(32) { it.toByte() }
        val id = ByteArray(32) { (0xff - it).toByte() }
        assertEquals("901126284c4d1dfd5e753aa866e28b498825c3382611d1f5d5e929de0bcae15e", ConcordKeyDerivation.channelAccessKey(secret, id, 0).publicKeyHex)
        assertEquals("7a5b45238020c162ffb7237c680934cf088ddfa47e1d5e2d630450672bc32555", ConcordKeyDerivation.channelWriterKey(secret, id, 1).publicKeyHex)
    }

    private val author = KeyPair().pubKey.toHexKey()
    private val cid = bytes(0x01)
    private val binding = ConcordKeyDerivation.editionBinding(cid)

    private fun rumor(vararg extra: Array<String>): Event {
        val tags = arrayOf(arrayOf("vsk", "13"), arrayOf("eid", bytes(0x05).toHexKey()), arrayOf("ev", "1"), *extra)
        return Event("ab".repeat(32), author, 1_700_000_000, 3308, tags, """{"role_id":"${bytes(0x05).toHexKey()}","rank":"V"}""", "")
    }

    @Test
    fun aMarkedEditionHashesOverItsSignerAndKnowsItsCommunity() {
        val marked = ControlEdition.fromRumor(rumor(arrayOf("eh", "2"), arrayOf("eb", binding.toHexKey())))
        assertNotNull(marked)
        assertTrue(marked.isMarked)
        assertEquals(
            EditionHash.markedHash(bytes(0x05), 1, null, marked.content.encodeToByteArray(), author.hexToByteArray()).toHexKey(),
            marked.hashHex,
        )
        assertTrue(marked.isAdmissibleIn(binding))
        assertFalse(marked.isAdmissibleIn(ConcordKeyDerivation.editionBinding(bytes(0x09))))
    }

    @Test
    fun theMarkerRulesRefuseWhatTheSpecRefuses() {
        // An unknown hash form, a marker without its binding, and a repeated marker tag are refused.
        assertNull(ControlEdition.fromRumor(rumor(arrayOf("eh", "3"), arrayOf("eb", binding.toHexKey()))))
        assertNull(ControlEdition.fromRumor(rumor(arrayOf("eh", "2"))))
        assertNull(ControlEdition.fromRumor(rumor(arrayOf("eh", "2"), arrayOf("eh", "2"), arrayOf("eb", binding.toHexKey()))))
        assertNull(ControlEdition.fromRumor(rumor(arrayOf("eh", "2"), arrayOf("eb", binding.toHexKey()), arrayOf("eb", binding.toHexKey()))))
        // An `eb` without `eh` is ignored: the edition reads unmarked.
        val unmarked = ControlEdition.fromRumor(rumor(arrayOf("eb", binding.toHexKey())))
        assertNotNull(unmarked)
        assertFalse(unmarked.isMarked)
        // ...and an unmarked edition of a kind no older client wrote is refused by every reader.
        assertFalse(unmarked.isAdmissibleIn(binding))
    }

    @Test
    fun aMarkedLinkNamesItsSignerToo() {
        val other = KeyPair().pubKey.toHexKey()
        val content = """{"name":"Mods"}""".encodeToByteArray()
        val a = EditionHash.markedHash(bytes(0x11), 2, null, content, author.hexToByteArray())
        val b = EditionHash.markedHash(bytes(0x11), 2, null, content, other.hexToByteArray())
        assertNotEquals(a.toHexKey(), b.toHexKey())
    }

    @Test
    fun theBuilderWritesTheMarkerLast() {
        val rumor = ControlEditionBuilder.rumor(author, ControlEntityKind.RANKED_ROLE, bytes(0x05), 1, null, "{}", 1_700_000_000, null, binding)
        assertEquals(listOf("vsk", "eid", "ev", "eh", "eb"), rumor.tags.map { it[0] })
        assertEquals(binding.toHexKey(), ControlEdition.fromRumor(rumor)?.binding?.toHexKey())
    }
}
