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
package com.vitorpamplona.quartz.nip01Core.prodbench

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper
import com.vitorpamplona.quartz.nip01Core.core.any
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.tags.kinds.isTaggedKind
import com.vitorpamplona.quartz.nip01Core.tags.references.isTaggedReference
import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import com.vitorpamplona.quartz.nip10Notes.content.findHashtags
import com.vitorpamplona.quartz.nip10Notes.content.findIndexTagsWithPeople
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip30CustomEmoji.CustomEmoji
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * Wall-clock cost of each per-event stage, over the first 20k unique events of the real
 * startup capture: what one event costs to parse, serialize, hash, verify and scan, so a
 * change to any of them can be weighed against the rest (verify is ~95% of ingest CPU).
 *
 * Opt-in: `./gradlew :quartz:jvmTest --tests "*.StageThroughputBenchmark" -PstageBench=1 -i`
 * (`-PstageBench=<text>` runs only the stages whose label contains it). Each stage gets four
 * warm-up passes, then reports the median of seven.
 */
class StageThroughputBenchmark {
    companion object {
        const val CORPUS = "nostr_vitor_startup_data.json.gz"
        const val EVENTS = 20_000

        /** Results parked here so the JIT cannot drop the measured work. */
        var sink: Any? = null
        var sinkCount = 0L
    }

    private val only = System.getProperty("stageBench")

    private fun corpus(): List<Event> {
        val stream =
            javaClass.classLoader?.getResourceAsStream(CORPUS)
                ?: throw IllegalStateException("corpus $CORPUS missing from the test classpath")
        val all = GZIPInputStream(stream).use { OptimizedJsonMapper.fromJsonToEventList(it.readBytes().toString(Charsets.UTF_8)) }
        val seen = HashSet<String>(EVENTS * 2)
        val out = ArrayList<Event>(EVENTS)
        for (e in all) {
            if (out.size >= EVENTS) break
            if (seen.add(e.id)) out.add(e)
        }
        return out
    }

    private fun stage(
        label: String,
        n: Int,
        block: (Int) -> Unit,
    ) {
        if (only != "1" && !label.contains(only!!)) return
        repeat(4) { for (i in 0 until n) block(i) }
        val samples = LongArray(7)
        for (r in samples.indices) {
            val t0 = System.nanoTime()
            for (i in 0 until n) block(i)
            samples[r] = System.nanoTime() - t0
        }
        samples.sort()
        val perEvent = samples[samples.size / 2].toDouble() / n
        println("  %-32s %9.0f ns/event %11.0f events/s".format(label, perEvent, 1e9 / perEvent))
    }

    @Test
    fun perEventStages() {
        if (only == null) return
        val events = corpus()
        val json = events.map { it.toJson() }
        val frames = json.mapIndexed { i, j -> "[\"EVENT\",\"sub${i % 7}\",$j]" }
        val n = events.size
        val emojiMap = mapOf(":soapbox:" to "http://soapbox", ":wave:" to "http://wave")
        println("\n=== ${events.size} events, mean ${json.sumOf { it.length } / n} chars ===")

        stage("frame -> Message", n) { sink = Message.fromJson(frames[it]) }
        stage("json -> Event", n) { sink = OptimizedJsonMapper.fromJson(json[it]) }
        stage("Event -> json", n) { sink = events[it].toJson() }
        stage("id hash", n) {
            val e = events[it]
            sink = EventHasher.hashIdBytes(e.pubKey, e.createdAt, e.kind, e.tags, e.content)
        }
        stage("verify()", n) { if (events[it].verify()) sinkCount++ }
        stage("findHashtags", n) { sink = findHashtags(events[it].content) }
        stage("findIndexTagsWithPeople", n) { sink = findIndexTagsWithPeople(events[it].content, events[it].tags) }
        stage("Nip19Parser.parseAll", n) { sink = Nip19Parser.parseAll(events[it].content) }
        stage("CustomEmoji.assembleAnnotatedList", n) { sink = CustomEmoji.assembleAnnotatedList(events[it].content, emojiMap) }
        stage("tags.any(ContactTag::isTagged)", n) { if (events[it].tags.any(ContactTag::isTagged, "abcd")) sinkCount++ }
        stage("isTaggedReference", n) { if (events[it].tags.isTaggedReference("https://x.com")) sinkCount++ }
        stage("isTaggedKind", n) { if (events[it].tags.isTaggedKind(30023)) sinkCount++ }
    }
}
