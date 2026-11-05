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
package com.vitorpamplona.quartz.nip34Git.review

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.pTag
import com.vitorpamplona.quartz.nip10Notes.BaseNoteEvent
import com.vitorpamplona.quartz.nip10Notes.tags.MarkedETag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.QTag
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Kind 7321 — Pull Request Review, gitworkshop's verdict on a patch or pull request (its NIP.md):
 *
 *     ["e", "<pr or patch id>", "<relay>", "root"], ["p", "<its author>"],
 *     ["s", "ACK" | "NACK" | "Concept ACK" | "Concept NACK" | "Changes Required"],
 *     ["q", "<the reviewer's own kind-1111 comment id>", "<relay>"]...
 *
 * The content is an optional summary. Each reviewer's latest review is their verdict; it carries
 * no authority over merging, which stays the maintainers'.
 */
@Immutable
class GitReviewEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseNoteEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider,
    PubKeyHintProvider {
    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(MarkedETag::parseAsHint) + tags.mapNotNull(QTag::parseEventAsHint)

    /** `ROOT`: the reviewed patch or PR; `QUOTE`: the reviewer's comments it gathers. */
    override fun linkedEventIds(): List<HexKey> = listOfNotNull(reviewedEventId()) + commentIds()

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    /** `ROOT_AUTHOR`: the reviewed item's author. */
    override fun linkedPubKeys(): List<HexKey> = tags.mapNotNull(PTag::parseKey)

    /** The reviewed patch or PR: the `root`-marked `e`, else the first `e`. */
    fun reviewedEventId(): HexKey? = tags.firstNotNullOfOrNull(MarkedETag::parseRootId) ?: tags.firstNotNullOfOrNull(MarkedETag::parseId)

    /** The verdict (`s`), or null when it names none this class knows. */
    fun verdict(): Verdict? = tags.firstNotNullOfOrNull { tag -> if (tag.has(1) && tag[0] == VERDICT_TAG) Verdict.parse(tag[1]) else null }

    /** The reviewer's own comments the review gathers (`q`). */
    fun commentIds(): List<HexKey> = tags.mapNotNull(QTag::parseEventId)

    enum class Verdict(
        val code: String,
    ) {
        ACK("ACK"),
        NACK("NACK"),
        CONCEPT_ACK("Concept ACK"),
        CONCEPT_NACK("Concept NACK"),
        CHANGES_REQUIRED("Changes Required"),
        ;

        companion object {
            fun parse(code: String): Verdict? = entries.firstOrNull { it.code == code }
        }
    }

    companion object {
        const val KIND = 7321
        const val VERDICT_TAG = "s"

        fun build(
            verdict: Verdict,
            summary: String,
            reviewed: EventHintBundle<out Event>,
            comments: List<EventHintBundle<out Event>> = emptyList(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<GitReviewEvent>.() -> Unit = {},
        ) = eventTemplate<GitReviewEvent>(KIND, summary, createdAt) {
            add(MarkedETag.assemble(reviewed.event.id, reviewed.relay, MarkedETag.MARKER.ROOT, reviewed.event.pubKey))
            pTag(reviewed.event.pubKey, reviewed.authorHomeRelay)
            add(arrayOf(VERDICT_TAG, verdict.code))
            comments.forEach { add(QEventTag.assemble(it.event.id, it.relay, it.event.pubKey)) }
            alt("Pull request review: ${verdict.code}")
            initializer()
        }
    }
}
