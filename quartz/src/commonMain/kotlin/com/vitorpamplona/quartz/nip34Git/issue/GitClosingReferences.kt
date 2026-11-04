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
package com.vitorpamplona.quartz.nip34Git.issue

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote

/**
 * The issues a commit message says it resolves, in gitworkshop's grammar: a line holding one of
 * the verbs close, fix, resolve or implement (any tense: `closes`, `fixed`, `resolving`, …) names
 * the issues written after it on that line, as `#<hex prefix>`, a `note1`/`nevent1` (with or without
 * `nostr:`) or a 64-character hex id.
 *
 * Which issue a prefix means, whether the issue is still open and whether the committer may close
 * it are the caller's: this only reads the message.
 */
object GitClosingReferences {
    /** One issue named on a closing [line]: a full id, or a [isPrefix] of one (`#1a2b3c4d`). */
    class Reference(
        val line: String,
        val id: HexKey,
        val isPrefix: Boolean,
    )

    private val VERB = Regex("\\b(close[sd]?|closing|fix(e[sd])?|fixing|resolve[sd]?|resolving|implement(s|ed|ing)?)\\b", RegexOption.IGNORE_CASE)
    private val REFERENCE = Regex("(?<![0-9a-zA-Z])(?:#([0-9a-fA-F]{8,64})\\b|(?:nostr:)?((?:note1|nevent1)[02-9ac-hj-np-z]+)|([0-9a-fA-F]{64})\\b)")

    fun parse(message: String): List<Reference> {
        val result = ArrayList<Reference>()
        val seen = HashSet<String>()
        message.lineSequence().forEach { raw ->
            val line = raw.trim()
            val verb = VERB.find(line) ?: return@forEach
            REFERENCE.findAll(line, verb.range.last + 1).forEach { match ->
                val prefix = match.groups[1]?.value
                val bech = match.groups[2]?.value
                val hex = match.groups[3]?.value
                val reference =
                    when {
                        prefix != null -> Reference(line, prefix.lowercase(), isPrefix = prefix.length < 64)
                        hex != null -> Reference(line, hex.lowercase(), isPrefix = false)
                        bech != null ->
                            when (val entity = Nip19Parser.uriToRoute(bech)?.entity) {
                                is NNote -> Reference(line, entity.hex, isPrefix = false)
                                is NEvent -> Reference(line, entity.hex, isPrefix = false)
                                else -> null
                            }
                        else -> null
                    }
                if (reference != null && seen.add(reference.id)) result.add(reference)
            }
        }
        return result
    }
}
