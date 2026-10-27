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
package com.vitorpamplona.quartz.experimental.pressstr.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * `["l", "en"]`: a publication's language as an ISO-639-1 code. NIP-32 spells a label with a
 * namespace in position 2; PressStr leaves it out on all three of its kinds. An `l` that names
 * another namespace is someone else's label (a NIP-36 content warning, a genre) and not a
 * language, so only a bare `l` or one in the `ISO-639-1` namespace is read.
 */
class LanguageTag {
    companion object {
        const val TAG_NAME = "l"

        const val NAMESPACE = "ISO-639-1"

        fun isTag(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && tag[1].isNotEmpty() && isLanguageNamespace(tag)

        fun parse(tag: Array<String>): String? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotEmpty()) { return null }
            ensure(isLanguageNamespace(tag)) { return null }
            return tag[1]
        }

        // No namespace (PressStr's spelling), an empty one, or ISO-639-1 itself.
        private fun isLanguageNamespace(tag: Array<String>) = !tag.has(2) || tag[2].isEmpty() || tag[2].equals(NAMESPACE, ignoreCase = true)

        fun assemble(iso639: String) = arrayOf(TAG_NAME, iso639)
    }
}
