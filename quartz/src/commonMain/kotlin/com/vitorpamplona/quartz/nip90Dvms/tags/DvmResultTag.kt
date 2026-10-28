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
package com.vitorpamplona.quartz.nip90Dvms.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * One entry of a NIP-90 discovery or search result. The result list is a JSON tag array carried in
 * `content`: `["e", <id>]` and `["a", <address>]` for content, `["p", <pubkey>]` for people. The
 * values are kept as written, unvalidated.
 */
class DvmResultTag {
    companion object {
        const val EVENT = "e"
        const val ADDRESS = "a"
        const val PUBKEY = "p"

        /** The id or address of an `e` or `a` result. */
        fun parseEventOrAddress(tag: Array<String>): String? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == EVENT || tag[0] == ADDRESS) { return null }
            return tag[1]
        }

        /** The pubkey of a `p` result. */
        fun parsePubKey(tag: Array<String>): String? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == PUBKEY) { return null }
            return tag[1]
        }
    }
}
