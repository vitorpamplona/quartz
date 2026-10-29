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
package com.vitorpamplona.quartz.nip57Zaps.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-57 `anon`: `["anon"]` marks an anonymous zap (signed by a throwaway key); `["anon", ""]`
 * marks a private zap request before PrivateZapRequestBuilder fills in the encrypted copy.
 */
class AnonTag {
    companion object {
        const val TAG_NAME = "anon"

        /** Any `anon` tag: the request's author is a throwaway or derived key, not the sender. */
        fun isTag(tag: Array<String>) = tag.isNotEmpty() && tag[0] == TAG_NAME

        /** A valueless `["anon"]`: an anonymous zap, signed by a throwaway key. */
        fun isAnonymous(tag: Array<String>) = tag.size == 1 && tag[0] == TAG_NAME

        /** A blank `["anon", ""]`: a private zap the signer still has to encrypt. */
        fun isUnsignedPrivate(tag: Array<String>) = tag.size > 1 && tag[0] == TAG_NAME && tag[1].isBlank()

        /** The encrypted request of a signed private zap, or null when [tag] carries none. */
        fun parse(tag: Array<String>): String? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotBlank()) { return null }
            return tag[1]
        }

        fun assembleAnonymous() = arrayOf(TAG_NAME)

        fun assemblePrivate(encryptedRequest: String = "") = arrayOf(TAG_NAME, encryptedRequest)
    }
}
