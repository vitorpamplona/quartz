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
package com.vitorpamplona.quartz.nipFBScheduledTasks

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.tagArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip51Lists.PrivateTagArrayEvent
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-FB Scheduled Tasks: one recurring job a person has their clients run, such as the morning
 * front page. Addressable; `d` is the task's id. Like a NIP-51 list, its tags may be public (the
 * event's tags) or private (the content: a NIP-44 ciphertext, by the author to themselves, of a
 * JSON tag array), and readers use both: read them with [task], write them with [update].
 *
 * Not searchable: its tags are times, ids and device names, nothing a person searches for. It
 * references nothing, so it provides no hints.
 */
@Immutable
class ScheduledTaskEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : PrivateTagArrayEvent(id, pubKey, createdAt, KIND, tags, content, sig) {
    /**
     * The task, its public tags merged with the private ones [signer] (the author) decrypts; null
     * when the signer isn't the author or can't decrypt the content. A null for an event that
     * exists means unreadable: never rewrite it from nothing.
     */
    suspend fun task(signer: NostrSigner): ScheduledTask? {
        if (signer.pubKey != pubKey) return null
        if (content.isBlank()) return ScheduledTask(tags, emptyArray())
        val private = privateTags(signer) ?: return null
        return ScheduledTask(tags, private)
    }

    companion object {
        const val KIND = 34817

        fun createAddress(
            pubKey: HexKey,
            dTag: String,
        ) = Address(KIND, pubKey, dTag)

        /**
         * `max(now, previous.createdAt + 1)`: a rewrite built from an older read never loses to
         * it by clock skew (NIP-FB, Editing).
         */
        fun nextCreatedAt(
            previous: Event?,
            now: Long = TimeUtils.now(),
        ) = if (previous == null) now else maxOf(now, previous.createdAt + 1)

        /**
         * Always encrypts the JSON, `[]` included: NIP-44 can't encrypt an empty string, which is
         * what [PrivateTagsInContent.encryptNip44] sends for an empty list.
         */
        private suspend fun encrypt(
            privateTags: TagArray,
            signer: NostrSigner,
        ) = signer.nip44Encrypt(PrivateTagsInContent.encode(privateTags), signer.pubKey)

        /**
         * A new task under [dTag] running [task] on [cron]: `d` public, the rest private, as
         * NIP-FB advises. [initializer] adds private tags (the task's options).
         */
        suspend fun build(
            task: String,
            cron: CronTag,
            signer: NostrSigner,
            isPaused: Boolean = false,
            dTag: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ScheduledTaskEvent>.() -> Unit = {},
        ): EventTemplate<ScheduledTaskEvent> {
            val privateTags =
                tagArray<ScheduledTaskEvent> {
                    taskName(task)
                    cron(cron)
                    if (isPaused) paused()
                    initializer()
                }
            return eventTemplate(KIND, encrypt(privateTags, signer), createdAt) { dTag(dTag) }
        }

        /**
         * The event for [task] as it stands: its public tags as they are (`d` added when
         * missing), its private tags encrypted by [signer] to itself.
         */
        suspend fun update(
            task: ScheduledTask,
            signer: NostrSigner,
            dTag: String,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<ScheduledTaskEvent> {
            val public = if (task.publicTags.any(DTag::isTag)) task.publicTags else arrayOf(DTag.assemble(dTag)) + task.publicTags
            return EventTemplate(createdAt, KIND, public, encrypt(task.privateTags, signer))
        }
    }
}
