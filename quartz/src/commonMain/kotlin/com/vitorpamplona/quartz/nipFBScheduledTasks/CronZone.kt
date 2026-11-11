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
package com.vitorpamplona.quartz.nipFBScheduledTasks

/**
 * A time zone as cron needs it: the UTC offset in force at an instant. Quartz carries no zone
 * database, so a platform supplies one (on the JVM and Android, `ZoneIdCronZone` over
 * `java.time`); [UTC] and [fixed] need none.
 */
fun interface CronZone {
    /** The offset from UTC, in seconds, at the instant [epochSec]. */
    fun offsetSecondsAt(epochSec: Long): Int

    /** The wall clock at [epochSec], as seconds since the epoch read as if it were UTC. */
    fun wallSeconds(epochSec: Long): Long = epochSec + offsetSecondsAt(epochSec)

    /** The calendar day [epochSec] falls on in this zone, as days since 1970-01-01. */
    fun dayOf(epochSec: Long): Long = wallSeconds(epochSec).floorDiv(86_400L)

    companion object {
        val UTC = CronZone { 0 }

        fun fixed(offsetSeconds: Int) = CronZone { offsetSeconds }
    }
}
