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
package com.vitorpamplona.quartz.nip01Core.store.conformance

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreConformanceTest {
    private val corpus = ConformanceCorpus.generate(ConformanceCorpus.Config(size = 1_500))

    @Test
    fun theCorpusIsDeterministicAndRunsEveryWritePath() {
        val again = ConformanceCorpus.generate(ConformanceCorpus.Config(size = 1_500))
        assertEquals(corpus.map { it.id }, again.map { it.id })
        assertEquals(1_500, corpus.size)

        val ids = corpus.mapTo(HashSet()) { it.id }
        val kind5s = corpus.filter { it.kind == 5 }
        assertTrue(kind5s.any { d -> d.tags.any { it[0] == "a" } }, "an address deletion")
        assertTrue(kind5s.any { d -> d.tags.any { t -> t[0] == "e" && kind5s.any { it.id == t[1] } } }, "a deletion of a deletion")
        assertTrue(ids.size < corpus.size, "re-offered events")
        assertTrue(corpus.zipWithNext().all { (a, b) -> a === b || a.createdAt != b.createdAt || a.id == b.id }, "distinct timestamps")
    }

    @Test
    fun aStoreAgreesWithItself() =
        runTest {
            val reference = EventStore(null)
            val candidate = EventStore(null)
            StoreConformance.load(reference, corpus)
            StoreConformance.load(candidate, corpus)

            val report = StoreConformance.compare(reference, candidate, StoreConformance.battery(corpus))
            assertTrue(report.ok, report.summary())
            assertTrue(report.checks > 100)
            reference.close()
            candidate.close()
        }

    @Test
    fun aStoreThatIgnoresDeletionsIsCaughtAndTheRulesAreNamed() =
        runTest {
            val reference = EventStore(null)
            val candidate = EventStore(null)
            StoreConformance.load(reference, corpus)
            // The candidate never saw a kind 5: everything a deletion removed is still there.
            StoreConformance.load(candidate, corpus.filter { it.kind != 5 })

            val report = StoreConformance.compare(reference, candidate, StoreConformance.battery(corpus))
            assertFalse(report.ok)
            assertTrue("STORE-D01" in report.suspectRules, report.summary())
            assertTrue(report.mismatches.any { it.kind == StoreConformance.MismatchKind.COUNT && it.check.name == "count all" })
            reference.close()
            candidate.close()
        }

    @Test
    fun anEmptyCorpusHasNoChecks() {
        assertEquals(emptyList(), StoreConformance.battery(emptyList<Event>()).map { it.name })
    }
}
