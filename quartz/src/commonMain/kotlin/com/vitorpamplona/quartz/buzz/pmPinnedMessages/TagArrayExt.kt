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
package com.vitorpamplona.quartz.buzz.pmPinnedMessages

import com.vitorpamplona.quartz.buzz.pmPinnedMessages.tags.ClientIdTag
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag

/** The channel a pin command or a pin list is about: the first `h` tag. */
fun TagArray.pinChannel(): String? = firstNotNullOfOrNull(GroupIdTag::parse)

/** The message a pin or unpin command targets: the first `e` tag. */
fun TagArray.pinTarget(): HexKey? = firstNotNullOfOrNull(ETag::parseId)

/** The relay hint of a pin command's target, when its `e` tag carries one. */
fun TagArray.pinTargetHints(): List<EventIdHint> = mapNotNull(ETag::parseAsHint)

/** The command's `client-id` value, or null when absent. */
fun TagArray.pinClientId(): String? = firstNotNullOfOrNull(ClientIdTag::parse)

/** The pinned message ids of a pin list, newest pin first: every `e` tag, in order. */
fun TagArray.pinnedMessageIds(): List<HexKey> = mapNotNull(ETag::parseId)

/** The relay hints a pin list's `e` tags carry. */
fun TagArray.pinnedMessageHints(): List<EventIdHint> = mapNotNull(ETag::parseAsHint)

/**
 * Whether this command has the shape NIP-PM requires: exactly one `h`, exactly one `e`
 * holding a 64-character lowercase hex id. The relay refuses anything else; this lets a
 * client check before it signs or skip a malformed one it reads.
 */
fun TagArray.isWellFormedPinCommand(): Boolean {
    var channels = 0
    var targets = 0
    var target: String? = null
    for (tag in this) {
        if (tag.isEmpty()) continue
        when (tag[0]) {
            GroupIdTag.TAG_NAME -> channels++
            ETag.TAG_NAME -> {
                targets++
                target = tag.getOrNull(1)
            }
        }
    }
    return channels == 1 && targets == 1 && target != null && isLowercaseHexId(target)
}

private fun isLowercaseHexId(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
