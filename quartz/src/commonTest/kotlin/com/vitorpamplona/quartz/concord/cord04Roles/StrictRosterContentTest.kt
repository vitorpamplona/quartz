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

import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Roles v2 content is read as Vector's serde and Armada's `strictJson.ts` read it (CORD-04 §2): a
 * repeated field, an unquoted key or a lone surrogate refuses the edition, where a lenient last-wins
 * read would fold a Role the other clients never see. Legacy content keeps its lenient reader.
 */
class StrictRosterContentTest {
    private val id = "ab".repeat(32)

    @Test
    fun aRepeatedFieldRefusesTheRole() {
        assertNotNull(RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V","permissions":"0"}"""))
        assertNull(RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V","permissions":"0","permissions":"9223372036854775808"}"""))
        assertNull(RankedRosterContent.parseEveryone("""{"permissions":"0","permissions":"1"}"""))
        assertNull(RankedRosterContent.parseGrant("""{"member":"$id","member":"$id"}"""))
        assertNull(RankedRosterContent.parseSync("""{"channel":"$id","sync":null,"sync":"$id"}"""))
    }

    @Test
    fun lenientFormsRefuseTheRole() {
        assertNull(RankedRosterContent.parseRankedRole("""{role_id:"$id","rank":"V"}"""), "unquoted key")
        assertNull(RankedRosterContent.parseRankedRole("""{"role_id":$id,"rank":"V"}"""), "unquoted string")
        assertNull(RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V",}"""), "trailing comma")
        assertNull(RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V","name":"\ud800"}"""), "lone surrogate")
        assertNull(RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V","color":-0}"""), "-0 is no u32")
        assertNull(RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V","color":1.0}"""), "1.0 is no u32")
        // A valid pair is fine.
        assertEquals("😀", (RankedRosterContent.parseRankedRole("""{"role_id":"$id","rank":"V","name":"\ud83d\ude00"}""") as RankedRosterContent.ParsedRole.Live).name)
    }

    @Test
    fun aRepeatedFieldInsideATargetOrATenureEntryRefusesIt() {
        assertNotNull(RankedRosterContent.parseOverwrite("""{"scope":"$id","target":{"kind":"role","id":"$id"},"allow":"0","deny":"0"}"""))
        assertNull(RankedRosterContent.parseOverwrite("""{"scope":"$id","target":{"kind":"everyone","kind":"role","id":"$id"},"allow":"0","deny":"0"}"""))
        assertNotNull(RankedRosterContent.parseTenure("""{"member":"$id","sealed":[{"rank":"V","permissions":"1","heads":[]}]}"""))
        assertNull(RankedRosterContent.parseTenure("""{"member":"$id","sealed":[{"rank":"V","permissions":"1","permissions":"3","heads":[]}]}"""))
    }

    @Test
    fun legacyContentStillReadsLeniently() {
        assertEquals("Mods", ConcordJson.decodeOrNull<RoleEntity>("""{name:"Mods",position:2}""")?.name)
    }

    @Test
    fun theStrictReaderKeepsNumbersAsWrittenAndCapsDepth() {
        assertEquals("1e3", ConcordStrictJson.parse("1e3")!!.value.toString())
        assertNull(ConcordStrictJson.parse("[".repeat(128) + "]".repeat(128)))
        assertNotNull(ConcordStrictJson.parse("[".repeat(127) + "]".repeat(127)))
        assertNull(ConcordStrictJson.parse("01"))
        assertNull(ConcordStrictJson.parse("\"a\u0001\""))
    }

    private val cid = ByteArray(32) { 7 }
    private val channel = "cd".repeat(32)
    private val eid = ConcordKeyDerivation.writersCoordinate(cid, channel.hexToBytes()).toHexKey()
    private val w = KeyPair().pubKey.toHexKey()

    private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ledger(entry: String) = ConcordWritersLedger.parse("""{"channel":"$channel","ledger":[$entry]}""", cid, eid)

    @Test
    fun aLedgerEntryIsClassifiedByEveryFieldItCarries() {
        assertNotNull(ledger("""{"at":1,"epoch":1,"writer":"$w","root":0,"by":"$w"}"""))
        assertNotNull(ledger("""{"at":1,"open":true}"""))
        assertNotNull(ledger("""{"at":1,"novel":"kind"}"""), "an unknown kind is carried")
        assertNull(ledger("""{"at":1,"open":true,"epoch":1}"""), "open with a writer field")
        assertNull(ledger("""{"at":1,"open":false}"""), "open must be true")
        assertNull(ledger("""{"at":1,"open":null}"""), "a present null is present")
        assertNull(ledger("""{"at":1,"epoch":1,"writer":"$w","root":0,"by":"$w","stop":"$w"}"""), "writer with a stop")
        assertNull(ledger("""{"at":1,"root":5}"""), "an unknown kind reusing root")
        assertNull(ledger("""{"at":1,"by":"$w"}"""), "an unknown kind reusing by")
        assertNull(ledger("""{"at":1.0,"open":true}"""), "a float date")
        assertNull(ledger("""{"at":1,"epoch":1,"writer":"$w","root":0,"by":"$w","certifier":null}"""), "a null certifier")
        // A stop needs a writer address before it, never the read address.
        assertNull(ConcordWritersLedger.parse("""{"channel":"$channel","ledger":[{"at":1,"open":true},{"at":2,"stop":"$w"}]}""", cid, eid))
        val stopped = ConcordWritersLedger.parse("""{"channel":"$channel","ledger":[{"at":1,"epoch":1,"writer":"$w","root":0,"by":"$w"},{"at":2,"stop":"$w","from":0}]}""", cid, eid)
        assertNotNull(stopped)
        assertEquals(0L, stopped.stops.single().fromMs)
        assertTrue(stopped.isRestricted)
    }
}
