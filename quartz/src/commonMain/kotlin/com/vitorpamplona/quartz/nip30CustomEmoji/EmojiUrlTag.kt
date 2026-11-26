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
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

@Immutable
data class EmojiUrlTag(
    val code: String,
    val url: String,
    val emojiSet: Address? = null,
) {
    fun encode(): String = ":$code:$url"

    fun toContentEncode(): String = contentEncode(code)

    fun toTagArray() =
        if (emojiSet != null) {
            arrayOf(TAG_NAME, code, url, emojiSet.toValue())
        } else {
            arrayOf(TAG_NAME, code, url)
        }

    companion object {
        const val TAG_NAME = "emoji"

        /**
         * NIP-30: a shortcode MUST be comprised of only alphanumeric characters, hyphens, and
         * underscores. ASCII only, the same characters `:shortcode:` matches in text
         * ([CustomEmoji.customEmojiPattern]), so a code that passes can be emojified.
         */
        fun isValidShortcode(shortcode: String): Boolean {
            if (shortcode.isEmpty()) return false
            for (c in shortcode) {
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-')) return false
            }
            return true
        }

        /**
         * NIP-30's `<image-url>`: an `http(s)://` url, or a `data:image/` uri (about 0.6% of the
         * emoji tags on relays inline their image). Anything else (empty, a bare file name, a
         * leading space, another scheme) is not an image a client can load.
         */
        fun isValidImageUrl(url: String): Boolean =
            (url.length > HTTPS.length && url.startsWith(HTTPS, ignoreCase = true)) ||
                (url.length > HTTP.length && url.startsWith(HTTP, ignoreCase = true)) ||
                (url.length > DATA_IMAGE.length && url.startsWith(DATA_IMAGE, ignoreCase = true))

        private const val HTTPS = "https://"
        private const val HTTP = "http://"
        private const val DATA_IMAGE = "data:image/"

        fun contentEncode(code: String): String = ":$code:"

        /**
         * Reads the `:shortcode:url` form the reaction pickers carry. Returns null unless both the
         * shortcode and the url are present: a bare `:shortcode:` has no image, and turning it
         * into a tag would publish `["emoji", shortcode, ""]`.
         */
        fun decode(encodedEmojiSetup: String): EmojiUrlTag? {
            val emojiParts = encodedEmojiSetup.split(":", limit = 3)
            return if (emojiParts.size > 2 && emojiParts[1].isNotEmpty() && emojiParts[2].isNotBlank()) {
                EmojiUrlTag(emojiParts[1], emojiParts[2])
            } else {
                null
            }
        }

        /** Just the shortcode of an `emoji` tag, without parsing its url or emoji-set address. */
        fun parseCode(tag: Array<String>): String? =
            if (tag.size > 2 && tag[0] == TAG_NAME && tag[1].isNotEmpty()) {
                tag[1]
            } else {
                null
            }

        /**
         * `["emoji", <shortcode>, <image-url>, <emoji-set-address>?]`, or null when the shortcode
         * breaks NIP-30's alphabet ([isValidShortcode]) or the url is not an image url
         * ([isValidImageUrl]). About 1.5% of the emoji tags on relays fail one of them: codes with
         * spaces, `+` or an emoji character, which `:shortcode:` can never reach in text.
         */
        fun parse(tag: Array<String>): EmojiUrlTag? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(isValidShortcode(tag[1])) { return null }
            ensure(isValidImageUrl(tag[2])) { return null }
            return EmojiUrlTag(tag[1], tag[2], tag.getOrNull(3)?.let(Address::parse))
        }
    }
}
