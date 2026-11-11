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

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [CronZone] over `java.time`'s zone rules. */
class ZoneIdCronZone(
    val zone: ZoneId,
) : CronZone {
    private val rules = zone.rules

    override fun offsetSecondsAt(epochSec: Long): Int = rules.getOffset(Instant.ofEpochSecond(epochSec)).totalSeconds

    companion object {
        /**
         * The zone named [id] (IANA, such as `America/New_York` or `UTC`), or null when this
         * platform doesn't know it, or when it is an offset (`+05:00`, `UTC+5`, `GMT-3`) rather
         * than a name in the IANA tz database, though `java.time` takes those: NIP-FB reads a
         * `cron` in an IANA zone, and anything else is unreadable. `Etc/GMT+5` is a tzdb name.
         */
        fun of(id: String): ZoneIdCronZone? {
            if (offsetLike.containsMatchIn(id)) return null
            val zone =
                try {
                    ZoneId.of(id)
                } catch (e: DateTimeException) {
                    return null
                }
            if (zone is ZoneOffset || ':' in zone.id) return null
            return ZoneIdCronZone(zone)
        }

        /** `+05:00`, `-3`, `UTC+5`, `GMT-03:00`, `UT+1`: offsets, not zone names. */
        private val offsetLike = Regex("^([+-]|(UTC|GMT|UT)[+-])", RegexOption.IGNORE_CASE)

        fun systemDefault() = ZoneIdCronZone(ZoneId.systemDefault())
    }
}
