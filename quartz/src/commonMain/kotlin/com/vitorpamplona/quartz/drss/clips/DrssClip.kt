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
package com.vitorpamplona.quartz.drss.clips

import com.vitorpamplona.quartz.drss.clips.tags.ClipTimeTag
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip84Highlights.HighlightEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * An audio clip of a podcast episode, as drss.io's reader cuts them: a NIP-84 highlight (kind
 * 9802) of the episode article (`a`, its author as `p`) whose quote is the clip's words, when
 * known, with the listener's note as the `comment` and the clip's range as [ClipTimeTag]s.
 *
 * drss builds its clips on its server and none were public when this was written, so the range's
 * tag names follow the fields its reader sends (`start_ms`, `end_ms`) rather than a seen event.
 */
object DrssClip {
    fun build(
        episode: ATag,
        episodeAuthor: HexKey,
        startMillis: Long,
        endMillis: Long,
        text: String = "",
        note: String? = null,
        createdAt: Long = TimeUtils.now(),
    ): EventTemplate<HighlightEvent> {
        require(endMillis > startMillis && startMillis >= 0) { "a clip runs forward from zero or later" }
        return HighlightEvent.build(
            quote = text,
            comment = note?.ifBlank { null },
            author = episodeAuthor,
            createdAt = createdAt,
        ) {
            // Here rather than as build's address, which leaves out the relay the episode is on.
            add(episode.toATagArray())
            add(KindTag.assemble(episode.kind))
            add(ClipTimeTag.assemble(ClipTimeTag.START, startMillis))
            add(ClipTimeTag.assemble(ClipTimeTag.END, endMillis))
        }
    }

    /** True when [tags] carry a clip's range. */
    fun isClip(tags: TagArray) = clipRange(tags) != null

    /** The clip's range in milliseconds, or null when [tags] are not a clip's. */
    fun clipRange(tags: TagArray): LongRange? {
        val start = tags.fastFirstNotNullOfOrNull(ClipTimeTag::parseStart) ?: return null
        val end = tags.fastFirstNotNullOfOrNull(ClipTimeTag::parseEnd) ?: return null
        return if (end > start) start..end else null
    }
}

/** The audio range this highlight clips, in milliseconds, when it is one of drss's clips. */
fun HighlightEvent.clipRangeMillis(): LongRange? = DrssClip.clipRange(tags)
