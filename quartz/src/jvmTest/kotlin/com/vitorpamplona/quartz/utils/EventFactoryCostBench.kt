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
package com.vitorpamplona.quartz.utils

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import java.io.File
import kotlin.test.Test

/** EXPERIMENT: what building a typed subclass per kind costs per event, against a plain Event. Runs only with KITE_CORPUS set. */
class EventFactoryCostBench {
    @Test
    fun cost() {
        val path = System.getenv("KITE_CORPUS") ?: return
        val t = System.nanoTime()
        val size = EventFactoryTable.size
        println("FACTORY table init: %.1f ms for %d kinds".format((System.nanoTime() - t) / 1e6, size))
        val events = File(path).readLines().filter { it.isNotBlank() }.map { KiteReader.readEvent(it) }
        val n = events.size
        val kinds = events.map { it.kind }.toSet().size
        val classes = events.map { it::class }.toSet().size
        println("FACTORY $n events, $kinds kinds, $classes classes")
        repeat(3) { pass ->
            var sink = 0L
            var t0 = System.nanoTime()
            repeat(50) { for (e in events) sink += EventFactory.create<Event>(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig).kind }
            val typed = (System.nanoTime() - t0) / 50.0 / n
            t0 = System.nanoTime()
            repeat(50) { for (e in events) sink += Event(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig).kind }
            val plain = (System.nanoTime() - t0) / 50.0 / n
            t0 = System.nanoTime()
            repeat(50) { for (e in events) sink += (EventFactory.createWithWhen(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig)?.kind ?: 0) }
            val viaWhen = (System.nanoTime() - t0) / 50.0 / n
            t0 = System.nanoTime()
            repeat(50) { for (e in events) sink += (EventFactoryTable.create(e.id, e.pubKey, e.createdAt, e.kind, e.tags, e.content, e.sig)?.kind ?: 0) }
            val viaTable = (System.nanoTime() - t0) / 50.0 / n
            println("FACTORY pass ${pass + 1}: when parts %.1f ns, table %.1f ns per event".format(viaWhen, viaTable))
            t0 = System.nanoTime()
            repeat(50) { for (e in events) sink += KiteReader.readEvent(e.toJson()).kind }
            val parseWithJson = (System.nanoTime() - t0) / 50.0 / n
            println("FACTORY pass ${pass + 1}: EventFactory.create %.1f ns, plain Event(...) %.1f ns, difference %.1f ns per event (a full toJson+Kite parse: %.0f ns) [%d]".format(typed, plain, typed - plain, parseWithJson, sink and 1))
        }
    }
}
