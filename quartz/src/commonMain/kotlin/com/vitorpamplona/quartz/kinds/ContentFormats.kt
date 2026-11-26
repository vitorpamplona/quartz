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
package com.vitorpamplona.quartz.kinds

import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * The [ContentFormat] of every kind [com.vitorpamplona.quartz.utils.EventFactory] builds a
 * [FormattedContentEvent] for, for code that holds a kind and no event (a filter, a feed
 * definition, a renderer registry keyed by kind).
 *
 * Written out for the same reason as [com.vitorpamplona.quartz.nip50Search.SearchableKinds]:
 * common code cannot walk the factory's `when`. `ContentFormatsTest` builds every kind from 0 to
 * 65535 and asserts this map is exactly what the classes declare.
 */
object ContentFormats {
    /** Kind to format, sorted by kind. */
    val KINDS: Map<Int, ContentFormat> =
        linkedMapOf(
            1 to ContentFormat.PLAIN_TEXT, // TextNoteEvent
            14 to ContentFormat.PLAIN_TEXT, // ChatMessageEvent
            24 to ContentFormat.PLAIN_TEXT, // PublicMessageEvent
            54 to ContentFormat.MARKDOWN, // PodcastEpisodeEvent
            1111 to ContentFormat.PLAIN_TEXT, // CommentEvent
            1618 to ContentFormat.MARKDOWN, // GitPullRequestEvent
            1621 to ContentFormat.MARKDOWN, // GitIssueEvent
            1624 to ContentFormat.MARKDOWN, // GitCoverNoteEvent
            1630 to ContentFormat.MARKDOWN, // GitStatusOpenEvent
            1631 to ContentFormat.MARKDOWN, // GitStatusAppliedEvent
            1632 to ContentFormat.MARKDOWN, // GitStatusClosedEvent
            1633 to ContentFormat.MARKDOWN, // GitStatusDraftEvent
            30023 to ContentFormat.MARKDOWN, // LongFormContentEvent
            30024 to ContentFormat.MARKDOWN, // LongFormDraftEvent
            30041 to ContentFormat.ASCIIDOC, // PublicationContentEvent
            30063 to ContentFormat.MARKDOWN, // ReleaseArtifactSetEvent
            30402 to ContentFormat.MARKDOWN, // ClassifiedsEvent
            30818 to ContentFormat.DJOT, // WikiArticleEvent
            33953 to ContentFormat.MARKDOWN, // EbookEvent
        )

    /** The format the kind's NIP names, or null when it names none. */
    fun of(kind: Int): ContentFormat? = KINDS[kind]

    /** The format [event] declares, or null when its kind names none. */
    fun of(event: Event): ContentFormat? = (event as? FormattedContentEvent)?.contentFormat()
}
