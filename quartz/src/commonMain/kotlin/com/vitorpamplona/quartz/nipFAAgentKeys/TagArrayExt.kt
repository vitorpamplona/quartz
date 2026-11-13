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
package com.vitorpamplona.quartz.nipFAAgentKeys

import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag

/**
 * Every well-formed `key` tag, in the user's order of preference, those NIP-FA's readers ignore
 * (a URL that breaks its rule) included: what a rewrite reorders and keeps. To use a key, or to
 * say which one is the default, read [usableAgentKeys].
 */
fun TagArray.agentKeys() = mapNotNull(AgentKeyTag::parse)

/**
 * The `key` tags NIP-FA's readers use, in order: the ones with a URL a key may be sent to
 * ([AgentKeyTag.isUrlAllowed]: `https`, or `http` to a loopback host or with no key). The first
 * is the default; a key over plain `http` to another machine is ignored, wherever it stands.
 */
fun TagArray.usableAgentKeys() = mapNotNull { tag -> AgentKeyTag.parse(tag)?.takeIf { it.isUrlAllowed() } }

/** The first key NIP-FA's readers use ([usableAgentKeys]): the default, the one clients send. */
fun TagArray.preferredAgentKey() = fastFirstNotNullOfOrNull { tag -> AgentKeyTag.parse(tag)?.takeIf { it.isUrlAllowed() } }

/** Everything but the `key` tags, kept as-is when the keys are replaced. */
fun TagArray.withoutAgentKeys() = filterNot(AgentKeyTag::isTag).toTypedArray()

/**
 * These private tags with their keys set to [keys], in that order, as NIP-FA's Editing asks:
 * a key already here keeps its original tag array (extra values included), a new one is
 * written fresh, `key` tags this client can't read stay (after the keys), and every other tag
 * stays as it was. Keys not in [keys] are removed.
 */
fun TagArray.withAgentKeys(keys: List<AgentKeyTag>): TagArray {
    val used = BooleanArray(size)
    val result = ArrayList<Array<String>>(size + keys.size)
    keys.forEach { key ->
        val index = indices.firstOrNull { !used[it] && AgentKeyTag.parse(this[it]) == key }
        if (index != null) {
            used[index] = true
            result.add(this[index])
        } else {
            result.add(key.toTagArray())
        }
    }
    forEach { tag -> if (AgentKeyTag.isTag(tag) && AgentKeyTag.parse(tag) == null) result.add(tag) }
    forEach { tag -> if (!AgentKeyTag.isTag(tag)) result.add(tag) }
    return result.toTypedArray()
}
