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
package com.vitorpamplona.quartz.nip01Core.relay.commands.toClient

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.cache.ConcurrentHashCache
import com.vitorpamplona.quartz.utils.concurrent.ConcurrentMap
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A [MessageDecoder] that skips the full JSON parse for EVENT frames whose
 * event was already parsed once.
 *
 * Why: duplicates are a large share of real relay traffic — the same event
 * arrives once per matching subscription AND once per connected relay
 * (production measurements: 14–57% duplicate frames; see
 * `quartz/plans/2026-07-02-nostrclient-receiver-perf.md`). Each duplicate
 * costs a full JSON parse (~2µs a frame on the recorded startup capture, more
 * for long events) even though the client already holds the parsed [Event]. This decoder scans the frame for
 * the event id (~0.3µs), and on a cache hit synthesizes the [EventMessage]
 * from the cached [Event] with the *frame's own* subscription id — so every
 * subscription still receives its delivery, per-relay bookkeeping still
 * happens, and dispatch semantics are IDENTICAL to a full parse. Only the
 * redundant parse is skipped.
 *
 * Safety rules — the scan must never mis-attribute a frame, so it bails to a
 * full parse whenever anything is unusual:
 *  - the frame must start exactly with `["EVENT","`;
 *  - the subscription id must contain no escapes;
 *  - the id must be found as the literal `"id":"` key with a 64-lowercase-hex
 *    value. Valid JSON guarantees this substring cannot occur inside a string
 *    value (inner quotes are escaped as `\"`, e.g. kind-6 reposts embedding
 *    full event JSON in `content`), so a match is the top-level id key.
 *
 * The id → Event cache is generational: two lock-free concurrent maps
 * ([ConcurrentHashCache]: `ConcurrentHashMap` on JVM/Android), rotated when
 * the live one reaches [capacity]. Frames arrive from every relay's consumer
 * coroutine concurrently; the hit path is entirely lock-free, and the
 * rotation races are DELIBERATELY tolerated because every one of them is
 * benign — the worst outcome is always a redundant re-parse, never a wrong
 * message:
 *  - two threads rotating at once: one generation of ids is dropped early;
 *  - inserting into a map that just became `previous`: still found by the
 *    two-generation lookup until the next rotation;
 *  - two threads first-seeing the same id simultaneously: both full-parse.
 *
 * Events are identified by id here exactly like in the app-level dedup (LocalCache), and
 * verified downstream, often lazily, only when the app uses them. So the first copy of an id
 * wins, unverified: optimistic, because nearly every duplicate is a genuine copy. When a copy
 * turns out to be forged (its content doesn't hash to its id, or its signature is bad), the
 * app reports it with [reportForged], and two kinds of scrutiny recover from it:
 *
 *  - **The id.** The forged copy stops being handed out, and every later copy of that id is
 *    parsed and verified here until one passes, which then takes its place: ask the relays
 *    for the id again and the genuine copy comes through. A copy whose content hashes to the
 *    id but whose signature failed proves that signature bad for good, so a copy carrying it
 *    again is skipped without parsing. Each id gets [MAX_FORGED_COPIES] bad copies and
 *    [SCRUTINY_SECONDS]; then its copies are skipped until the scrutiny expires.
 *  - **The relays** that sent bad copies: the one whose copy was handed out (the first to
 *    send it), and any whose copy fails here. A bad copy alone doesn't prove a relay altered
 *    it: a client may have published a broken event, which every relay that doesn't verify
 *    what it stores then serves. So a relay is charged only once a valid copy of the same id
 *    arrives, which proves its copy differs; an id that never gets one expires with no one
 *    charged. Even then a relay may only be buggy (escaping, Unicode normalization: the id no
 *    longer matches on events it re-encodes), so charges are counted per relay in an hourly
 *    window, id failures apart from signature failures (an encoding bug leaves the signature
 *    alone; a bad signature on matching content is malice). Only past [verifyOnArrival]'s
 *    threshold (by default 2 bad signatures or 20 bad ids) are the relay's copies verified on
 *    arrival, before they can win the dedup, so its next forgery is caught before anyone uses
 *    it; below it, and for every other relay, copies cost nothing. Each charge is reported to
 *    [onForgery] with the relay's counts: whether and when to stop using a relay is the app's
 *    call ([forgive] clears its record).
 *
 * Without reports this behaves as before: no copy is verified here.
 */
@OptIn(ExperimentalAtomicApi::class)
class CachingEventDecoder(
    /**
     * Ids kept per generation, so up to `2 * capacity` are addressable.
     *
     * Sized from the measured *reuse distance* — how many frames separate two
     * deliveries of the same event — because a duplicate is only caught when its
     * reuse distance still fits in the cache. Against the recorded multi-relay
     * startup capture (150k frames, 82% duplicates, median reuse distance 1,430),
     * the old 2048 caught just 58.3% of duplicates; 8192 catches 80.5% and halves
     * decode time (354ms -> 169ms). Beyond 8192 the curve flattens (32768 buys
     * 96.5% for 4x the entries), so this trades the last 16% for memory the
     * client would otherwise hold on top of `LocalCache`.
     *
     * See `DedupCapacityBenchmark`. Re-measure it before changing this: the right
     * value follows the duplication of real traffic, not intuition.
     */
    private val capacity: Int = 8192,
    private val fullParser: MessageDecoder = MessageDecoder.Default,
) : MessageDecoder {
    @Volatile private var live = ConcurrentHashCache<HexKey, Entry>()

    @Volatile private var previous = ConcurrentHashCache<HexKey, Entry>()

    /**
     * A cached copy and the relay that sent it first, the one every duplicate was handed. A
     * null [event] is a revoked id: reported forged, so a lookup must not fall through to an
     * older generation that may still hold the forged copy.
     */
    private class Entry(
        val event: Event?,
        val relay: NormalizedRelayUrl?,
    )

    /**
     * An id reported forged: the signatures proven bad for it, the bad copies seen, until when,
     * and the relays that sent them (each with whether its copy's id was wrong), charged once a
     * valid copy proves those copies were altered.
     */
    private class IdScrutiny(
        val badSigs: Set<String>,
        val badCopies: Int,
        val untilSeconds: Long,
        val badRelays: Map<NormalizedRelayUrl, Boolean>,
    )

    private val scrutinizedIds = ConcurrentMap<HexKey, IdScrutiny>()

    /** Proven forgeries per relay: the current hour's and the previous hour's. */
    private class Charges(
        val badIds: Int,
        val badSigs: Int,
    ) {
        operator fun plus(other: Charges) = Charges(badIds + other.badIds, badSigs + other.badSigs)
    }

    @Volatile private var chargesNow = ConcurrentMap<NormalizedRelayUrl, Charges>()

    @Volatile private var chargesBefore = ConcurrentMap<NormalizedRelayUrl, Charges>()

    @Volatile private var chargesSince = Long.MIN_VALUE

    /** Relays past [verifyOnArrival]: their copies are verified on arrival. */
    private val suspects = ConcurrentMap<NormalizedRelayUrl, Boolean>()

    /**
     * The (relay, id) pairs charged, so a relay is charged once per event however many forged
     * copies of it it sends, and however a late copy races the scrutiny's end. Charges are rare
     * (forgeries proven); past [MAX_CHARGES_KEPT] it starts over.
     */
    private val charged = ConcurrentMap<String, Boolean>()

    // Read on every frame: kept so clean traffic pays one volatile read, not a map lookup. They
    // only ever turn on (off again only in clearCache): a flag reset racing an add could hide a
    // live scrutiny, while a stale true costs one map lookup per frame.
    @Volatile private var anyScrutinizedId = false

    @Volatile private var anySuspect = false

    /**
     * Called each time a relay is charged with a forged copy, from the thread whose valid copy
     * proved it: the relay, the event id, whether its copy's id was wrong (else only the
     * signature), and the relay's counts in the window. Use it to decide when to stop using a
     * relay.
     */
    @Volatile var onForgery: ((Forgery) -> Unit)? = null

    /**
     * Whether a relay's copies are verified on arrival, given its proven forgeries in the
     * window. Verifying costs a signature check per new event from that relay, so the default
     * waits for a pattern: 2 bad signatures (matching content with a bad signature is malice;
     * one could be a fluke) or 20 bad ids (an id mismatch is usually a relay re-encoding some
     * events, which the per-id recovery already handles). Re-evaluated on each charge and as
     * the window rolls.
     */
    @Volatile var verifyOnArrival: (RelayRecord) -> Boolean = { it.badSigs >= 2 || it.badIds >= 20 }

    /** The clock for scrutiny expiry; tests move it. */
    internal var nowInSeconds: () -> Long = TimeUtils::now

    // Observability + benchmark hooks.
    private val parsed = AtomicLong(0)
    private val reused = AtomicLong(0)
    private val verifiedHere = AtomicLong(0)
    private val skipped = AtomicLong(0)

    val parsedCount: Long get() = parsed.load()
    val reusedCount: Long get() = reused.load()

    /** Copies verified here: those from offending relays and those of scrutinized ids. */
    val verifiedCount: Long get() = verifiedHere.load()

    /** Copies dropped here: forged ones caught, and known-bad ones skipped unparsed. */
    val skippedCount: Long get() = skipped.load()

    /**
     * As [decodeFrom] with no relay to attribute the frame to. A frame dropped as a forged copy
     * fails with [IllegalArgumentException].
     */
    override fun decode(text: String): Message = decodeFrom(text, null) ?: throw IllegalArgumentException("A forged copy, dropped")

    /**
     * Decodes [text], which [relay] sent, or returns null when it is a forged copy to drop: one
     * of an id under scrutiny that failed verification or carries a signature known bad, or one
     * an offending relay sent that failed verification.
     */
    internal fun decodeFrom(
        text: String,
        relay: NormalizedRelayUrl?,
    ): Message? {
        val scanned = scanEventFrame(text)
        if (scanned != null) {
            if (anyScrutinizedId) {
                val scrutiny = activeScrutiny(scanned.eventId)
                if (scrutiny != null) return decodeScrutinized(text, relay, scanned.eventId, scrutiny)
            }
            val cached = lookup(scanned.eventId)
            if (cached != null) {
                reused.incrementAndFetch()
                return EventMessage(scanned.subId, cached)
            }
        }

        val msg = fullParser.decode(text)
        if (msg is EventMessage) {
            val event = msg.event
            if (anyScrutinizedId) {
                // A frame the scan could not read still answers to its id's scrutiny.
                val scrutiny = activeScrutiny(event.id)
                if (scrutiny != null) return checkScrutinized(msg, relay, scrutiny)
            }
            parsed.incrementAndFetch()
            if (relay != null && anySuspect && suspects[relay] != null) {
                verifiedHere.incrementAndFetch()
                if (!event.verify()) {
                    forged(event, relay)
                    return null
                }
            }
            remember(event, relay)
        }
        return msg
    }

    /**
     * The app found [event], handed out here, forged (its [Event.verify] failed). Its id goes
     * under scrutiny, and the relay that sent the copy is returned: it is charged once a valid
     * copy of the id arrives. Null when no relay is known (the copy aged out of the cache, or
     * was decoded without one) or when [event] verifies after all, which changes nothing.
     */
    fun reportForged(event: Event): NormalizedRelayUrl? {
        val idValid = event.verifyId()
        if (idValid && event.verifySignature()) return null
        val entry = live.get(event.id) ?: previous.get(event.id)
        val relay = entry?.relay?.takeIf { entry.event === event }
        // Scrutiny first, so no copy finds the id with neither. Then stop handing the copy out:
        // the revoked entry also hides an older generation's copy once the scrutiny is over.
        scrutinize(event.id, if (idValid) event.sig else null, relay, !idValid)
        live.put(event.id, Entry(null, null))
        return relay
    }

    /** [relay]'s proven forgeries in the window (the current and the previous hour). */
    fun record(relay: NormalizedRelayUrl): RelayRecord {
        rollWindow()
        val c = (chargesNow[relay] ?: NO_CHARGES) + (chargesBefore[relay] ?: NO_CHARGES)
        return RelayRecord(relay, c.badIds, c.badSigs)
    }

    /** Relays whose copies are verified on arrival ([verifyOnArrival]). */
    fun suspectRelays(): Set<NormalizedRelayUrl> {
        rollWindow()
        return suspects.snapshot().keys
    }

    /** Clears [relay]'s record: its copies are trusted again (an event it was charged for stays charged). */
    fun forgive(relay: NormalizedRelayUrl) {
        chargesNow.remove(relay)
        chargesBefore.remove(relay)
        suspects.remove(relay)
    }

    private fun lookup(id: HexKey): Event? {
        val entry = live.get(id) ?: previous.get(id) ?: return null
        return entry.event
    }

    private fun remember(
        event: Event,
        relay: NormalizedRelayUrl?,
    ) {
        if (live.size() >= capacity) {
            // Benign-race rotation: see class kdoc.
            previous = live
            live = ConcurrentHashCache()
        }
        live.put(event.id, Entry(event, relay))
    }

    /** [id]'s scrutiny, or null when it has none or it expired (then it is dropped). */
    private fun activeScrutiny(id: HexKey): IdScrutiny? {
        val scrutiny = scrutinizedIds[id] ?: return null
        if (scrutiny.untilSeconds >= nowInSeconds()) return scrutiny
        scrutinizedIds.remove(id, scrutiny)
        return null
    }

    private fun decodeScrutinized(
        text: String,
        relay: NormalizedRelayUrl?,
        id: HexKey,
        scrutiny: IdScrutiny,
    ): Message? {
        if (scrutiny.badCopies >= MAX_FORGED_COPIES || scanSig(text)?.let { it in scrutiny.badSigs } == true) {
            skipped.incrementAndFetch()
            return null
        }
        val msg = fullParser.decode(text)
        if (msg !is EventMessage || msg.event.id != id) return msg
        return checkScrutinized(msg, relay, scrutiny)
    }

    /** A parsed copy of a scrutinized id: the first that verifies ends the scrutiny and is cached. */
    private fun checkScrutinized(
        msg: EventMessage,
        relay: NormalizedRelayUrl?,
        scrutiny: IdScrutiny,
    ): Message? {
        val event = msg.event
        if (scrutiny.badCopies >= MAX_FORGED_COPIES || event.sig in scrutiny.badSigs) {
            skipped.incrementAndFetch()
            return null
        }
        parsed.incrementAndFetch()
        verifiedHere.incrementAndFetch()
        if (event.verify()) {
            // Cache the valid copy before ending the scrutiny: in between, another relay's copy
            // would find neither, be parsed unverified, and could be cached in its place.
            remember(event, relay)
            // Only the thread that ends the scrutiny charges its relays.
            val ended = scrutinizedIds.remove(event.id)
            ended?.badRelays?.forEach { (bad, badId) -> offended(bad, event.id, badId) }
            return msg
        }
        forged(event, relay)
        return null
    }

    /** A copy that failed verification here: scrutinize its id, note its relay, drop it. */
    private fun forged(
        event: Event,
        relay: NormalizedRelayUrl?,
    ) {
        skipped.incrementAndFetch()
        val idValid = event.verifyId()
        scrutinize(event.id, if (idValid) event.sig else null, relay, !idValid)
    }

    private fun scrutinize(
        id: HexKey,
        badSig: String?,
        relay: NormalizedRelayUrl?,
        badId: Boolean,
    ) {
        val until = nowInSeconds() + SCRUTINY_SECONDS
        val sigs = if (badSig != null) setOf(badSig) else emptySet()
        val relays = if (relay != null) mapOf(relay to badId) else emptyMap()
        if (scrutinizedIds[id] == null && scrutinizedIds.size() >= MAX_SCRUTINIZED_IDS) {
            dropExpiredScrutiny()
            // Still full: a flood of forgeries falls back to plain dedup rather than growing.
            if (scrutinizedIds.size() >= MAX_SCRUTINIZED_IDS) return
        }
        scrutinizedIds.merge(id, IdScrutiny(sigs, 1, until, relays)) { old, new ->
            IdScrutiny(old.badSigs + new.badSigs, old.badCopies + 1, new.untilSeconds, old.badRelays + new.badRelays)
        }
        anyScrutinizedId = true
    }

    private fun offended(
        relay: NormalizedRelayUrl,
        id: HexKey,
        badId: Boolean,
    ) {
        if (charged.size() >= MAX_CHARGES_KEPT) charged.clear()
        if (charged.putIfAbsent(relay.url + " " + id, true) != null) return
        rollWindow()
        chargesNow.merge(relay, if (badId) Charges(1, 0) else Charges(0, 1)) { old, new -> old + new }
        val record = record(relay)
        reassess(record)
        onForgery?.invoke(Forgery(relay, id, badId, record))
    }

    /** Marks or clears [record]'s relay as a suspect. */
    private fun reassess(record: RelayRecord) {
        if (verifyOnArrival(record)) {
            suspects[record.relay] = true
            anySuspect = true
        } else {
            suspects.remove(record.relay)
        }
    }

    /** Starts a new hour of charges when the current one is over, and re-assesses the suspects. */
    private fun rollWindow() {
        val now = nowInSeconds()
        val since = chargesSince
        if (since != Long.MIN_VALUE && now - since < CHARGES_WINDOW_SECONDS) return
        chargesSince = now
        if (since == Long.MIN_VALUE) return
        // Racy like the cache's rotation: two threads rolling at once drop an hour early.
        chargesBefore = if (now - since < 2 * CHARGES_WINDOW_SECONDS) chargesNow else ConcurrentMap()
        chargesNow = ConcurrentMap()
        for (relay in suspects.snapshot().keys) {
            val c = (chargesNow[relay] ?: NO_CHARGES) + (chargesBefore[relay] ?: NO_CHARGES)
            reassess(RelayRecord(relay, c.badIds, c.badSigs))
        }
    }

    private fun dropExpiredScrutiny() {
        val now = nowInSeconds()
        for ((id, scrutiny) in scrutinizedIds.snapshot()) {
            if (scrutiny.untilSeconds < now) scrutinizedIds.remove(id, scrutiny)
        }
    }

    /**
     * Retires the live generation, so entries age out on the caller's clock instead of
     * only when the cache fills.
     *
     * [capacity] bounds what the cache costs *during* a burst; this bounds how long it
     * costs anything *after* one. Without it the maps only rotate inside an insert, so
     * a client receiving a slow trickle keeps everything it saw during the initial
     * burst forever — memory whose dedup value is long gone.
     *
     * An id survives one call and dies on the next, so ticking every 30s keeps nothing
     * older than ~60s. Two consecutive calls with no traffic in between leave the cache
     * empty, which is what releases memory when the client really does go quiet.
     *
     * Racy by the same design as capacity rotation (see class kdoc): retiring a
     * generation concurrently with an insert can only lose an id, and a lost id costs a
     * re-parse, never a wrong message.
     */
    override fun ageOutCache() {
        previous = live
        live = ConcurrentHashCache()
        if (anyScrutinizedId) dropExpiredScrutiny()
        if (anySuspect) rollWindow()
    }

    override fun clearCache() {
        previous = ConcurrentHashCache()
        live = ConcurrentHashCache()
        scrutinizedIds.clear()
        anyScrutinizedId = false
    }

    /** Ids currently addressable across both generations. Observability + tests. */
    val cachedCount: Int get() = live.size() + previous.size()

    private class ScannedEvent(
        val subId: String,
        val eventId: HexKey,
    )

    /**
     * Extracts (subId, eventId) from a `["EVENT","<subId>",{...}]` frame, compact or with JSON
     * whitespace between its tokens (some relays write `"id": "…"`), or null when the frame isn't
     * an EVENT or anything about it is unusual (an escaped subId, a missing or malformed id):
     * null means "do the full parse".
     */
    private fun scanEventFrame(text: String): ScannedEvent? {
        val n = text.length
        var i = skipWs(text, 0)
        if (i >= n || text[i] != '[') return null
        i = skipWs(text, i + 1)
        if (!text.startsWith(EVENT_LABEL, i)) return null
        i = skipWs(text, i + EVENT_LABEL.length)
        if (i >= n || text[i] != ',') return null
        i = skipWs(text, i + 1)
        if (i >= n || text[i] != '"') return null
        i++

        // subId: read up to the closing quote; any escape → bail.
        val subStart = i
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') return null
            if (c == '"') break
            i++
        }
        if (i >= text.length) return null
        val subId = text.substring(subStart, i)

        // id: the first `"id"` key with a 64-char lowercase-hex value.
        val idStart = stringValueOf(text, ID_KEY, i + 1)
        if (idStart < 0) return null
        val idEnd = idStart + 64
        if (idEnd >= text.length || text[idEnd] != '"') return null
        for (j in idStart until idEnd) {
            val c = text[j]
            if (c !in '0'..'9' && c !in 'a'..'f') return null
        }
        return ScannedEvent(subId, text.substring(idStart, idEnd))
    }

    /**
     * A forged copy proven: [relay] sent a copy of [eventId] whose id didn't match its content
     * ([badId]) or whose signature was bad, and [record] holds its counts in the window, this
     * one included.
     */
    class Forgery(
        val relay: NormalizedRelayUrl,
        val eventId: HexKey,
        val badId: Boolean,
        val record: RelayRecord,
    )

    /** [relay]'s proven forgeries in the window: copies whose id didn't match, and copies whose signature was bad. */
    class RelayRecord(
        val relay: NormalizedRelayUrl,
        val badIds: Int,
        val badSigs: Int,
    )

    /** The frame's `sig` value, found like the id: the first `"sig"` key and 128 lowercase-hex chars; null when unusual. */
    private fun scanSig(text: String): String? {
        val start = stringValueOf(text, SIG_KEY, 0)
        if (start < 0) return null
        val end = start + 128
        if (end >= text.length || text[end] != '"') return null
        for (j in start until end) {
            val c = text[j]
            if (c !in '0'..'9' && c !in 'a'..'f') return null
        }
        return text.substring(start, end)
    }

    /**
     * Where the string value of the first [key] (with its quotes, e.g. `"id"`) at or after
     * [from] starts, just past its opening quote, allowing JSON whitespace around the colon; or
     * -1. A string can't hold [key] unescaped, and a string value is never followed by a colon,
     * so the first match followed by `:` is a key.
     */
    private fun stringValueOf(
        text: String,
        key: String,
        from: Int,
    ): Int {
        var k = text.indexOf(key, from)
        while (k >= 0) {
            var j = skipWs(text, k + key.length)
            if (j < text.length && text[j] == ':') {
                j = skipWs(text, j + 1)
                return if (j < text.length && text[j] == '"') j + 1 else -1
            }
            k = text.indexOf(key, k + 1)
        }
        return -1
    }

    private fun skipWs(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
        }
        return i
    }

    companion object {
        private const val EVENT_LABEL = "\"EVENT\""
        private const val ID_KEY = "\"id\""
        private const val SIG_KEY = "\"sig\""

        /** Bad copies an id may bring before the rest are skipped until its scrutiny expires. */
        const val MAX_FORGED_COPIES = 8

        /** How long an id stays under scrutiny after its last bad copy. */
        const val SCRUTINY_SECONDS = 10 * 60L

        /** Ids under scrutiny at once; past it, new reports fall back to plain dedup. */
        const val MAX_SCRUTINIZED_IDS = 1024

        /** (relay, id) charges remembered to charge each once; forgeries are rare, so a few thousand. */
        private const val MAX_CHARGES_KEPT = 4096

        /** A relay's charges count for this hour and the next, then drop out. */
        const val CHARGES_WINDOW_SECONDS = 60 * 60L

        private val NO_CHARGES = Charges(0, 0)
    }
}
