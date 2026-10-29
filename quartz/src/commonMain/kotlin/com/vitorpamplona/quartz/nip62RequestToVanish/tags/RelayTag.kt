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
package com.vitorpamplona.quartz.nip62RequestToVanish.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.utils.ensure

class RelayTag {
    companion object {
        const val TAG_NAME = "relay"
        const val EVERYWHERE = "ALL_RELAYS"

        fun match(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && tag[1].isNotEmpty()

        fun notMatch(tag: Array<String>) = !(tag.has(0) && tag[0] == TAG_NAME)

        fun shouldVanishFromEverywhere(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && tag[1] == EVERYWHERE

        /**
         * Whether this tag asks [relay] to vanish the author. The tag carries whatever url the
         * client typed, so it is compared as a [NormalizedRelayUrl], not as a string: a
         * `wss://relay.example` tag must reach the relay whose normalized url is
         * `wss://relay.example/`. A raw string match quietly accepted the request and deleted
         * nothing.
         */
        fun shouldVanishFrom(
            tag: Array<String>,
            relay: NormalizedRelayUrl,
        ): Boolean {
            if (!tag.has(1) || tag[0] != TAG_NAME) return false
            val value = tag[1]
            // Fast paths first: the everywhere marker, and a tag already in normalized form.
            if (value == EVERYWHERE || value == relay.url) return true
            return RelayUrlNormalizer.normalizeOrNull(value) == relay
        }

        /**
         * The relay this request names, local ones included: a request to vanish from the
         * author's own LAN relay is as valid as any other. Nothing dials these urls on someone
         * else's behalf; an app that does filters with
         * [com.vitorpamplona.quartz.nip01Core.relay.normalizer.isDialableFromOthers] first.
         */
        fun parse(tag: Array<String>): NormalizedRelayUrl? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotEmpty()) { return null }
            // The everywhere marker is not a relay; the lenient parser would make it `ws://all_relays/`.
            ensure(tag[1] != EVERYWHERE) { return null }

            return RelayUrlNormalizer.normalizeOrNull(tag[1])
        }

        fun assemble(relay: NormalizedRelayUrl) = arrayOf(TAG_NAME, relay.url)

        fun assembleEverywhere() = arrayOf(TAG_NAME, EVERYWHERE)
    }
}
