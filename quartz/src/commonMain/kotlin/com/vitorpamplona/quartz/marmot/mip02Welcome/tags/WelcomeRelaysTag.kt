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
package com.vitorpamplona.quartz.marmot.mip02Welcome.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isLocalHost
import com.vitorpamplona.quartz.utils.ensure

/**
 * Relay list tag for Welcome events.
 * Tells the new member where to find Group Events for this group.
 *
 * Example tag: ["relays", "wss://relay1.com", "wss://relay2.com"]
 */
class WelcomeRelaysTag {
    companion object {
        const val TAG_NAME = "relays"

        /** The relays without local ones, or null when none is left. */
        @Deprecated(
            "Drops local relays, which is the app's call: on a closed network every relay is local. Use parseUnfiltered " +
                "and filter someone else's relays with isDialableFromOthers(). Returns every relay in the next release.",
            ReplaceWith(
                "parseUnfiltered(tag)?.filter { it.isDialableFromOthers() }?.ifEmpty { null }",
                "com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers",
            ),
        )
        fun parse(tag: Array<String>): List<NormalizedRelayUrl>? = parseUnfiltered(tag)?.filterNot { it.isLocalHost() }?.ifEmpty { null }

        /**
         * Every relay the tag names, local ones included, or null when it names no valid relay.
         * The tag comes from someone else's event: filter with [isDialableFromOthers] before
         * dialing, unless the app knows it is on a closed network with the sender.
         */
        fun parseUnfiltered(tag: Array<String>): List<NormalizedRelayUrl>? {
            ensure(tag.has(1) && tag[0] == TAG_NAME) { return null }
            val relays = tag.drop(1).mapNotNull { url -> RelayUrlNormalizer.normalizeOrNull(url) }
            ensure(relays.isNotEmpty()) { return null }
            return relays
        }

        fun assemble(relays: List<NormalizedRelayUrl>) = arrayOf(TAG_NAME, *relays.map { it.url }.toTypedArray())
    }
}
