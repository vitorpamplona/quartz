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
package com.vitorpamplona.quartz.nip01Core.store.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Query shapes are client-controlled, so a long-lived relay connection sees
 * one-off SQL fill the cache. A full cache must make room by evicting the
 * least recently used idle SQL, or every shape that shows up later runs
 * uncached forever.
 */
class StatementCacheEvictionTest {
    private fun withCache(block: (StatementCachingConnection) -> Unit) {
        val conn = StatementCachingConnection(BundledSQLiteDriver().open(":memory:"), maxCachedStatements = 2)
        try {
            block(conn)
        } finally {
            conn.close()
        }
    }

    @Test
    fun newSqlEvictsTheLeastRecentlyUsedIdleEntry() =
        withCache { conn ->
            val a = conn.prepare("SELECT 1").also { it.close() }
            conn.prepare("SELECT 2").close()
            conn.prepare("SELECT 1").close() // A is now more recent than B

            val c1 = conn.prepare("SELECT 3").also { it.close() }
            assertSame(c1, conn.prepare("SELECT 3").also { it.close() }, "new SQL is cached once the cache is full")
            assertSame(a, conn.prepare("SELECT 1").also { it.close() }, "the recently used entry survives")
        }

    @Test
    fun checkedOutStatementsAreNeverEvicted() =
        withCache { conn ->
            val a = conn.prepare("SELECT 1")
            val b = conn.prepare("SELECT 2")
            // Nothing idle to evict: the new SQL runs uncached, and A and B stay usable.
            val c1 = conn.prepare("SELECT 3").also { it.close() }
            assertNotSame(c1, conn.prepare("SELECT 3").also { it.close() })
            a.step()
            assertEquals(1, a.getInt(0))
            b.step()
            assertEquals(2, b.getInt(0))
            a.close()
            b.close()
        }
}
