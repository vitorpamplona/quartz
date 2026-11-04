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
package com.vitorpamplona.quartz.nip01Core.kite

import java.io.File
import kotlin.test.Test

/** EXPERIMENT: where the byte parser's time goes. Runs only with KITE_CORPUS set. */
class KiteByteReaderBench {
    private inline fun time(
        label: String,
        events: Int,
        rounds: Int,
        body: () -> Long,
    ) {
        var sink = 0L
        repeat(rounds / 2) { sink += body() } // warm-up
        val t0 = System.nanoTime()
        repeat(rounds) { sink += body() }
        val ns = (System.nanoTime() - t0).toDouble() / rounds / events
        println("BENCH %-40s %8.1f ns/event  (%d)".format(label, ns, sink and 1))
    }

    @Test
    fun parse() {
        val path = System.getenv("KITE_CORPUS") ?: return
        val frames = File(path).readLines().filter { it.isNotBlank() }.map { "[\"EVENT\",\"sub-1\",$it]".encodeToByteArray() }
        val texts = frames.map { it.decodeToString() }
        val n = frames.size
        val rounds = 40
        for (pass in 1..2) {
            println("BENCH pass $pass, $n frames, ${frames.sumOf { it.size } / n} bytes avg")
            time("decodeToString only", n, rounds) {
                var s = 0L
                for (f in frames) s += f.decodeToString().length
                s
            }
            time("Kite from String (String given)", n, rounds) {
                var s = 0L
                for (t in texts) s += KiteReader.readMessage(t).hashCode()
                s
            }
            time("decodeToString + Kite (today)", n, rounds) {
                var s = 0L
                for (f in frames) s += KiteReader.readMessage(f.decodeToString()).hashCode()
                s
            }
            time("KiteByteReader from bytes", n, rounds) {
                var s = 0L
                for (f in frames) s += KiteByteReader.readEventMessage(f, f.size).hashCode()
                s
            }
            time("scan bytes for quote/backslash", n, rounds) {
                var s = 0L
                for (f in frames) {
                    for (b in f) if (b == '"'.code.toByte() || b == '\\'.code.toByte()) s++
                }
                s
            }
            time("String.indexOf quote, whole frame", n, rounds) {
                var s = 0L
                for (t in texts) {
                    var k = t.indexOf('"')
                    while (k >= 0) {
                        s++
                        k = t.indexOf('"', k + 1)
                    }
                }
                s
            }
        }
    }
}
