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
package com.vitorpamplona.quartz.concord.cord03Channels.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/** The `["timer", <seconds>]` tag of a CORD-08 timer notice: the new disappearing timer, `0` = off. */
class TimerTag {
    companion object {
        const val TAG_NAME = "timer"

        private val DECIMAL = Regex("^(0|[1-9][0-9]*)$")

        fun isTag(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME

        /** The seconds in [tag], or null when it is not a `timer` tag or not a canonical decimal. */
        fun parse(tag: Array<String>): Long? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(DECIMAL.matches(tag[1])) { return null }
            return tag[1].toLongOrNull()
        }

        /** A negative [timerSecs] is written as `0` (off). */
        fun assemble(timerSecs: Long) = arrayOf(TAG_NAME, timerSecs.coerceAtLeast(0).toString())
    }
}
