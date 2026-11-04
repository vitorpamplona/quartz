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
package com.vitorpamplona.quartz.nip01Core.relay.client.accessories

import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.DynamicSubscription
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.subscribeAsFlow
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the live subscriptions (`subscribeEvents`, `DynamicSubscription`, `subscribeAsFlow`) against
 * real relays: EOSE, live delivery, the REQ re-sent after a dropped socket (resumed `since`, gift
 * wraps two days earlier), no further REQ after that one's EOSE, and delivery after the reconnect.
 *
 * Skipped unless `RELAY_INTEROP` lists relay urls (comma-separated). Relays also listed in
 * `RELAY_INTEROP_WRITE` get a kind-1 note from a throwaway key before and after the reconnect;
 * the others are only read, and their live traffic is whatever the relay carries.
 * `RELAY_INTEROP_NO_WRAPS` lists relays probed without the gift-wrap filter (relays that refuse
 * gift-wrap reads without AUTH end `subscribeEvents` at once, as designed). `RELAY_INTEROP_TRACE`
 * prints every frame. Gradle doesn't track environment variables: pass `--rerun`.
 *
 * `RELAY_INTEROP=ws://127.0.0.1:7781,wss://nos.lol RELAY_INTEROP_WRITE=ws://127.0.0.1:7781 \
 *   ./gradlew :quartz:jvmTest --tests "*LiveSubscriptionInteropProbe"`
 */
class LiveSubscriptionInteropProbe {
    private val relays = probeEnv("RELAY_INTEROP")
    private val writable = probeEnv("RELAY_INTEROP_WRITE").map { RelayUrlNormalizer.normalize(it) }.toSet()

    /** Relays that refuse gift-wrap reads without AUTH: probed without the gift-wrap filter. */
    private val noWraps = probeEnv("RELAY_INTEROP_NO_WRAPS").map { RelayUrlNormalizer.normalize(it) }.toSet()

    private suspend fun probe(url: String): ProbeReport {
        val report = ProbeReport(url)
        val relay = RelayUrlNormalizer.normalize(url)
        val canWrite = relay in writable
        val http =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val recorder = ProbeRecorder(BasicOkHttpWebSocket.Builder { http })
        val client = NostrClient(recorder, scope)
        val signer = NostrSignerSync()
        // vespa-relay refuses unauthenticated reads without a lens token.
        val lens = if (relay.url.contains("search.brainstorm.world")) "include:spam" else null
        val start = TimeUtils.now()

        fun notes(limit: Int) = Filter(kinds = listOf(1), since = start - 120, limit = limit, search = lens)

        // No `since`: the resume moves it to two days before the caught-up moment.
        val wraps = Filter(kinds = listOf(1059), tags = mapOf("p" to listOf(RandomInstance.bytes(32).toHexKey())), search = lens)

        try {
            val eResume = ProbeResume()
            val eEvents = ConcurrentLinkedQueue<Event>()
            val eEnd = AtomicReference<Throwable?>(null)
            val eJob =
                scope.launch {
                    try {
                        client.subscribeEvents(relay, if (relay in noWraps) listOf(notes(51)) else listOf(notes(51), wraps), eResume).collect { eEvents += it }
                    } catch (e: Throwable) {
                        eEnd.set(e)
                    }
                }
            val dResume = ProbeResume()
            val dEvents = ConcurrentLinkedQueue<Event>()
            val dynamic = DynamicSubscription(client, { mapOf(relay to listOf(notes(52))) }, dResume) { dEvents += it }
            val fList = AtomicReference<List<Event>>(emptyList())
            val fJob = scope.launch { client.subscribeAsFlow(relay, notes(53)).collect { fList.set(it) } }

            val eosed = waitUntil(30_000) { eResume.eoses(relay) > 0 && dResume.eoses(relay) > 0 }
            report.check("EOSE for subscribeEvents and DynamicSubscription", eosed)
            if (!eosed) {
                report.notes += "subscribeEvents ended: ${eEnd.get()?.message}; REQs ${recorder.reqs.size}"
                return report
            }
            report.notes += "stored kind-1 in the last 2 min: subscribeEvents ${eEvents.size}, dynamic ${dEvents.size}, list ${fList.get().size}"

            if (canWrite) {
                val note = signer.sign<Event>(TimeUtils.now(), 1, emptyArray(), "interop probe before")
                report.check("publish before the drop", client.publishAndConfirm(note, setOf(relay)))
                report.check(
                    "live event reaches all three",
                    waitUntil(10_000) { eEvents.any { it.id == note.id } && dEvents.any { it.id == note.id } && fList.get().any { it.id == note.id } },
                )
            } else {
                val before = eEvents.size
                delay(20_000)
                report.notes += "live kind-1 in 20 s before the drop: ${eEvents.size - before}"
            }

            // The newest event each one was handed; with none, the filters go as given.
            val eAt = eResume.caughtUpAt(relay) ?: (start - 120)
            val dAt = dResume.caughtUpAt(relay) ?: (start - 120)
            // Future-dated events (relays hold plenty) were capped at the clock when handed over:
            // they raise the point to at most then, so only the others bound it from below.
            val fAt = fList.get().filter { it.createdAt <= TimeUtils.now() }.maxOfOrNull { it.createdAt } ?: (start - 120)
            val eosesBefore = eResume.eoses(relay) to dResume.eoses(relay)
            recorder.dropAll()
            val resent = waitUntil(60_000) { recorder.reqsOf(51).size >= 2 && recorder.reqsOf(52).size >= 2 && recorder.reqsOf(53).size >= 2 }
            report.check("each subscription's REQ re-sent after the drop", resent)
            if (resent) {
                val e = recorder.reqsOf(51)[1].filters
                val eNotes = e.first { it.kinds == listOf(1) }.since
                val eWraps = e.firstOrNull { it.kinds == listOf(1059) }?.since
                report.check("subscribeEvents resumes from the newest event delivered ($eNotes vs $eAt)", resumedFromDelivered(eNotes, eAt, eEvents, start - 120))
                // Only a delivered event moves the point past the filters' own since; with none,
                // nothing moves and the wrap filter keeps its own.
                report.check("its gift-wrap filter resumes two days earlier", if (relay in noWraps || eEvents.isEmpty()) null else eNotes != null && eWraps == eNotes - TimeUtils.twoDays())
                val d =
                    recorder
                        .reqsOf(52)[1]
                        .filters
                        .single()
                        .since
                report.check("DynamicSubscription resumes from the newest event delivered ($d vs $dAt)", resumedFromDelivered(d, dAt, dEvents, start - 120))
                val f =
                    recorder
                        .reqsOf(53)[1]
                        .filters
                        .single()
                        .since
                report.check("subscribeAsFlow resumes from the newest event delivered ($f vs $fAt)", resumedFromDelivered(f, fAt, fList.get(), start - 120))

                val reEosed = waitUntil(30_000) { eResume.eoses(relay) > eosesBefore.first && dResume.eoses(relay) > eosesBefore.second }
                report.check("EOSE of the resumed REQs", reEosed)
                delay(5_000)
                val counts = listOf(51, 52, 53).map { recorder.reqsOf(it).size }
                report.check("no further REQ after the resumed one's EOSE (REQs per sub: $counts)", counts.all { it == 2 })
            }

            if (canWrite) {
                val note = signer.sign<Event>(TimeUtils.now(), 1, emptyArray(), "interop probe after")
                report.check("publish after the reconnect", client.publishAndConfirm(note, setOf(relay)))
                val got = waitUntil(10_000) { eEvents.any { it.id == note.id } && dEvents.any { it.id == note.id } && fList.get().any { it.id == note.id } }
                val who = "subscribeEvents ${eEvents.any { it.id == note.id }}, dynamic ${dEvents.any { it.id == note.id }}, list ${fList.get().any { it.id == note.id }}"
                report.check("live event after the reconnect reaches all three ($who)", got)
            } else {
                val before = eEvents.size
                delay(20_000)
                report.notes += "live kind-1 in 20 s after the reconnect: ${eEvents.size - before}"
            }
            report.check("subscribeEvents still open (${eEnd.get()?.message ?: "open"})", eEnd.get() == null && eJob.isActive)

            eJob.cancel()
            fJob.cancel()
            dynamic.close()
        } catch (e: Throwable) {
            report.check("no exception (${e::class.simpleName}: ${e.message})", false)
        } finally {
            if (recorder.notices.isNotEmpty()) report.notes += "CLOSED/NOTICE: ${recorder.notices.take(5)}"
            client.disconnect()
            scope.cancel()
        }
        return report
    }

    /**
     * The re-sent `since` is no earlier than the point read before the drop ([atDrop]; a live event
     * can land between that read and the re-send), and is the `created_at` of an event the
     * subscription was actually handed (capped at the clock), or the filter's own [given] `since`
     * when it was handed none.
     */
    private fun resumedFromDelivered(
        since: Long?,
        atDrop: Long,
        delivered: Collection<Event>,
        given: Long,
    ): Boolean {
        if (since == null || since < atDrop || since > TimeUtils.now()) return false
        if (since == given || delivered.any { it.createdAt == since }) return true
        // A future-dated event, capped at the clock when it was handed over.
        return delivered.any { it.createdAt > since } && since > TimeUtils.now() - 120
    }

    @Test
    fun liveSubscriptionsAgainstRealRelays() {
        if (relays.isEmpty()) return
        val reports = runBlocking { relays.map { url -> async(Dispatchers.Default) { probe(url) } }.awaitAll() }
        reports.forEach { println(it) }
        val failed = reports.filter { it.failed.isNotEmpty() }.map { "${it.name}: ${it.failed}" }
        println("SUMMARY ${reports.size - failed.size}/${reports.size} relays passed every check")
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    /** One `subscribeEvents` flow over every writable relay: each relay resumes from its own point. */
    @Test
    fun oneFlowOverSeveralRelays() {
        if (writable.size < 2) return
        runBlocking {
            val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val recorder = ProbeRecorder(BasicOkHttpWebSocket.Builder { http })
            val client = NostrClient(recorder, scope)
            val signer = NostrSignerSync()
            val start = TimeUtils.now()
            val resume = ProbeResume()
            val events = ConcurrentLinkedQueue<Event>()
            val end = AtomicReference<Throwable?>(null)
            val job =
                scope.launch {
                    try {
                        client.subscribeEvents(writable.associateWith { listOf(Filter(kinds = listOf(1), since = start - 60, limit = 54)) }, resume).collect { events += it }
                    } catch (e: Throwable) {
                        end.set(e)
                    }
                }
            try {
                assertTrue(waitUntil(30_000) { writable.all { resume.eoses(it) > 0 } }, "EOSE from every relay; ended: ${end.get()?.message}")
                val before = writable.associateWith { signer.sign<Event>(TimeUtils.now(), 1, emptyArray(), "one flow ${it.url}") }
                before.forEach { (relay, note) -> client.publishAndConfirm(note, setOf(relay)) }
                assertTrue(waitUntil(10_000) { before.values.all { n -> events.any { it.id == n.id } } }, "a note from every relay")

                val dropAt = TimeUtils.now()
                recorder.dropAll()
                assertTrue(waitUntil(60_000) { recorder.reqs.size >= 2 * writable.size }, "every relay's REQ re-sent; got ${recorder.reqs.size}")
                val resumed = recorder.reqs.drop(writable.size).map { it.filters.single().since }
                assertTrue(resumed.all { it != null && it >= dropAt - 60 }, "every relay resumed: $resumed")

                val after = writable.associateWith { signer.sign<Event>(TimeUtils.now(), 1, emptyArray(), "one flow after ${it.url}") }
                after.forEach { (relay, note) -> client.publishAndConfirm(note, setOf(relay)) }
                assertTrue(waitUntil(10_000) { after.values.all { n -> events.any { it.id == n.id } } }, "a note from every relay after the reconnect")
                delay(3_000)
                assertTrue(recorder.reqs.size == 2 * writable.size, "no further REQ; got ${recorder.reqs.size}")
                println("ONE FLOW over ${writable.size} relays: resumed since $resumed, ${events.size} events, still open: ${end.get() == null}")
            } finally {
                job.cancel()
                client.disconnect()
                scope.cancel()
            }
        }
    }
}
