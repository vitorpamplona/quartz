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

/**
 * The markup of an event's `content`, as the kind's NIP names it.
 *
 * Every format here carries Nostr's own inline references as well: NIP-27 `nostr:` URIs, URLs,
 * hashtags and NIP-30 custom emoji appear in plain text and inside markup alike.
 *
 * This is what the spec says, not what a client chooses to render: kind 1 is [PLAIN_TEXT] because
 * NIP-10 says markup "SHOULD NOT be used", even though clients that find markdown in a note may
 * still draw it. That choice is the client's.
 */
enum class ContentFormat {
    /**
     * Text with no markup. Kind 1 (NIP-10: "Markup languages such as markdown and HTML SHOULD NOT
     * be used"), 14 (NIP-17: "MUST be plain text"), 24 (NIP-A4) and 1111 (NIP-22: "no HTML,
     * Markdown, or other formatting").
     */
    PLAIN_TEXT,

    /**
     * Markdown. Kinds 30023/30024 (NIP-23, which forbids HTML inside it), NIP-34's pull requests,
     * issues and status events, NIP-51's release notes (30063), NIP-99 classifieds and NIP-F4
     * episodes.
     */
    MARKDOWN,

    /**
     * [Djot](https://djot.net/), kind 30818 (NIP-54). NIP-54 adds two rules to it: a link may
     * target a `nostr:` URI, and a reference link with no definition (a wikilink) points to the
     * article whose `d` tag is its label normalized by
     * [com.vitorpamplona.quartz.nip54Wiki.WikiRedirectEvent.normalizeSlug].
     */
    DJOT,

    /** AsciiDoc, kind 30041 (NKBIP-01 publication sections). */
    ASCIIDOC,
}

/**
 * An event whose `content` is text in a format its NIP names. A kind whose NIP says nothing about
 * the format of its content, or whose content is not text for a person to read (JSON, a patch,
 * ciphertext), does not implement this.
 *
 * Implementing it also takes a row in [ContentFormats.KINDS]; `ContentFormatsTest` builds every
 * kind and fails when the two disagree.
 */
interface FormattedContentEvent {
    fun contentFormat(): ContentFormat
}
