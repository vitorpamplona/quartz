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
package com.vitorpamplona.quartz.nip5dNapplets.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * One intent a napplet accepts: a queryless convention identity (`napplet:feed/edit`) and the
 * query parameter names it advertises for it (`filters`, `relays`).
 */
@Immutable
data class NappletIntent(
    val intent: String,
    val params: List<String> = emptyList(),
)

/**
 * NIP-5D accepted intent: `["i", "<intent>", "<param>", ...]`. Like `z`, a routing hint from
 * untrusted content, never a grant.
 */
class IntentTag {
    companion object {
        const val TAG_NAME = "i"

        fun isTag(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && tag[1].isNotEmpty()

        fun parse(tag: Array<String>): NappletIntent? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].isNotEmpty()) { return null }
            val params = if (tag.size > 2) tag.copyOfRange(2, tag.size).filter { it.isNotEmpty() } else emptyList()
            return NappletIntent(tag[1], params)
        }

        fun assemble(intent: NappletIntent) = arrayOf(TAG_NAME, intent.intent, *intent.params.toTypedArray())

        fun assemble(intents: List<NappletIntent>) = intents.map { assemble(it) }
    }
}
