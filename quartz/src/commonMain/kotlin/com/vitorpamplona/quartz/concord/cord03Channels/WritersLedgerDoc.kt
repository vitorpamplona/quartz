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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.concord.cord04Roles.ConcordStrictJson
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRosterContent
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.schnorr256k1.kotlin.Schnorr256k1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Writers ledger's content entry by entry (CORD-03 §1), the shape the chain rules compare: each
 * edition appends exactly one entry to its predecessor's, verbatim, the oldest moving to [retired] at
 * the cap. A port of Armada's `writers.ts` (itself Vector's `community/v2/writers.rs`, MIT).
 * [ConcordWritersLedger] is what a reader acts on; this is what a fold judges.
 */
internal class WritersLedgerDoc(
    val channel: String,
    val entries: List<Entry>,
    val retired: List<Retired>,
    /** Unknown top-level fields, carried but never compared. */
    val extra: JsonObject,
) {
    /** What an entry says (CORD-03 §1): a writer address, back to the read address, a stop, or a kind a reader doesn't know. */
    sealed class Spell {
        data class Writer(
            val epoch: Long,
            val pk: String,
            val root: Long,
            val by: String,
            val certifier: String?,
        ) : Spell()

        data object Open : Spell()

        data class Stop(
            val writer: String,
        ) : Spell()

        data object Other : Spell()

        /** Whether the entry moves where posts go. */
        val moves: Boolean get() = this is Writer || this == Open
    }

    /** One ledger entry: its date, what it says, and its unknown fields (a stop's `from`, a stray `certifier`). */
    data class Entry(
        val at: Long,
        val spell: Spell,
        val extra: JsonObject,
    ) {
        /** When it takes effect: its own date, or for a stop the departure it records, if earlier. */
        val effectiveAt: Long
            get() {
                if (spell !is Spell.Stop) return at
                val from = u64(extra["from"]) ?: return at
                return minOf(from, at)
            }
    }

    data class Retired(
        val epoch: Long,
        val pk: String,
        val from: Long,
        val to: Long,
        val certifier: String?,
    )

    class Window(
        val epoch: Long,
        val pk: String,
        val from: Long,
        val to: Long?,
        val certifier: String?,
        val root: Long?,
    )

    /** Whether this ledger sits at its Channel's coordinate [entityIdHex] in [communityId] (CORD-03 §1). */
    fun isAt(
        communityId: ByteArray,
        entityIdHex: String,
    ): Boolean = ConcordKeyDerivation.writersCoordinate(communityId, channel.hexToByteArray()).toHexKey() == entityIdHex.lowercase()

    /** Every writer address, retired then live, with when its window opened and closed (null while it holds). */
    fun writerWindows(): List<Window> {
        val out = ArrayList<Window>(retired.size + entries.size)
        for (r in retired) out.add(Window(r.epoch, r.pk, r.from, r.to, r.certifier, null))
        for ((i, e) in entries.withIndex()) {
            val s = e.spell as? Spell.Writer ?: continue
            var to: Long? = null
            for (j in i + 1 until entries.size) {
                if (entries[j].spell.moves) {
                    to = entries[j].at
                    break
                }
            }
            out.add(Window(s.epoch, s.pk, e.at, to, s.certifier, s.root))
        }
        return out
    }

    private fun wellFormed(): Boolean {
        for (i in 1 until entries.size) if (entries[i - 1].at > entries[i].at) return false
        // A stop follows a writer address, never the read address.
        var lastMoving: Spell? = null
        for (e in entries) {
            if (e.spell is Spell.Stop && lastMoving == Spell.Open) return false
            if (e.spell.moves) lastMoving = e.spell
        }
        if (retired.any { it.from > it.to }) return false
        val epochs = writerWindows().map { it.epoch }
        for (i in 1 until epochs.size) if (epochs[i - 1] >= epochs[i]) return false
        return true
    }

    /**
     * This ledger with [entry] appended verbatim; at the cap the oldest entry goes, a writer one into
     * [retired] (CORD-03 §1, Vector `Ledger::with`). Null when that leaves it malformed.
     */
    fun with(entry: Entry): WritersLedgerDoc? {
        val grown = ArrayList(entries).apply { add(entry) }
        val gone = ArrayList(retired)
        if (grown.size > ConcordWritersLedger.MAX_ENTRIES) {
            val oldest = grown.removeAt(0)
            val s = oldest.spell
            if (s is Spell.Writer) {
                // Its window runs to the next entry that moves posts; the live one never retires.
                val to = grown.firstOrNull { it.spell.moves }?.at ?: return null
                gone.add(Retired(s.epoch, s.pk, oldest.at, to, s.certifier))
                if (gone.size > ConcordWritersLedger.MAX_RETIRED) gone.removeAt(0)
            }
        }
        val out = WritersLedgerDoc(channel, grown, gone, extra)
        return out.takeIf { it.wellFormed() && grown.size <= ConcordWritersLedger.MAX_ENTRIES && gone.size <= ConcordWritersLedger.MAX_RETIRED }
    }

    /** The entries and retired windows, the identity [extends] compares (unknown top-level fields aside). */
    private fun sameShape(other: WritersLedgerDoc): Boolean = channel == other.channel && entries == other.entries && retired == other.retired

    /**
     * Whether this ledger is [prev] with exactly one entry appended verbatim, the oldest moving to
     * `retired` at the cap (CORD-03 §1, Armada `ledgerExtends`).
     */
    fun extends(prev: WritersLedgerDoc): Boolean {
        val last = entries.lastOrNull() ?: return false
        val grown = prev.with(last) ?: return false
        return grown.sameShape(this)
    }

    /** The reader's view: windows, stops (each tied to the epochs it applies to) and whether posts go to a writer address. */
    fun toLedger(): ConcordWritersLedger {
        val windows = writerWindows().map { WriterWindow(it.epoch, it.pk, it.from, it.to, it.certifier, it.root) }
        val stops = ArrayList<WriterStop>()
        var current: Long? = null
        var seenMoving = false
        for (e in entries) {
            val s = e.spell
            when {
                s is Spell.Writer -> {
                    current = s.epoch
                    seenMoving = true
                }
                s == Spell.Open -> {
                    current = null
                    seenMoving = true
                }
                s is Spell.Stop -> {
                    // After a writer entry: that epoch alone. Before any moving entry: each retired
                    // window whose bounds hold the stop's date (Armada `stopped`).
                    val epochs =
                        when {
                            current != null -> setOf(current)
                            !seenMoving -> retired.filter { e.at >= it.from && e.at <= it.to }.mapTo(HashSet()) { it.epoch }
                            else -> emptySet()
                        }
                    stops.add(WriterStop(e.at, s.writer, e.effectiveAt, epochs))
                }
                else -> {}
            }
        }
        val restricted = entries.lastOrNull { it.spell.moves }?.spell is Spell.Writer
        return ConcordWritersLedger(channel, windows, stops, restricted)
    }

    companion object {
        private const val MAX_EPOCH = (1L shl 53) - 1

        /** A u64 as serde's `as_u64` reads it: an integer literal (no sign, fraction or exponent) that fits. */
        fun u64(e: JsonElement?): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return null
            val text = p.content
            if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
            return text.toLongOrNull()
        }

        private fun epochOf(e: JsonElement?): Long? = u64(e)?.takeIf { it in 1..MAX_EPOCH }

        private fun stringOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * Parses a ledger, or null when it is malformed (CORD-03 §1). [keys] shares the x-only point
         * checks across a run of parses, such as one fold's ledger chain: a ledger names the same
         * writers in every version, and each check is a point decompression (Vector `KeyCheck`).
         */
        fun parse(
            content: String,
            keys: MutableMap<String, Boolean> = HashMap(),
        ): WritersLedgerDoc? {
            fun isPoint(e: JsonElement?): String? {
                val hex = stringOf(e) ?: return null
                val ok =
                    keys.getOrPut(hex) {
                        RankedRosterContent.isHex64(hex) && Schnorr256k1.pubkeyParse(byteArrayOf(0x02) + hex.hexToByteArray()) != null
                    }
                return if (ok) hex.lowercase() else null
            }

            val top = ConcordStrictJson.parse(content)?.value as? JsonObject ?: return null
            val channel = stringOf(top["channel"])?.takeIf { RankedRosterContent.isHex64(it) } ?: return null
            val ledger = top["ledger"] as? JsonArray ?: return null
            val retired = ArrayList<Retired>()
            if (top.containsKey("retired")) {
                val raw = top["retired"] as? JsonArray ?: return null
                for (r in raw) {
                    val arr = r as? JsonArray ?: return null
                    if (arr.size != 4 && arr.size != 5) return null
                    val epoch = epochOf(arr[0]) ?: return null
                    val pk = isPoint(arr[1]) ?: return null
                    val from = u64(arr[2]) ?: return null
                    val to = u64(arr[3]) ?: return null
                    val certifier = if (arr.size == 5) isPoint(arr[4]) ?: return null else null
                    retired.add(Retired(epoch, pk, from, to, certifier))
                }
            }
            if (ledger.size > ConcordWritersLedger.MAX_ENTRIES || retired.size > ConcordWritersLedger.MAX_RETIRED) return null

            val entries = ArrayList<Entry>(ledger.size)
            for (raw in ledger) {
                val o = raw as? JsonObject ?: return null
                val at = u64(o["at"]) ?: return null
                val has = { k: String -> o.containsKey(k) }
                val writerFields = has("open") || has("epoch") || has("writer") || has("root") || has("by")
                // An entry is classified by the whole set of fields it carries: a field of another kind makes it malformed.
                val spell: Spell
                val known: Set<String>
                when {
                    !writerFields && has("stop") -> {
                        spell = Spell.Stop(isPoint(o["stop"]) ?: return null)
                        known = STOP_FIELDS
                    }
                    !writerFields && !has("stop") -> {
                        spell = Spell.Other
                        known = AT_FIELD
                    }
                    (o["open"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true &&
                        !has("epoch") && !has("writer") && !has("root") && !has("by") && !has("stop") -> {
                        spell = Spell.Open
                        known = OPEN_FIELDS
                    }
                    !has("open") && !has("stop") && has("epoch") && has("writer") && has("root") && has("by") -> {
                        val epoch = epochOf(o["epoch"]) ?: return null
                        val pk = isPoint(o["writer"]) ?: return null
                        val root = u64(o["root"]) ?: return null
                        val by = stringOf(o["by"])?.takeIf { RankedRosterContent.isHex64(it) }?.lowercase() ?: return null
                        val certifier = if (has("certifier")) isPoint(o["certifier"]) ?: return null else null
                        spell = Spell.Writer(epoch, pk, root, by, certifier)
                        known = WRITER_FIELDS
                    }
                    else -> return null
                }
                entries.add(Entry(at, spell, JsonObject(o.filterKeys { it !in known })))
            }
            val extra = JsonObject(top.filterKeys { it != "channel" && it != "ledger" && it != "retired" })
            val doc = WritersLedgerDoc(channel.lowercase(), entries, retired, extra)
            return doc.takeIf { it.wellFormed() }
        }

        private val AT_FIELD = setOf("at")
        private val STOP_FIELDS = setOf("at", "stop")
        private val OPEN_FIELDS = setOf("at", "open")
        private val WRITER_FIELDS = setOf("at", "epoch", "writer", "root", "by", "certifier")
    }
}
