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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.DynamicSubscription
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.subscribeAsFlow
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Failure scenarios for the live subscriptions against real relays: what the in-process tests
 * can't reproduce. Each scenario runs only when its variable is set, so it skips by default.
 * Gradle doesn't track environment variables: pass `--rerun`.
 *
 * - `RELAY_FAILURES=name@proxied-url@direct-url,…`: relays behind a Toxiproxy proxy called `name`
 *   (`TOXIPROXY_URL`, default `http://127.0.0.1:8474`). The client connects through the proxy; a
 *   second client publishes to the relay directly.
 *   - **cut**: the proxy is disabled while notes are published, then enabled. A note created after
 *     the cut and a gift wrap backdated a day must arrive; a note backdated before the resume point
 *     is reported (it is the documented blind spot).
 *   - **hang**: the connection stops passing data without closing (a `timeout` toxic) for 30 s
 *     while a note is published. The client must notice, reconnect once the hang ends, and get it.
 *   - **restart** (`RELAY_FAILURES_RESTART=docker`, `name` being the container): `docker restart`.
 * - `RELAY_AUTH=url`: a relay that requires NIP-42 AUTH for every read. With a signer the
 *   subscriptions authenticate, receive, and resume after a reconnect; a declining responder and
 *   no responder end `subscribeEvents`.
 * - `RELAY_REFUSE_ALWAYS=name@proxied-url`: a relay that CLOSEs every REQ with `restricted:`.
 *   `subscribeEvents` ends; the callback subscriptions stay, and the pool stops re-sending after
 *   three refusals across reconnects.
 * - `RELAY_REFUSE_TWICE=name@proxied-url`: a relay that refuses only its first two REQs. The
 *   callback subscriptions come back on the next reconnect and receive.
 * - `RELAY_RATE_LIMITED=url`: a relay whose first REQs get `rate-limited:` with a back-off.
 * - `RELAY_LIMITS=url,…`: 25 subscriptions on one connection: how many are served, refused, ignored.
 * - `RELAY_VOLUME=url,…`: 2,000 notes published in a burst while a slow and a fast collector read.
 */
class LiveSubscriptionFailureProbe {
    private data class Target(
        val name: String,
        val proxied: NormalizedRelayUrl,
        val direct: NormalizedRelayUrl?,
    )

    private fun target(spec: String): Target {
        val parts = spec.split('@')
        return Target(parts[0], RelayUrlNormalizer.normalize(parts[1]), parts.getOrNull(2)?.let { RelayUrlNormalizer.normalize(it) })
    }

    private val failures = probeEnv("RELAY_FAILURES").map(::target)
    private val canRestart = System.getenv("RELAY_FAILURES_RESTART") == "docker"
    private val toxiproxy = System.getenv("TOXIPROXY_URL") ?: "http://127.0.0.1:8474"
    private val authRelay = System.getenv("RELAY_AUTH")?.let { RelayUrlNormalizer.normalize(it) }
    private val refuseAlways = System.getenv("RELAY_REFUSE_ALWAYS")?.let(::target)
    private val refuseTwice = System.getenv("RELAY_REFUSE_TWICE")?.let(::target)
    private val rateLimited = System.getenv("RELAY_RATE_LIMITED")?.let { RelayUrlNormalizer.normalize(it) }
    private val limits = probeEnv("RELAY_LIMITS").map { RelayUrlNormalizer.normalize(it) }
    private val volume = probeEnv("RELAY_VOLUME").map { RelayUrlNormalizer.normalize(it) }

    // As a long-running service would configure it (no read timeout on a quiet REQ), with a
    // 10 s ping so a dead connection is noticed within the test's patience.
    private val http =
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    private val admin = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private val signer = NostrSignerSync()

    private fun toxi(
        method: String,
        path: String,
        json: String? = null,
    ) {
        val body = json?.toRequestBody("application/json".toMediaType())
        admin
            .newCall(
                Request
                    .Builder()
                    .url(toxiproxy + path)
                    .method(method, body)
                    .build(),
            ).execute()
            .use { check(it.isSuccessful) { "toxiproxy $method $path: ${it.code} ${it.body?.string()}" } }
    }

    private fun setEnabled(
        proxy: String,
        enabled: Boolean,
    ) = toxi("POST", "/proxies/$proxy", """{"enabled":$enabled}""")

    private fun hang(proxy: String) =
        listOf("downstream", "upstream").forEach {
            toxi("POST", "/proxies/$proxy/toxics", """{"name":"hang_$it","type":"timeout","stream":"$it","attributes":{"timeout":0}}""")
        }

    private fun unhang(proxy: String) = listOf("downstream", "upstream").forEach { runCatching { toxi("DELETE", "/proxies/$proxy/toxics/hang_$it") } }

    private fun note(createdAt: Long = TimeUtils.now()) = signer.sign<Event>(createdAt, 1, emptyArray(), "failure probe ${RandomInstance.randomChars(8)}")

    /** A real NIP-59 wrap (random key, NIP-44 content) to [to], created at [createdAt]. */
    private fun wrap(
        createdAt: Long,
        to: HexKey,
    ) = GiftWrapEvent.create(note(), to, createdAt = createdAt)

    /** A client of its own: one connection per relay, its frames recorded. */
    private inner class Session(
        auth: ((NormalizedRelayUrl) -> Boolean)? = null,
    ) : AutoCloseable {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val recorder = ProbeRecorder(BasicOkHttpWebSocket.Builder { http })
        val client = NostrClient(recorder, scope)

        // A NIP-42 responder: signs when [auth] says so, declines (signs nothing) otherwise.
        private val authenticator =
            auth?.let { shouldSign ->
                RelayAuthenticator(client, scope) { relay, template, _ -> if (shouldSign(relay)) listOf(signer.sign(template)) else emptyList() }
            }

        suspend fun publish(
            event: Event,
            relay: NormalizedRelayUrl,
        ) = client.publishAndConfirm(event, setOf(relay))

        override fun close() {
            client.disconnect()
            scope.cancel()
        }
    }

    /** subscribeEvents (REQ limit 61, plus a gift-wrap filter), DynamicSubscription (62) and subscribeAsFlow (63). */
    private class Three(
        val relay: NormalizedRelayUrl,
        /** Whom the gift-wrap filter asks for: the key the session authenticates with. */
        val me: HexKey,
    ) {
        val eResume = ProbeResume()
        val eEvents = ConcurrentLinkedQueue<Event>()
        val eEnd = AtomicReference<Throwable?>(null)
        var eJob: Job? = null
        val dResume = ProbeResume()
        val dEvents = ConcurrentLinkedQueue<Event>()
        var dynamic: DynamicSubscription? = null
        val fList = AtomicReference<List<Event>>(emptyList())
        var fJob: Job? = null

        fun eosed() = eResume.eoses(relay) > 0 && dResume.eoses(relay) > 0

        fun all(id: String) = eEvents.any { it.id == id } && dEvents.any { it.id == id } && fList.get().any { it.id == id }

        fun who(id: String) = "subscribeEvents ${eEvents.any { it.id == id }}, dynamic ${dEvents.any { it.id == id }}, list ${fList.get().any { it.id == id }}"

        fun close() {
            eJob?.cancel()
            fJob?.cancel()
            dynamic?.close()
        }
    }

    private fun Session.three(relay: NormalizedRelayUrl): Three {
        val t = Three(relay, signer.pubKey)
        val since = TimeUtils.now() - 60
        val wraps = Filter(kinds = listOf(1059), tags = mapOf("p" to listOf(t.me)))
        t.eJob =
            scope.launch {
                try {
                    client.subscribeEvents(relay, listOf(Filter(kinds = listOf(1), since = since, limit = 61), wraps), t.eResume).collect { t.eEvents += it }
                } catch (e: Throwable) {
                    t.eEnd.set(e)
                }
            }
        t.dynamic = DynamicSubscription(client, { mapOf(relay to listOf(Filter(kinds = listOf(1), since = since, limit = 62))) }, t.dResume) { t.dEvents += it }
        t.fJob = scope.launch { client.subscribeAsFlow(relay, Filter(kinds = listOf(1), since = since, limit = 63)).collect { t.fList.set(it) } }
        return t
    }

    private fun counts(s: Session) = listOf(61, 62, 63).map { s.recorder.reqsOf(it).size }

    private fun resentSince(
        s: Session,
        before: List<Int>,
    ) = counts(s).zip(before).all { (now, then) -> now > then }

    /** True when no subscription writes another REQ for 3 s: nothing undoes the resumed one. */
    private suspend fun settles(s: Session): Boolean {
        val first = counts(s)
        delay(3_000)
        return counts(s) == first
    }

    private suspend fun cut(t: Target): ProbeReport {
        val r = ProbeReport("${t.name}: connection cut while notes are published")
        Session(auth = { true }).use { s ->
            Session().use { pub ->
                val subs = s.three(t.proxied)
                try {
                    if (!waitUntil(30_000) { subs.eosed() }.also { r.check("EOSE", it) }) return r
                    // A note before the cut, so there is a resume point.
                    val first = note()
                    pub.publish(first, t.direct!!)
                    r.check("a note before the cut reaches all three", waitUntil(10_000) { subs.all(first.id) })
                    val caughtUp = subs.eResume.caughtUpAt(t.proxied) ?: return r
                    delay(1_000) // a relay that challenges for AUTH has the REQs re-sent once it lands
                    val before = counts(s)
                    val closes = s.recorder.closedAt.size
                    setEnabled(t.name, false)
                    r.check("the client sees the cut", waitUntil(15_000) { s.recorder.closedAt.size > closes })
                    delay(1_000)
                    val after = note()
                    val oldWrap = wrap(TimeUtils.now() - TimeUtils.ONE_DAY, subs.me)
                    val backdated = note(caughtUp - 30)
                    val published = listOf(after, oldWrap, backdated).map { pub.publish(it, t.direct!!) }
                    r.check("publish during the cut: note, wrap backdated a day, note backdated before the resume point ($published)", published.all { it })
                    delay(2_000)
                    setEnabled(t.name, true)
                    r.check("each REQ re-sent", waitUntil(90_000) { resentSince(s, before) })
                    waitUntil(15_000) { subs.all(after.id) }.let { ok -> r.check("the note created during the cut reaches all three (${subs.who(after.id)})", ok) }
                    r.check("the gift wrap backdated a day reaches subscribeEvents", waitUntil(5_000) { subs.eEvents.any { it.id == oldWrap.id } })
                    r.notes += "note backdated before the resume point: " + if (subs.eEvents.any { it.id == backdated.id }) "delivered" else "not delivered (it predates the resumed since: the documented blind spot)"
                    r.check("no further REQ once settled (${counts(s)})", settles(s))
                    r.check("subscribeEvents still open", subs.eEnd.get() == null)
                } finally {
                    runCatching { setEnabled(t.name, true) }
                    subs.close()
                    if (s.recorder.notices.isNotEmpty()) r.notes += "CLOSED/NOTICE: ${s.recorder.notices.take(4)}"
                }
            }
        }
        return r
    }

    private suspend fun hang(t: Target): ProbeReport {
        val r = ProbeReport("${t.name}: connection hangs for 30 s without closing")
        Session(auth = { true }).use { s ->
            Session().use { pub ->
                val subs = s.three(t.proxied)
                try {
                    if (!waitUntil(30_000) { subs.eosed() }.also { r.check("EOSE", it) }) return r
                    delay(1_000)
                    val before = counts(s)
                    val closes = s.recorder.closedAt.size
                    val hungAt = System.currentTimeMillis()
                    hang(t.name)
                    val during = note()
                    r.check("publish during the hang", pub.publish(during, t.direct!!))
                    val noticed = waitUntil(40_000) { s.recorder.closedAt.size > closes }
                    r.check("the client notices the dead connection" + if (noticed) " (after ${(s.recorder.closedAt[closes] - hungAt) / 1000} s)" else "", noticed)
                    delay((30_000 - (System.currentTimeMillis() - hungAt)).coerceAtLeast(0))
                    unhang(t.name)
                    val healedAt = System.currentTimeMillis()
                    val resent = waitUntil(120_000) { resentSince(s, before) }
                    r.check("each REQ re-sent after the hang" + if (resent) " (${(System.currentTimeMillis() - healedAt) / 1000} s after it ended)" else " (${counts(s)})", resent)
                    waitUntil(15_000) { subs.all(during.id) }.let { ok -> r.check("the note published during the hang reaches all three (${subs.who(during.id)})", ok) }
                    r.check("subscribeEvents still open", subs.eEnd.get() == null)
                } finally {
                    unhang(t.name)
                    subs.close()
                    if (s.recorder.notices.isNotEmpty()) r.notes += "CLOSED/NOTICE: ${s.recorder.notices.take(4)}"
                }
            }
        }
        return r
    }

    private suspend fun restart(t: Target): ProbeReport {
        val r = ProbeReport("${t.name}: relay restarted")
        Session(auth = { true }).use { s ->
            Session().use { pub ->
                val subs = s.three(t.proxied)
                try {
                    if (!waitUntil(30_000) { subs.eosed() }.also { r.check("EOSE", it) }) return r
                    delay(1_000)
                    val before = counts(s)
                    val restarted = ProcessBuilder("docker", "restart", t.name).redirectErrorStream(true).start()
                    r.check("docker restart ${t.name}", restarted.waitFor(90, TimeUnit.SECONDS) && restarted.exitValue() == 0)
                    val upAt = System.currentTimeMillis()
                    val resent = waitUntil(120_000) { resentSince(s, before) }
                    r.check("each REQ re-sent" + if (resent) " (${(System.currentTimeMillis() - upAt) / 1000} s after the restart returned)" else " (${counts(s)})", resent)
                    delay(2_000)
                    val after = note()
                    r.check("publish after the restart", pub.publish(after, t.direct!!))
                    waitUntil(15_000) { subs.all(after.id) }.let { ok -> r.check("it reaches all three (${subs.who(after.id)})", ok) }
                    r.check("subscribeEvents still open (${subs.eEnd.get()?.message ?: "open"})", subs.eEnd.get() == null)
                } finally {
                    subs.close()
                    if (s.recorder.notices.isNotEmpty()) r.notes += "CLOSED/NOTICE: ${s.recorder.notices.take(4)}"
                }
            }
        }
        return r
    }

    private suspend fun auth(relay: NormalizedRelayUrl): List<ProbeReport> {
        val signs = ProbeReport("${relay.url}: AUTH required, the client signs")
        Session(auth = { true }).use { s ->
            Session().use { pub ->
                val subs = s.three(relay)
                try {
                    signs.check("EOSE after authenticating", waitUntil(30_000) { subs.eosed() })
                    val first = note()
                    signs.check("publish", pub.publish(first, relay))
                    waitUntil(10_000) { subs.all(first.id) }.let { ok -> signs.check("live note reaches all three (${subs.who(first.id)})", ok) }
                    val caughtUp = subs.eResume.caughtUpAt(relay) ?: 0
                    val before = counts(s)
                    s.recorder.dropAll()
                    signs.check("REQs re-sent after the reconnect, and after its AUTH", waitUntil(60_000) { counts(s).zip(before).all { (now, then) -> now > then } })
                    val lastSince =
                        s.recorder
                            .reqsOf(61)
                            .lastOrNull()
                            ?.filters
                            ?.first { it.kinds == listOf(1) }
                            ?.since
                    signs.check("the last re-sent REQ resumes ($lastSince vs $caughtUp)", lastSince != null && lastSince >= caughtUp)
                    val second = note()
                    signs.check("publish after the reconnect", pub.publish(second, relay))
                    waitUntil(15_000) { subs.all(second.id) }.let { ok -> signs.check("it reaches all three (${subs.who(second.id)})", ok) }
                    signs.check("subscribeEvents still open (${subs.eEnd.get()?.message ?: "open"})", subs.eEnd.get() == null)
                    signs.notes += "CLOSED/NOTICE: ${s.recorder.notices.take(4)}"
                } finally {
                    subs.close()
                }
            }
        }

        suspend fun ends(
            name: String,
            session: Session,
        ): ProbeReport {
            val r = ProbeReport("${relay.url}: AUTH required, $name")
            session.use { s ->
                val started = System.currentTimeMillis()
                val subs = s.three(relay)
                try {
                    val ended = waitUntil(30_000) { subs.eEnd.get() != null }
                    val reason = (subs.eEnd.get() as? SubscriptionClosedException)?.reason
                    r.check("subscribeEvents ends with auth-required ($reason, ${(System.currentTimeMillis() - started) / 1000} s)", ended && reason?.startsWith("auth-required:") == true)
                    delay(3_000)
                    r.notes += "callback subscriptions meanwhile: dynamic ${subs.dEvents.size} events, list ${subs.fList.get().size}, REQs ${counts(s)}"
                } finally {
                    subs.close()
                }
            }
            return r
        }
        return listOf(signs, ends("the responder declines", Session(auth = { false })), ends("no responder", Session()))
    }

    private suspend fun refusesAlways(t: Target): ProbeReport {
        val r = ProbeReport("${t.name}: every REQ refused with restricted:")
        Session().use { s ->
            val subs = s.three(t.proxied)
            try {
                val ended = waitUntil(15_000) { subs.eEnd.get() != null }
                r.check("subscribeEvents ends (${(subs.eEnd.get() as? SubscriptionClosedException)?.reason})", ended && subs.eEnd.get() is SubscriptionClosedException)
                delay(2_000)
                r.check("the callback subscriptions stay open", subs.fJob?.isActive == true)
                val perReconnect = mutableListOf(listOf(62, 63).map { s.recorder.reqsOf(it).size })
                repeat(4) {
                    setEnabled(t.name, false)
                    delay(1_000)
                    setEnabled(t.name, true)
                    delay(15_000)
                    perReconnect += listOf(62, 63).map { s.recorder.reqsOf(it).size }
                }
                r.notes += "REQs (dynamic, list) after each of 4 reconnects: $perReconnect"
                r.check("re-sent on reconnects until 3 refusals, then no more", perReconnect.last().all { it == 3 })
            } finally {
                runCatching { setEnabled(t.name, true) }
                subs.close()
            }
        }
        return r
    }

    private suspend fun refusesTwice(t: Target): ProbeReport {
        val r = ProbeReport("${t.name}: the first two REQs refused, the rest served")
        Session().use { s ->
            Session().use { pub ->
                val dResume = ProbeResume()
                val dEvents = ConcurrentLinkedQueue<Event>()
                val since = TimeUtils.now() - 60
                val dynamic = DynamicSubscription(s.client, { mapOf(t.proxied to listOf(Filter(kinds = listOf(1), since = since, limit = 62))) }, dResume) { dEvents += it }
                val fList = AtomicReference<List<Event>>(emptyList())
                val fJob = s.scope.launch { s.client.subscribeAsFlow(t.proxied, Filter(kinds = listOf(1), since = since, limit = 63)).collect { fList.set(it) } }
                try {
                    r.check("both refused", waitUntil(10_000) { s.recorder.notices.count { it.contains("restricted:") } >= 2 })
                    setEnabled(t.name, false)
                    delay(1_000)
                    setEnabled(t.name, true)
                    r.check("both re-sent on the reconnect and served", waitUntil(60_000) { dResume.eoses(t.proxied) > 0 && listOf(62, 63).all { s.recorder.reqsOf(it).size >= 2 } })
                    delay(1_000)
                    val n = note()
                    r.check("publish", pub.publish(n, t.direct ?: t.proxied))
                    r.check("both receive", waitUntil(10_000) { dEvents.any { it.id == n.id } && fList.get().any { it.id == n.id } })
                } finally {
                    runCatching { setEnabled(t.name, true) }
                    fJob.cancel()
                    dynamic.close()
                }
            }
        }
        return r
    }

    private suspend fun rateLimit(relay: NormalizedRelayUrl): ProbeReport {
        val r = ProbeReport("${relay.url}: first REQs rate-limited with a back-off")
        Session().use { s ->
            Session().use { pub ->
                val started = System.currentTimeMillis()
                val subs = s.three(relay)
                try {
                    val eosed = waitUntil(20_000) { subs.eosed() }
                    r.check("EOSE after the back-off (${(System.currentTimeMillis() - started)} ms)", eosed && System.currentTimeMillis() - started >= 1_000)
                    r.check("subscribeEvents still open", subs.eEnd.get() == null)
                    val n = note()
                    r.check("publish", pub.publish(n, relay))
                    waitUntil(10_000) { subs.all(n.id) }.let { ok -> r.check("it reaches all three (${subs.who(n.id)})", ok) }
                    r.notes += "REQs per subscription: ${counts(s)}; CLOSED: ${s.recorder.notices.size}"
                } finally {
                    subs.close()
                }
            }
        }
        return r
    }

    private suspend fun manySubscriptions(relay: NormalizedRelayUrl): ProbeReport {
        val r = ProbeReport("${relay.url}: 25 subscriptions on one connection")
        Session().use { s ->
            val resumes = (0 until 25).map { ProbeResume() }
            val subs = resumes.mapIndexed { i, resume -> DynamicSubscription(s.client, { mapOf(relay to listOf(Filter(kinds = listOf(1), limit = 100 + i))) }, resume) { } }
            try {
                waitUntil(20_000) { resumes.all { it.eoses(relay) > 0 } }
                delay(2_000)
                val served = resumes.count { it.eoses(relay) > 0 }
                val refusedIds =
                    s.recorder.notices
                        .filter { it.startsWith("[\"CLOSED\"") }
                        .map { it.substringAfter(",").substringAfter("\"").substringBefore("\"") }
                        .toSet()
                val refused = subs.count { it.subId in refusedIds }
                r.notes += "served $served, refused with CLOSED $refused, unanswered ${25 - served - refused}; NOTICE/CLOSED sample: ${s.recorder.notices.take(2)}"
                r.check("every subscription is served or refused, none left unanswered", served + refused == 25)
            } finally {
                subs.forEach { it.close() }
            }
        }
        return r
    }

    /**
     * 2,000 notes in a burst to a slow collector (5 ms per event), a fast one and a callback, all
     * on one connection. With [slowBuffered] the slow one reads through `buffer()`, the remedy the
     * KDoc names; without it the slow one holds the connection, and with it everyone else, back.
     */
    private suspend fun burst(
        relay: NormalizedRelayUrl,
        slowBuffered: Boolean,
    ): ProbeReport {
        val r = ProbeReport("${relay.url}: 2,000 notes in a burst, slow collector ${if (slowBuffered) "buffered" else "unbuffered"}")
        Session().use { s ->
            Session().use { pub ->
                val start = TimeUtils.now()
                val filter = { limit: Int -> listOf(Filter(kinds = listOf(1), since = start, limit = limit)) }
                val got = linkedMapOf("slow" to ConcurrentHashMap.newKeySet<String>(), "fast" to ConcurrentHashMap.newKeySet(), "callback" to ConcurrentHashMap.newKeySet())
                val resumes = List(3) { ProbeResume() }
                val slowFlow = s.client.subscribeEvents(relay, filter(71), resumes[0]).let { if (slowBuffered) it.buffer(Int.MAX_VALUE) else it }
                val jobs =
                    listOf(
                        s.scope.launch {
                            slowFlow.collect {
                                delay(5)
                                got["slow"]!! += it.id
                            }
                        },
                        s.scope.launch { s.client.subscribeEvents(relay, filter(72), resumes[1]).collect { got["fast"]!! += it.id } },
                    )
                val dynamic = DynamicSubscription(s.client, { mapOf(relay to filter(74)) }, resumes[2]) { got["callback"]!! += it.id }
                try {
                    r.check("EOSE", waitUntil(20_000) { resumes.all { it.eoses(relay) > 0 } })
                    val notes = List(2_000) { note() }
                    val accepted = AtomicInteger()
                    val began = System.currentTimeMillis()
                    notes.chunked(100).forEach { chunk -> chunk.map { n -> pub.scope.async { if (pub.publish(n, relay)) accepted.incrementAndGet() } }.awaitAll() }
                    val publishedIn = System.currentTimeMillis() - began
                    r.notes += "relay accepted ${accepted.get()} of 2000 in $publishedIn ms"
                    val ids = notes.map { it.id }.toSet()
                    val target = accepted.get()
                    val done = ConcurrentHashMap<String, Long>()
                    waitUntil(180_000) {
                        got.forEach { (name, set) -> if (set.count { it in ids } >= target) done.putIfAbsent(name, System.currentTimeMillis() - began) }
                        done.size == got.size
                    }
                    r.notes += "ms from the first publish until each had every note: $done"
                    got.forEach { (name, set) -> r.check("$name got every accepted note (${set.count { it in ids }}/$target)", set.count { it in ids } >= target) }
                    if (slowBuffered) {
                        val slowAt = done["slow"] ?: Long.MAX_VALUE
                        r.check("the others are not held back by the slow one", listOf("fast", "callback").all { (done[it] ?: Long.MAX_VALUE) < slowAt - 3_000 })
                    }
                    r.check("no REQ re-sent", listOf(71, 72, 74).all { s.recorder.reqsOf(it).size == 1 })
                } finally {
                    jobs.forEach { it.cancel() }
                    dynamic.close()
                }
            }
        }
        return r
    }

    @Test
    fun failureScenarios() {
        val any = failures.isNotEmpty() || authRelay != null || refuseAlways != null || refuseTwice != null || rateLimited != null || limits.isNotEmpty() || volume.isNotEmpty()
        if (!any) return
        val reports =
            runBlocking {
                buildList {
                    addAll(failures.map { async(Dispatchers.Default) { cut(it) } }.awaitAll())
                    addAll(failures.map { async(Dispatchers.Default) { hang(it) } }.awaitAll())
                    if (canRestart) addAll(failures.map { async(Dispatchers.Default) { restart(it) } }.awaitAll())
                    authRelay?.let { addAll(auth(it)) }
                    refuseAlways?.let { add(refusesAlways(it)) }
                    refuseTwice?.let { add(refusesTwice(it)) }
                    rateLimited?.let { add(rateLimit(it)) }
                    addAll(limits.map { async(Dispatchers.Default) { manySubscriptions(it) } }.awaitAll())
                    volume.forEach {
                        add(burst(it, slowBuffered = false))
                        add(burst(it, slowBuffered = true))
                    }
                }
            }
        reports.forEach { println(it) }
        val failed = reports.filter { it.failed.isNotEmpty() }.map { "${it.name}: ${it.failed}" }
        println("SUMMARY ${reports.size - failed.size}/${reports.size} scenarios passed every check")
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }
}
