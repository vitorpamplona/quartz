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
package com.vitorpamplona.quartz.nip29RelayGroups.tags

import com.vitorpamplona.quartz.utils.ensure

/**
 * NIP-29 `["supported_kinds", "9", "11", …]`: the kinds a group accepts. Values that are not
 * numbers are skipped; a tag with no values constrains the group to nothing.
 */
class SupportedKindsTag {
    companion object {
        const val TAG_NAME = "supported_kinds"

        fun parse(tag: Array<String>): List<Int>? {
            ensure(tag.isNotEmpty()) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            return tag.drop(1).mapNotNull { it.toIntOrNull() }
        }

        fun assemble(kinds: List<Int>) = (listOf(TAG_NAME) + kinds.map { it.toString() }).toTypedArray()
    }
}
