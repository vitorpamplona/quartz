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
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip51Lists.PrivateTagArrayEventCache
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.Decision
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.RunnerCheck
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.RunnerVerdict
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScheduledTaskRunnerTest {
    private val reader = NostrSignerInternal(KeyPair())
    private val assistant = NostrSignerInternal(KeyPair())
    private val stranger = NostrSignerInternal(KeyPair())

    private val newYork = CronZone { -5 * 3_600 }
    private val day = 86_400L

    // 2026-11-11T11:00:00Z = 06:00 in New York.
    private val six = 1_794_394_800L

    private val daily =
        ScheduledTask
            .create("observer", "observer", "0 6 * * *", "America/New_York", setAt = 0)
            .withParam("model", "anthropic/claude-sonnet-4.5")

    private suspend fun signed(
        template: EventTemplate<ScheduledTaskEvent>,
        by: NostrSignerInternal = reader,
    ): ScheduledTaskEvent {
        val event: Event = by.sign(template)
        return EventFactory.create(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content, event.sig)
    }

    @Test
    fun aPublicPNamesTheRunner() {
        assertNull(daily.runner)
        assertFalse(daily.namesRunner)
        val run = daily.withRunner(assistant.pubKey, 0)
        assertEquals(assistant.pubKey, run.runner)
        assertTrue(run.namesRunner)
        assertFalse(run.hasRunnerConflict)
        assertContentEquals(arrayOf("p", assistant.pubKey), run.publicTags.last())
        // Read from the public tags only: a private `p` is just a tag that stays.
        val privateP = ScheduledTask(daily.publicTags, daily.privateTags + arrayOf(arrayOf("p", assistant.pubKey)))
        assertNull(privateP.runner)
        assertFalse(privateP.namesRunner)
        assertEquals(1, privateP.withRunner(null, 0).privateTags.count { it[0] == "p" })
    }

    @Test
    fun twoRunnersAreAConflictAndNobodyRunsIt() {
        val two = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", assistant.pubKey), arrayOf("p", stranger.pubKey)), daily.privateTags)
        assertTrue(two.hasRunnerConflict)
        assertTrue(two.namesRunner)
        assertNull(two.runner)
        assertEquals(Decision.RUNNER_NAMED, ScheduledTaskClaims.decide(two, "observer", six, six + 60))
        assertEquals(RunnerVerdict.RUNNER_CONFLICT, ScheduledTaskClaims.decideAsRunner(two, "observer", assistant.pubKey, newYork, six + 60).verdict)
        // Setting a runner again leaves exactly one.
        assertEquals(assistant.pubKey, two.withRunner(assistant.pubKey, 0).runner)
        // A `p` that isn't a key names nobody who can run it, and the author's devices still don't.
        val odd = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", "not-a-key")), daily.privateTags)
        assertNull(odd.runner)
        assertEquals(Decision.RUNNER_NAMED, ScheduledTaskClaims.decide(odd, "observer", six, six + 60))
    }

    @Test
    fun theAuthorsDevicesNeverClaimATaskWithARunner() {
        val run = daily.withRunner(assistant.pubKey, 0)
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(daily, "observer", six, six + 60))
        assertEquals(Decision.RUNNER_NAMED, ScheduledTaskClaims.decide(run, "observer", six, six + 60))
        assertEquals(Decision.RUNNER_NAMED, ScheduledTaskClaims.decide(run, "observer", six, six + 60, "install", OwnRun.DIED))
        // Back to devices: claimable again.
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(run.withRunner(null, 0), "observer", six, six + 60))
    }

    @Test
    fun bothTheAuthorAndTheRunnerReadThePrivateTags() =
        runTest {
            val event = signed(ScheduledTaskEvent.update(daily.withRunner(assistant.pubKey, 0), reader, "observer", createdAt = 1_000))
            assertEquals(listOf("d", "p"), event.tags.map { it[0] })
            assertEquals(assistant.pubKey, event.runner())
            assertFalse(event.content.contains("America"))
            val byAuthor = assertNotNull(event.task(reader))
            val byRunner = assertNotNull(event.task(assistant))
            assertEquals(daily.withRunner(assistant.pubKey, 0), byAuthor)
            assertEquals(byAuthor, byRunner)
            assertEquals("anthropic/claude-sonnet-4.5", byRunner.param("model")!!.value())
            // Encrypted to the runner, not to self: the author's own conversation doesn't open it.
            assertTrue(runCatching { PrivateTagsInContent.decrypt(event.content, reader, reader.pubKey) }.isFailure)
            // Nobody else reads it.
            assertNull(event.task(stranger))
            assertNull(event.privateTags(stranger))
            // The decryption cache serves the runner too.
            assertNotNull(PrivateTagArrayEventCache<ScheduledTaskEvent>(assistant).privateTags(event))
        }

    @Test
    fun clearingTheRunnerReEncryptsToSelf() =
        runTest {
            val withRunner = signed(ScheduledTaskEvent.update(daily.withRunner(assistant.pubKey, 0), reader, "observer", createdAt = 1_000))
            val read = assertNotNull(withRunner.task(reader))
            val back = signed(ScheduledTaskEvent.rewrite(read.withRunner(null, 0), reader, "observer", previous = withRunner, now = 900))
            assertEquals(1_001, back.createdAt)
            assertEquals(listOf("d"), back.tags.map { it[0] })
            assertNull(back.runner())
            assertEquals(daily, back.task(reader))
            // The runner no longer reads it: it isn't named, and it isn't encrypted to it.
            assertNull(back.task(assistant))
            assertTrue(runCatching { PrivateTagsInContent.decrypt(back.content, assistant, reader.pubKey) }.isFailure)
            assertEquals(daily, ScheduledTask(back.tags, PrivateTagsInContent.decrypt(back.content, reader, reader.pubKey)))
        }

    @Test
    fun movingToAnotherRunnerReEncryptsToIt() =
        runTest {
            val first = signed(ScheduledTaskEvent.update(daily.withRunner(assistant.pubKey, 0), reader, "observer", createdAt = 1_000))
            val moved = signed(ScheduledTaskEvent.rewrite(first.task(reader)!!.withRunner(stranger.pubKey, 0), reader, "observer", previous = first))
            assertEquals(stranger.pubKey, moved.runner())
            assertNotNull(moved.task(stranger))
            assertNull(moved.task(assistant))
            assertEquals(daily.withRunner(stranger.pubKey, 0), moved.task(reader))
        }

    @Test
    fun theAuthorStillReadsAConflict() =
        runTest {
            // Some client wrote two runners and encrypted to the second one.
            val two = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", stranger.pubKey), arrayOf("p", assistant.pubKey)), daily.privateTags)
            val content = reader.nip44Encrypt(PrivateTagsInContent.encode(two.privateTags), assistant.pubKey)
            val event = signed(EventTemplate(1_000, ScheduledTaskEvent.KIND, two.publicTags, content))
            assertEquals(two, event.task(reader))
            assertEquals(two, event.task(assistant))
            // Our own writer encrypts a conflict to self.
            val rewritten = signed(ScheduledTaskEvent.update(two, reader, "observer"))
            assertEquals(two, ScheduledTask(rewritten.tags, PrivateTagsInContent.decrypt(rewritten.content, reader, reader.pubKey)))
            assertEquals(two, rewritten.task(reader))
        }

    @Test
    fun otherNip51ListsStillDecryptToSelfOnly() =
        runTest {
            val plain = signed(ScheduledTaskEvent.update(daily, reader, "observer"))
            assertEquals(listOf(reader.pubKey), plain.privateTagsPeers(reader.pubKey))
            assertEquals(emptyList(), plain.privateTagsPeers(assistant.pubKey))
            assertEquals(daily, plain.task(reader))
            assertNull(plain.task(assistant))
        }

    @Test
    fun theRunnerRunsTheLastDueOccurrenceWithoutClaiming() {
        val run = daily.withRunner(assistant.pubKey, 0)
        val me = assistant.pubKey

        fun verdict(
            task: ScheduledTask = run,
            now: Long,
            ownRun: OwnRun = OwnRun.NONE,
            done: Boolean = false,
        ) = ScheduledTaskClaims.decideAsRunner(task, "observer", me, newYork, now, { ownRun }, { done })

        assertEquals(ScheduledTaskClaims.RunnerDecision(RunnerVerdict.RUN, six), verdict(now = six + 60))
        assertTrue(verdict(now = six + 60).shouldRun)
        // A missed one only until the next is due; then the next.
        assertEquals(six, verdict(now = six + day - 1).occurrence)
        assertEquals(six + day, verdict(now = six + day).occurrence)
        assertEquals(RunnerVerdict.ALREADY_DONE, verdict(now = six + 60, done = true).verdict)
        assertEquals(RunnerVerdict.ATTEMPTED_HERE, verdict(now = six + 60, ownRun = OwnRun.ENDED).verdict)
        // A run that started and never ended counts as done: it may have spent the reader's money.
        assertEquals(RunnerVerdict.ATTEMPTED_HERE, verdict(now = six + 60, ownRun = OwnRun.DIED).verdict)
        assertEquals(RunnerVerdict.NOT_RUNNABLE, verdict(run.withEnabled(false, 0), six + 60).verdict)
        assertEquals(RunnerVerdict.NOT_RUNNABLE, verdict(ScheduledTask(run.publicTags, arrayOf(arrayOf("task", "digest"), arrayOf("cron", "0 6 * * *", "UTC"))), six + 60).verdict)
        // Nothing due before the schedule was set, or resumed.
        assertEquals(RunnerVerdict.NOT_DUE, verdict(run.withCron("0 6 * * *", "America/New_York", six + 1), six + 60).verdict)
        assertEquals(RunnerVerdict.NOT_DUE, verdict(run.withEnabled(false, 0).withEnabled(true, six + 1), six + 60).verdict)
        // Another runner's task, or one with none.
        assertEquals(RunnerVerdict.NOT_THIS_RUNNER, verdict(daily.withRunner(stranger.pubKey, 0), six + 60).verdict)
        assertEquals(RunnerVerdict.NOT_THIS_RUNNER, verdict(daily, six + 60).verdict)
        // Deciding never writes a claim.
        assertNull(run.claim)
    }

    @Test
    fun theRunnerRunsOnlyForTheOwnerItServes() =
        runTest {
            val mine = signed(ScheduledTaskEvent.update(daily.withRunner(assistant.pubKey, 0), reader, "observer"))
            assertEquals(RunnerCheck.OK, ScheduledTaskClaims.checkRunner(mine, assistant.pubKey, expectedOwner = reader.pubKey))
            assertTrue(ScheduledTaskClaims.isRunnableBy(mine, assistant.pubKey, reader.pubKey))

            // Someone else tags the runner to spend its owner's key: rejected.
            val foreign = signed(ScheduledTaskEvent.update(daily.withRunner(assistant.pubKey, 0), stranger, "observer"), by = stranger)
            assertNotNull(foreign.task(assistant))
            assertEquals(RunnerCheck.NOT_OWNER, ScheduledTaskClaims.checkRunner(foreign, assistant.pubKey, expectedOwner = reader.pubKey))
            assertFalse(ScheduledTaskClaims.isRunnableBy(foreign, assistant.pubKey, reader.pubKey))

            // The owner's task naming another runner, none, or two.
            assertEquals(RunnerCheck.NOT_THIS_RUNNER, ScheduledTaskClaims.checkRunner(signed(ScheduledTaskEvent.update(daily.withRunner(stranger.pubKey, 0), reader, "observer")), assistant.pubKey, reader.pubKey))
            assertEquals(RunnerCheck.NOT_THIS_RUNNER, ScheduledTaskClaims.checkRunner(signed(ScheduledTaskEvent.update(daily, reader, "observer")), assistant.pubKey, reader.pubKey))
            val two = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", assistant.pubKey), arrayOf("p", stranger.pubKey)), daily.privateTags)
            assertEquals(RunnerCheck.NOT_THIS_RUNNER, ScheduledTaskClaims.checkRunner(signed(ScheduledTaskEvent.update(two, reader, "observer")), assistant.pubKey, reader.pubKey))

            // A forged copy: the owner's pubkey, a tampered body.
            val forged = ScheduledTaskEvent(mine.id, mine.pubKey, mine.createdAt + 1, mine.tags, mine.content, mine.sig)
            assertEquals(RunnerCheck.BAD_SIGNATURE, ScheduledTaskClaims.checkRunner(forged, assistant.pubKey, reader.pubKey))
        }

    @Test
    fun aHandRunTaskWithParamsAndNoCronNeverRunsOnItsOwn() {
        val settingsOnly = ScheduledTask(arrayOf(arrayOf("d", "observer")), arrayOf(arrayOf("task", "observer"), arrayOf("param", "model", "m")))
        assertEquals(Decision.NOT_RUNNABLE, ScheduledTaskClaims.decide(settingsOnly, "observer", six, six + 60))
        assertEquals(RunnerVerdict.NOT_RUNNABLE, ScheduledTaskClaims.decideAsRunner(settingsOnly.withRunner(assistant.pubKey, 0), "observer", assistant.pubKey, newYork, six + 60).verdict)
    }

    /** A signer that records the peers it is asked to decrypt with, and answers through [answer]. */
    private class RecordingSigner(
        private val inner: NostrSignerInternal,
        private val answer: suspend (ciphertext: String, peer: String) -> String = { c, p -> inner.nip44Decrypt(c, p) },
    ) : NostrSigner(inner.pubKey) {
        val peers = mutableListOf<String>()

        override fun isWriteable() = true

        override suspend fun <T : Event> sign(
            createdAt: Long,
            kind: Int,
            tags: Array<Array<String>>,
            content: String,
        ): T = inner.sign(createdAt, kind, tags, content)

        override suspend fun nip04Encrypt(
            plaintext: String,
            toPublicKey: String,
        ) = inner.nip04Encrypt(plaintext, toPublicKey)

        override suspend fun nip04Decrypt(
            ciphertext: String,
            fromPublicKey: String,
        ) = inner.nip04Decrypt(ciphertext, fromPublicKey)

        override suspend fun nip44Encrypt(
            plaintext: String,
            toPublicKey: String,
        ) = inner.nip44Encrypt(plaintext, toPublicKey)

        override suspend fun nip44Decrypt(
            ciphertext: String,
            fromPublicKey: String,
        ): String {
            peers += fromPublicKey
            return answer(ciphertext, fromPublicKey)
        }

        override suspend fun decryptZapEvent(event: ZapRequestEvent) = inner.decryptZapEvent(event)

        override suspend fun signPsbt(psbtHex: String) = inner.signPsbt(psbtHex)

        override suspend fun deriveKey(nonce: String) = inner.deriveKey(nonce)

        override fun hasForegroundSupport() = false
    }

    @Test
    fun clearingTheRunnerAfterItPrintedNeverPrintsThatMorningAgain() {
        // Named last month; a device last printed weeks ago; the runner printed 06:00 today.
        val run = daily.withRan(six - 30 * day).withRunner(assistant.pubKey, six - 30 * day)
        val tenAm = six + 4 * 3_600
        val cleared = run.withRunner(null, tenAm)
        assertEquals(tenAm, cleared.cronSetAt)
        // No device claims the morning the runner printed, and nothing warns about it.
        assertNull(ScheduledTaskClaims.occurrence(cleared, newYork, tenAm + 60))
        assertNull(cleared.missedRun(newYork, tenAm + 60))
        assertNull(cleared.missedRun(newYork, six + day - 1))
        // Tomorrow is the devices' again, and so is its warning.
        assertEquals(six + day, ScheduledTaskClaims.occurrence(cleared, newYork, six + day + 60))
        assertEquals(Decision.CLAIM, ScheduledTaskClaims.decide(cleared, "observer", six + day, six + day + 60))
        assertEquals(six + day, cleared.missedRun(newYork, six + day + 4 * 3_600))
    }

    @Test
    fun namingARunnerWhileADevicePrintsNeverPrintsTwice() {
        val claimed = ScheduledTaskClaims.claimed(daily, six, "phone", "Phone", at = six + 30)
        val named = claimed.withRunner(assistant.pubKey, six + 60)
        assertEquals(six + 60, named.cronSetAt)

        fun verdict(
            task: ScheduledTask,
            now: Long,
        ) = ScheduledTaskClaims.decideAsRunner(task, "observer", assistant.pubKey, newYork, now)
        // The schedule restarted after 06:00: the runner's first morning is tomorrow.
        assertEquals(RunnerVerdict.NOT_DUE, verdict(named, six + 90).verdict)
        assertEquals(ScheduledTaskClaims.RunnerDecision(RunnerVerdict.RUN, six + day), verdict(named, six + day + 60))

        // A writer that added `p` without restarting the schedule: the device's live claim still stops the runner.
        val sloppy = ScheduledTask(claimed.publicTags + arrayOf(arrayOf("p", assistant.pubKey)), claimed.privateTags)
        assertEquals(ScheduledTaskClaims.RunnerDecision(RunnerVerdict.CLAIMED_BY_DEVICE, six), verdict(sloppy, six + 90))
        // Finished by the device: done.
        assertEquals(RunnerVerdict.ALREADY_DONE, verdict(ScheduledTaskClaims.finished(sloppy, six + 300), six + 400).verdict)
        // A claim that went stale (the device died an hour ago) no longer holds it back.
        assertEquals(RunnerVerdict.RUN, verdict(sloppy, six + 30 + ScheduledTaskClaims.STALE_CLAIM_SECONDS).verdict)
        // A claim on another morning is no claim on this one.
        assertEquals(RunnerVerdict.RUN, verdict(sloppy, six + day + 60).verdict)
    }

    @Test
    fun onlyAChangeOfRunnerRestartsTheSchedule() {
        val run = daily.withRunner(assistant.pubKey, 100)
        assertEquals(100, run.cronSetAt)
        assertSame(run, run.withRunner(assistant.pubKey, 200))
        assertSame(daily, daily.withRunner(null, 200))
        assertEquals(200, run.withRunner(stranger.pubKey, 200).cronSetAt)
        assertEquals(200, run.withRunner(null, 200).cronSetAt)
        // Two runners become one: a change.
        val two = ScheduledTask(run.publicTags + arrayOf(arrayOf("p", stranger.pubKey)), run.privateTags)
        assertEquals(300, two.withRunner(assistant.pubKey, 300).cronSetAt)
        // Without a cron there is nothing to restart.
        val settingsOnly = ScheduledTask(arrayOf(arrayOf("d", "observer")), arrayOf(arrayOf("task", "observer")))
        assertNull(settingsOnly.withRunner(assistant.pubKey, 300).cronTag)
        // The zone and the expression stay as they were.
        assertEquals("America/New_York", run.withRunner(null, 300).tz)
        assertEquals("0 6 * * *", run.withRunner(null, 300).cron)
    }

    @Test
    fun aPThatIsntALowercaseKeyOfSomeoneElseStopsDevicesAndNobodyRuns() =
        runTest {
            for (odd in listOf(assistant.pubKey.uppercase(), reader.pubKey, "", "npub1xyz")) {
                val task = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", odd)), daily.privateTags)
                assertTrue(task.namesRunner, odd)
                assertNull(task.runnerFor(reader.pubKey), odd)
                assertEquals(reader.pubKey, task.privateTagsPeer(reader.pubKey), odd)
                assertEquals(Decision.RUNNER_NAMED, ScheduledTaskClaims.decide(task, "observer", six, six + 60), odd)
                assertEquals(RunnerVerdict.NOT_THIS_RUNNER, ScheduledTaskClaims.decideAsRunner(task, "observer", assistant.pubKey, newYork, six + 60).verdict, odd)
                // Written by our own writer: encrypted to self; the author reads it, and no runner runs it.
                val event = signed(ScheduledTaskEvent.update(task, reader, "observer"))
                assertNull(event.runner(), odd)
                assertEquals(task, ScheduledTask(event.tags, PrivateTagsInContent.decrypt(event.content, reader, reader.pubKey)))
                assertEquals(task, event.task(reader))
                assertEquals(RunnerCheck.NOT_THIS_RUNNER, ScheduledTaskClaims.checkRunner(event, assistant.pubKey, reader.pubKey), odd)
            }
            // The author naming itself is no runner either, even to a runner holding the author's key.
            val self = signed(ScheduledTaskEvent.update(ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", reader.pubKey)), daily.privateTags), reader, "observer"))
            assertEquals(RunnerCheck.NOT_THIS_RUNNER, ScheduledTaskClaims.checkRunner(self, reader.pubKey, reader.pubKey))
            // A `p` with no value at all isn't one.
            assertFalse(ScheduledTask(daily.publicTags + arrayOf(arrayOf("p")), daily.privateTags).namesRunner)
        }

    @Test
    fun theAuthorTriesThePeerItsWriterEncryptsToFirst() =
        runTest {
            // One runner: the runner first.
            val one = signed(ScheduledTaskEvent.update(daily.withRunner(assistant.pubKey, 0), reader, "observer"))
            assertEquals(listOf(assistant.pubKey, reader.pubKey), one.privateTagsPeers(reader.pubKey))
            val first = RecordingSigner(reader)
            assertNotNull(one.task(first))
            assertEquals(listOf(assistant.pubKey), first.peers)

            // A conflict: our writer encrypts to self, so self first, and one decryption is enough.
            val two = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", stranger.pubKey), arrayOf("p", assistant.pubKey)), daily.privateTags)
            val conflict = signed(ScheduledTaskEvent.update(two, reader, "observer"))
            assertEquals(listOf(reader.pubKey, stranger.pubKey, assistant.pubKey), conflict.privateTagsPeers(reader.pubKey))
            val second = RecordingSigner(reader)
            assertEquals(two, conflict.task(second))
            assertEquals(listOf(reader.pubKey), second.peers)

            // Another client's conflict, encrypted to a runner: still read, after the misses.
            val theirs = signed(EventTemplate(1_000, ScheduledTaskEvent.KIND, two.publicTags, reader.nip44Encrypt(PrivateTagsInContent.encode(two.privateTags), assistant.pubKey)))
            val third = RecordingSigner(reader)
            assertEquals(two, theirs.task(third))
            assertEquals(listOf(reader.pubKey, stranger.pubKey, assistant.pubKey), third.peers)
        }

    @Test
    fun theAuthorTriesEachKeyOnceWhateverItsCase() =
        runTest {
            val upper = assistant.pubKey.uppercase()
            // Two `p`s for one key in two cases: a conflict, and one key to try besides self.
            val both = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", assistant.pubKey), arrayOf("p", upper)), daily.privateTags)
            val event = signed(ScheduledTaskEvent.update(both, reader, "observer"))
            assertEquals(listOf(reader.pubKey, assistant.pubKey), event.privateTagsPeers(reader.pubKey))
            // An old writer's uppercase `p`, encrypted to that key: read on the second try.
            val old = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", upper)), daily.privateTags)
            val theirs = signed(EventTemplate(1_000, ScheduledTaskEvent.KIND, old.publicTags, reader.nip44Encrypt(PrivateTagsInContent.encode(old.privateTags), assistant.pubKey)))
            assertEquals(listOf(reader.pubKey, assistant.pubKey), theirs.privateTagsPeers(reader.pubKey))
            assertEquals(old, theirs.task(RecordingSigner(reader)))
        }

    @Test
    fun aRejectedDecryptionIsAskedOnceNotOncePerPeer() =
        runTest {
            val two = ScheduledTask(daily.publicTags + arrayOf(arrayOf("p", stranger.pubKey), arrayOf("p", assistant.pubKey)), daily.privateTags)
            val conflict = signed(ScheduledTaskEvent.update(two, reader, "observer"))
            for (answer in listOf<Exception>(
                SignerExceptions.ManuallyUnauthorizedException("rejected"),
                SignerExceptions.TimedOutException("timed out"),
                SignerExceptions.RunningOnBackgroundWithoutAutomaticPermissionException("background"),
            )) {
                val rejecting = RecordingSigner(reader) { _, _ -> throw answer }
                assertSame(answer, assertFailsWith<SignerExceptions> { conflict.decrypt(rejecting) })
                assertEquals(1, rejecting.peers.size, answer::class.simpleName)
                // The null reader asks once too.
                val again = RecordingSigner(reader) { _, _ -> throw answer }
                assertNull(conflict.task(again))
                assertEquals(1, again.peers.size, answer::class.simpleName)
            }
            // A failed decryption is a wrong peer: every peer is tried, and the last failure is thrown.
            val failing = RecordingSigner(reader) { _, _ -> throw SignerExceptions.CouldNotPerformException("bad mac") }
            assertFailsWith<SignerExceptions.CouldNotPerformException> { conflict.decrypt(failing) }
            assertEquals(3, failing.peers.size)
        }
}
