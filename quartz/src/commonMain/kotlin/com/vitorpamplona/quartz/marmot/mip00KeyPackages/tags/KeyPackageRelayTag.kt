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
package com.vitorpamplona.quartz.marmot.mip00KeyPackages.tags

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isLocalHost

/** MIP-00 `["relay", <url>]` of a KeyPackage relay list (kind 10051): one relay this user publishes KeyPackages to. */
class KeyPackageRelayTag {
    companion object {
        const val TAG_NAME = "relay"

        /** The relay, or null when it is malformed or a localhost address. */
        @Deprecated(
            "Drops local relays, which is the app's call: it depends on whose list this is. Use parseUnfiltered and, " +
                "for someone else's list, filter with isDialableFromOthers(). Returns every relay in the next release.",
            ReplaceWith(
                "parseUnfiltered(tag)?.takeIf { it.isDialableFromOthers() }",
                "com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers",
            ),
        )
        fun parse(tag: Array<String>): NormalizedRelayUrl? = parseUnfiltered(tag)?.takeUnless { it.isLocalHost() }

        /** The relay, local ones included, or null when it is malformed. */
        fun parseUnfiltered(tag: Array<String>): NormalizedRelayUrl? {
            if (tag.size < 2 || tag[0] != TAG_NAME || tag[1].isEmpty()) return null
            return RelayUrlNormalizer.normalizeOrNull(tag[1])
        }

        fun assemble(relay: NormalizedRelayUrl) = arrayOf(TAG_NAME, relay.url)
    }
}
