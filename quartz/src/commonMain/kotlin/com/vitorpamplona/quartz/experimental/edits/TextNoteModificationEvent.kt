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
package com.vitorpamplona.quartz.experimental.edits

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.events.toETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.toPTag
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class TextNoteModificationEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider,
    PubKeyHintProvider,
    SearchableEvent {
    /** The note this modification edits (its first valid `e` tag), with its relay and author hints. */
    fun editedNote() = tags.firstNotNullOfOrNull(ETag::parse)

    /** The id of the note this modification edits (its first valid `e` tag). */
    fun editedNoteId(): HexKey? = tags.firstNotNullOfOrNull(ETag::parseId)

    /** The user this edit notifies (the `p` that [create] writes from its `notify` argument). */
    fun notifiedUser(): HexKey? = tags.firstNotNullOfOrNull(PTag::parseKey)

    // The edited note (`e`) and whoever the edit notifies (`p`), so outbox can fetch the
    // original the modification applies to.
    override fun eventHints(): List<EventIdHint> = tags.mapNotNull(ETag::parseAsHint)

    // [create] writes one `e` (the edited note) and at most one `p` (the notified user).
    override fun linkedEventIds(): List<HexKey> = listOfNotNull(editedNoteId())

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    override fun linkedPubKeys(): List<HexKey> = listOfNotNull(notifiedUser())

    fun summary() = tags.editSummary()

    override fun indexableContent() = listOfNotNull(content, summary()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(content)) return
        visitor.visit(summary())
    }

    companion object {
        const val KIND = 1010

        /** [content] replaces [editedNote]'s text; [notify] is its author, when that is someone else. */
        fun build(
            content: String,
            editedNote: ETag,
            notify: PTag? = null,
            summary: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<TextNoteModificationEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, content, createdAt) {
            editedNote(editedNote)
            notify?.let { notify(it) }
            summary?.let { editSummary(it) }
            initializer()
        }

        /** Edits [editedNote], carrying its relay hint and author; [notifyAuthor] when editing someone else's note. */
        fun build(
            content: String,
            editedNote: EventHintBundle<TextNoteEvent>,
            notifyAuthor: Boolean = false,
            summary: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<TextNoteModificationEvent>.() -> Unit = {},
        ) = build(content, editedNote.toETag(), if (notifyAuthor) editedNote.toPTag() else null, summary, createdAt, initializer)

        suspend fun create(
            content: String,
            eventId: HexKey,
            notify: HexKey?,
            summary: String?,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): TextNoteModificationEvent = signer.sign(build(content, ETag(eventId), notify?.let { PTag(it) }, summary, createdAt))
    }
}
