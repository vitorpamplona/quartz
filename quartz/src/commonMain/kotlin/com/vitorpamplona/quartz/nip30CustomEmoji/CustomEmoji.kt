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
package com.vitorpamplona.quartz.nip30CustomEmoji

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

class CustomEmoji {
    companion object {
        val customEmojiPattern: Regex =
            Regex("\\:([A-Za-z0-9_\\-]+)\\:", RegexOption.IGNORE_CASE)

        fun fastMightContainEmoji(
            input: String,
            allTags: Array<Array<String>>?,
        ): Boolean {
            if (allTags == null) return false
            if (allTags.any { it.size > 2 && it[0] == EmojiUrlTag.TAG_NAME }) return true
            return input.contains(":")
        }

        fun fastMightContainEmoji(
            input: String,
            emojiPairs: Map<String, String>,
        ): Boolean {
            if (emojiPairs.isEmpty()) return false
            return input.contains(":")
        }

        fun createEmojiMap(tags: Array<Array<String>>): Map<String, String> {
            val map = HashMap<String, String>()
            tags.fastForEach { if (it.size > 2 && it[0] == EmojiUrlTag.TAG_NAME) map[":${it[1]}:"] = it[2] }
            return map
        }

        fun findAllEmojis(input: String): List<String> {
            val matcher = customEmojiPattern.findAll(input)
            return matcher
                .mapNotNull {
                    it.groups[0]?.value
                }.toList()
        }

        fun findAllEmojiCodes(input: String): List<String> {
            val matcher = customEmojiPattern.findAll(input)
            return matcher
                .mapNotNull {
                    it.groups[1]?.value
                }.toList()
        }

        fun assembleAnnotatedList(
            input: String,
            tags: Array<Array<String>>?,
        ): ImmutableList<Renderable>? {
            if (tags == null || tags.isEmpty()) return null

            val emojiPairs = createEmojiMap(tags)

            return assembleAnnotatedList(input, emojiPairs)
        }

        /**
         * Splits [input] into text runs and the images of the `:code:`s that [emojiPairs] knows
         * (keyed `:code:`), or null when none of them resolves. A code it does not know stays in
         * the text. Codes match as [customEmojiPattern] does, left to right without overlapping,
         * in one pass and with no regex.
         */
        fun assembleAnnotatedList(
            input: String,
            emojiPairs: Map<String, String>,
        ): ImmutableList<Renderable>? {
            if (emojiPairs.isEmpty()) return null
            var finalList: ArrayList<Renderable>? = null
            var textStart = 0
            var open = input.indexOf(':')
            while (open >= 0) {
                var close = open + 1
                while (close < input.length && isEmojiCodeChar(input[close])) close++
                if (close == open + 1 || close >= input.length || input[close] != ':') {
                    // Not a code: the regex would retry from the next colon, which may be [close].
                    open = input.indexOf(':', open + 1)
                    continue
                }
                val url = emojiPairs[input.substring(open, close + 1)]
                if (url != null) {
                    val list = finalList ?: ArrayList<Renderable>().also { finalList = it }
                    if (open > textStart) list.add(TextType(input.substring(textStart, open)))
                    list.add(ImageUrlType(url))
                    textStart = close + 1
                }
                open = input.indexOf(':', close + 1)
            }
            val list = finalList ?: return null
            if (textStart < input.length) list.add(TextType(input.substring(textStart)))
            return list.toImmutableList()
        }

        /** `[A-Za-z0-9_\-]`, the code characters of [customEmojiPattern]. */
        private fun isEmojiCodeChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
    }

    @Immutable open class Renderable

    @Immutable class TextType(
        val text: String,
    ) : Renderable()

    @Immutable class ImageUrlType(
        val url: String,
    ) : Renderable()
}
