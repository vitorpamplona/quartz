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
package com.vitorpamplona.quartz.nipFBScheduledTasks

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ClaimTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledTaskTest {
    // NIP-FB's example tags, plus an option this client doesn't know.
    private val privateSample =
        arrayOf(
            arrayOf("task", "observer"),
            arrayOf("cron", "0 6 * * 1-5", "America/New_York"),
            arrayOf("claim", "1794398400", "3f1c9a0e5b7d4c21", "Desktop (Linux)"),
            arrayOf("ran", "1794398520"),
            arrayOf("length", "short"),
        )

    private val sample = ScheduledTask(arrayOf(arrayOf("d", "observer")), privateSample)

    @Test
    fun readsTheSpecExample() {
        assertEquals("observer", sample.task)
        assertTrue(sample.enabled)
        assertFalse(sample.paused)
        assertEquals("0 6 * * 1-5", sample.cron)
        assertEquals(SimpleCron(6, 0, setOf(1, 2, 3, 4, 5)), sample.simple())
        assertEquals("America/New_York", sample.tz)
        assertEquals(ClaimTag(1_794_398_400L, "3f1c9a0e5b7d4c21", "Desktop (Linux)"), sample.claim)
        assertEquals(1_794_398_520L, sample.ran)
    }

    @Test
    fun publicAndPrivateTagsMergePrivateFirst() {
        val split = ScheduledTask(arrayOf(arrayOf("d", "observer"), arrayOf("paused"), arrayOf("cron", "0 9 * * *", "UTC")), arrayOf(arrayOf("cron", "0 6 * * *", "America/New_York")))
        assertTrue(split.paused)
        assertEquals("0 6 * * *", split.cron)
        // Two on the same side: the first.
        assertEquals(10L, ScheduledTask(emptyArray(), arrayOf(arrayOf("ran", "10"), arrayOf("ran", "20"))).ran)
    }

    @Test
    fun eachTagStaysOnTheSideItWasFoundAndNewOnesGoPrivate() {
        val split = ScheduledTask(arrayOf(arrayOf("d", "observer"), arrayOf("cron", "0 9 * * *", "UTC"), arrayOf("x", "public option")), arrayOf(arrayOf("task", "observer"), arrayOf("y", "private option")))
        val edited =
            split
                .withCron("30 6 * * *", "Europe/Lisbon")
                .withClaim(ClaimTag(5, "id", "Pixel 8"))
                .withRan(9)
                .withEnabled(false)
        // The public cron changed in place; claim, ran and paused are new, so private.
        assertContentEquals(arrayOf("cron", "30 6 * * *", "Europe/Lisbon"), edited.publicTags[1])
        assertEquals(3, edited.publicTags.size)
        assertEquals(listOf("task", "y", "claim", "ran", "paused"), edited.privateTags.map { it[0] })
        // Unknown options survive on both sides.
        assertEquals("public option", edited.publicTags[2][1])
        assertEquals("private option", edited.privateTags[1][1])
        // Resuming removes `paused` wherever it is.
        assertFalse(edited.withEnabled(true).paused)
        assertFalse(ScheduledTask(arrayOf(arrayOf("paused")), arrayOf(arrayOf("paused"))).withEnabled(true).paused)
        // A second claim replaces the first, it doesn't stack.
        assertEquals(1, edited.withClaim(ClaimTag(6, "id2")).privateTags.count { it[0] == "claim" })
    }

    @Test
    fun oddTagsAreIgnoredButKept() {
        val odd = ScheduledTask(emptyArray(), arrayOf(arrayOf("cron", "@daily", "UTC"), arrayOf("claim", "soon", "x"), arrayOf("ran", "later"), arrayOf("task")))
        // A cron this client can't read is kept as written, and never runs.
        assertEquals("@daily", odd.cron)
        assertNull(odd.expression())
        assertNull(odd.claim)
        assertNull(odd.ran)
        assertNull(odd.task)
        assertEquals(4, odd.withEnabled(true).privateTags.size)
    }

    @Test
    fun aNewScheduleChangesTheZoneWithIt() {
        val moved = ScheduledTask.create("observer", "observer", zone = "UTC").withCron(SimpleCron(7, 30).toCron(), "Europe/Lisbon")
        assertEquals("30 7 * * *", moved.cron)
        assertEquals("Europe/Lisbon", moved.tz)
        assertEquals(SimpleCron(7, 30), moved.simple())
    }

    @Test
    fun privateTagsAreEncryptedToSelfAndOnlyDIsPublic() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val signed: Event = signer.sign(ScheduledTaskEvent.update(sample, signer, dTag = "observer", createdAt = 1_000))
            val event = EventFactory.create<Event>(signed.id, signed.pubKey, signed.createdAt, signed.kind, signed.tags, signed.content, signed.sig)
            assertIs<ScheduledTaskEvent>(event)
            assertEquals(ScheduledTaskEvent.KIND, event.kind)
            assertEquals(1, event.tags.size)
            assertEquals("observer", event.dTag())
            assertEquals(ScheduledTaskEvent.createAddress(signer.pubKey, "observer"), event.address())
            assertTrue(event.isContentEncoded())
            assertFalse(event.content.contains("America"))
            assertEquals(sample, event.task(signer))
            // Someone else can't read it, and it isn't theirs to rewrite.
            assertNull(event.task(NostrSignerInternal(KeyPair())))
        }

    @Test
    fun anEmptyPrivateSideIsStillEncrypted() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val publicOnly = ScheduledTask(arrayOf(arrayOf("d", "x"), arrayOf("task", "observer"), arrayOf("cron", "0 6 * * *", "UTC")))
            val event: ScheduledTaskEvent = signer.sign(ScheduledTaskEvent.update(publicOnly, signer, dTag = "x"))
            assertTrue(event.content.isNotEmpty())
            assertEquals("[]", signer.nip44Decrypt(event.content, signer.pubKey))
            assertEquals(publicOnly, event.task(signer))
            assertEquals("observer", event.task(signer)!!.task)
        }

    @Test
    fun theBuilderWritesTheTaskPrivately() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val event: ScheduledTaskEvent = signer.sign(ScheduledTaskEvent.build("observer", CronTag("0 6 * * *", "UTC"), signer, isPaused = true, dTag = "observer") { add(arrayOf("length", "short")) })
            assertEquals(1, event.tags.size)
            val task = event.task(signer)!!
            assertEquals("observer", task.task)
            assertTrue(task.paused)
            assertEquals("0 6 * * *", task.cron)
            assertEquals("short", PrivateTagsInContent.decode(signer.nip44Decrypt(event.content, signer.pubKey)).first { it[0] == "length" }[1])
        }

    @Test
    fun aRewriteNeverGoesBackInTime() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val previous: ScheduledTaskEvent = signer.sign(ScheduledTaskEvent.update(sample, signer, dTag = "x", createdAt = 200))
            assertEquals(201, ScheduledTaskEvent.nextCreatedAt(previous, now = 150))
            assertEquals(300, ScheduledTaskEvent.nextCreatedAt(previous, now = 300))
            assertEquals(50, ScheduledTaskEvent.nextCreatedAt(null, now = 50))
        }
}
