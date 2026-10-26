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
import kotlin.test.assertFails

/**
 * A COMMIT that fails can leave the transaction open (a deferred constraint,
 * or SQLITE_BUSY): the connection must not stay stuck inside it, or every
 * later BEGIN fails with "cannot start a transaction within a transaction".
 */
class TransactionCommitFailureTest {
    @Test
    fun connectionIsUsableAfterAFailedCommit() {
        val db = BundledSQLiteDriver().open(":memory:")
        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE child (parent_id INTEGER REFERENCES parent(id) DEFERRABLE INITIALLY DEFERRED)")

            // The deferred check only runs at COMMIT, so the failure comes from END TRANSACTION.
            assertFails { db.transaction { execSQL("INSERT INTO child VALUES (42)") } }

            db.transaction { execSQL("INSERT INTO parent VALUES (1)") }
            assertEquals(
                1,
                db.prepare("SELECT count(*) FROM parent").use {
                    it.step()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                db.prepare("SELECT count(*) FROM child").use {
                    it.step()
                    it.getInt(0)
                },
            )
        } finally {
            db.close()
        }
    }
}
