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

import androidx.compose.runtime.Immutable

/**
 * A NIP-FB `cron`: the five fields of a POSIX crontab line (minute, hour, day of month, month,
 * day of week), read on the wall clock of a [CronZone]. Each field is `*`, a number, a range
 * `a-b`, a step (`*` or a range, then `/n`), or a comma-separated list of these; day of week is `0`–`7`
 * with `0` and `7` Sunday; fields are separated by whitespace. A value out of range, `a` > `b`, a
 * step of 0, names, seconds, `?`, `L`, `W`, `#` and `@` macros make it unreadable: [parse]
 * returns null, and a client that can't read a task's `cron` never runs it. When both day of
 * month and day of week are other than a bare `*` (a stepped `*` counts as restricted), a day matching
 * either runs, as cron does.
 *
 * Daylight saving: a matching time the clocks skip runs at the first instant after the gap (the
 * transition); a time they repeat runs at its first occurrence only.
 */
@Immutable
class CronExpression private constructor(
    val text: String,
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val daysOfMonth: BooleanArray,
    private val months: BooleanArray,
    /** Index 0 = Sunday … 6 = Saturday; a `7` in the text lands on 0. */
    private val daysOfWeek: BooleanArray,
    private val anyDayOfMonth: Boolean,
    private val anyDayOfWeek: Boolean,
) {
    private val minuteList = (0..59).filter { minutes[it] }
    private val hourList = (0..23).filter { hours[it] }

    /** Whether the local date [year]-[month]-[day] (day of week [dayOfWeek], 0 = Sunday) is a day this runs on. */
    fun runsOnDay(
        year: Int,
        month: Int,
        day: Int,
        dayOfWeek: Int,
    ): Boolean {
        if (!months[month]) return false
        val domOk = daysOfMonth[day]
        val dowOk = daysOfWeek[dayOfWeek]
        return when {
            anyDayOfMonth && anyDayOfWeek -> true
            anyDayOfMonth -> dowOk
            anyDayOfWeek -> domOk
            else -> domOk || dowOk
        }
    }

    /** Whether the local wall-clock time [hour]:[minute] on that day matches. */
    fun runsAt(
        hour: Int,
        minute: Int,
    ): Boolean = hours[hour] && minutes[minute]

    /** The first occurrence strictly after [epochSec] in [zone]; null when there is none in the next [SEARCH_DAYS] days. */
    fun nextAfter(
        epochSec: Long,
        zone: CronZone,
    ): Long? {
        val firstDay = Civil.dayOf(epochSec + zone.offsetSecondsAt(epochSec)) - 1
        for (i in 0..SEARCH_DAYS) {
            val day = firstDay + i
            if (!runsOn(day)) continue
            for (hour in hourList) {
                for (minute in minuteList) {
                    val at = resolve(day * DAY + hour * 3_600L + minute * 60L, zone)
                    if (at > epochSec) return at
                }
            }
        }
        return null
    }

    /** The last occurrence at or before [epochSec] in [zone]; null when there is none in the [SEARCH_DAYS] days before. */
    fun lastAtOrBefore(
        epochSec: Long,
        zone: CronZone,
    ): Long? {
        val lastDay = Civil.dayOf(epochSec + zone.offsetSecondsAt(epochSec)) + 1
        for (i in 0..SEARCH_DAYS) {
            val day = lastDay - i
            if (!runsOn(day)) continue
            for (hour in hourList.asReversed()) {
                for (minute in minuteList.asReversed()) {
                    val at = resolve(day * DAY + hour * 3_600L + minute * 60L, zone)
                    if (at <= epochSec) return at
                }
            }
        }
        return null
    }

    private fun runsOn(day: Long): Boolean {
        val (year, month, date) = Civil.fromDay(day)
        return runsOnDay(year, month, date, Civil.dayOfWeek(day))
    }

    override fun equals(other: Any?) = other is CronExpression && other.text == text

    override fun hashCode() = text.hashCode()

    override fun toString() = text

    companion object {
        private const val DAY = 86_400L

        /** How far [CronExpression.nextAfter] looks: past a leap day's eight-year gap (2096 to 2104). */
        const val SEARCH_DAYS = 8 * 366 + 1

        /**
         * The first instant whose wall clock in [zone] reads [local] (seconds since the epoch, as
         * if UTC). Repeated by a fall-back change: the first of the two. Skipped by a
         * spring-forward change: the transition, the first instant after the gap.
         */
        fun resolve(
            local: Long,
            zone: CronZone,
        ): Long {
            val before = zone.offsetSecondsAt(local - 2 * DAY)
            val after = zone.offsetSecondsAt(local + 2 * DAY)
            val early = local - before
            val late = local - after
            val earlyOk = early + zone.offsetSecondsAt(early) == local
            val lateOk = late + zone.offsetSecondsAt(late) == local
            if (earlyOk && lateOk) return minOf(early, late)
            if (earlyOk) return early
            if (lateOk) return late
            // In the gap: the transition lies between the two readings. Find its first second.
            var lo = minOf(early, late)
            var hi = maxOf(early, late)
            val target = zone.offsetSecondsAt(hi)
            // No transition between them (two changes within days of each other): the earlier reading.
            if (zone.offsetSecondsAt(lo) == target) return early
            while (hi - lo > 1) {
                val mid = lo + (hi - lo) / 2
                if (zone.offsetSecondsAt(mid) == target) hi = mid else lo = mid
            }
            return hi
        }

        /** Null when [text] isn't a five-field expression in NIP-FB's subset. */
        fun parse(text: String): CronExpression? {
            val fields = text.trim().split(whitespace)
            if (fields.size != 5) return null
            val minutes = field(fields[0], 0, 59) ?: return null
            val hours = field(fields[1], 0, 23) ?: return null
            val doms = field(fields[2], 1, 31) ?: return null
            val months = field(fields[3], 1, 12) ?: return null
            val dowsRaw = field(fields[4], 0, 7) ?: return null
            val dows = BooleanArray(7) { dowsRaw[it] }
            if (dowsRaw[7]) dows[0] = true
            return CronExpression(fields.joinToString(" "), minutes, hours, doms, months, dows, fields[2] == "*", fields[4] == "*")
        }

        fun isValid(text: String) = parse(text) != null

        // Any number of digits (`001` is 1); the range check below bounds the value.
        private val number = Regex("^\\d{1,9}$")
        private val whitespace = Regex("\\s+")

        /** One field as a membership array indexed by value (size [max] + 1); null when malformed or out of range. */
        private fun field(
            text: String,
            min: Int,
            max: Int,
        ): BooleanArray? {
            val set = BooleanArray(max + 1)
            for (part in text.split(',')) {
                val slash = part.split('/')
                if (slash.size > 2) return null
                val step =
                    if (slash.size == 2) {
                        if (!number.matches(slash[1])) return null
                        slash[1].toInt().takeIf { it >= 1 } ?: return null
                    } else {
                        1
                    }
                val range = slash[0]
                val (from, to) =
                    when {
                        range == "*" -> min to max
                        '-' in range -> {
                            val ends = range.split('-')
                            if (ends.size != 2 || !number.matches(ends[0]) || !number.matches(ends[1])) return null
                            ends[0].toInt() to ends[1].toInt()
                        }
                        number.matches(range) -> {
                            // `a/n` isn't in the subset: a step needs `*` or a range.
                            if (slash.size == 2) return null
                            range.toInt() to range.toInt()
                        }
                        else -> return null
                    }
                if (from < min || to > max || from > to) return null
                for (v in from..to step step) set[v] = true
            }
            return set
        }
    }
}

/**
 * The shape a simple editor shows: one time of day, on every day ([weekdays] null) or on some
 * weekdays (ISO, 1 = Monday … 7 = Sunday). Written as `M H * * <days or *>`.
 */
@Immutable
data class SimpleCron(
    val hour: Int,
    val minute: Int,
    val weekdays: Set<Int>? = null,
) {
    /** The expression; weekdays in cron order (Sunday 0 first), runs of three or more as ranges. */
    fun toCron(): String {
        val days = weekdays?.takeIf { it.isNotEmpty() && it.size < 7 }
        val dow =
            if (days == null) {
                "*"
            } else {
                val cron = days.map { it % 7 }.sorted()
                val parts = mutableListOf<String>()
                var i = 0
                while (i < cron.size) {
                    var j = i
                    while (j + 1 < cron.size && cron[j + 1] == cron[j] + 1) j++
                    if (j - i >= 2) {
                        parts += "${cron[i]}-${cron[j]}"
                    } else {
                        for (k in i..j) parts += cron[k].toString()
                    }
                    i = j + 1
                }
                parts.joinToString(",")
            }
        return "$minute $hour * * $dow"
    }

    companion object {
        private val single = Regex("^\\d{1,9}$")

        /** The editor's shape of [cron], or null when the editor couldn't show it again. */
        fun parse(cron: String): SimpleCron? {
            val expression = CronExpression.parse(cron) ?: return null
            val fields = expression.text.split(' ')
            if (!single.matches(fields[0]) || !single.matches(fields[1])) return null
            if (fields[2] != "*" || fields[3] != "*") return null
            val hour = fields[1].toInt()
            val minute = fields[0].toInt()
            if (fields[4] == "*") return SimpleCron(hour, minute)
            // Only numbers and ranges: a step is a shape the editor wouldn't write back the same.
            if ('/' in fields[4]) return null
            val days = (0..6).filter { dow -> dowSet(fields[4], dow) }
            return SimpleCron(hour, minute, days.map { if (it == 0) 7 else it }.toSet())
        }

        private fun dowSet(
            field: String,
            dow: Int,
        ): Boolean =
            field.split(',').any { part ->
                val ends = part.split('-')
                val from = ends[0].toInt()
                val to = ends.getOrNull(1)?.toInt() ?: from
                (dow in from..to) || (dow == 0 && 7 in from..to)
            }
    }
}

/** Civil-calendar arithmetic on days since the epoch (Howard Hinnant's algorithms), no date library. */
internal object Civil {
    fun dayOf(localSeconds: Long): Long = localSeconds.floorDiv(86_400L)

    /** 0 = Sunday … 6 = Saturday; 1970-01-01 was a Thursday. */
    fun dayOfWeek(day: Long): Int = (day + 4).mod(7L).toInt()

    fun fromDay(day: Long): Triple<Int, Int, Int> {
        val z = day + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val y = yoe + era * 400L + (if (m <= 2L) 1L else 0L)
        return Triple(y.toInt(), m.toInt(), d.toInt())
    }
}
