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
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip51Lists.PrivateTagArrayEvent
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.CronTag
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-FB Scheduled Tasks: one recurring job a person has their clients run, such as the morning
 * front page. Addressable; `d` is the task's id. Like a NIP-51 list, its tags may be public (the
 * event's tags) or private (the content: a NIP-44 ciphertext, by the author to themselves, of a
 * JSON tag array), and readers use both: read them with [task], write them with [update]. A public
 * `p` names a runner (NIP-FB, Runner): the private tags are then NIP-44 between the author and the
 * runner, so both read them, and only the runner runs the task.
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
    /** The value of every public `p`: the task's runners (NIP-FB, Runner). More than one is a conflict. */
    fun runners(): List<String> = tags.runnerKeys()

    /**
     * The one runner the task names, or null: none, a conflict, or a `p` that isn't a lowercase
     * hex key or is the author's own ([ScheduledTask.runnerFor]).
     */
    fun runner(): HexKey? = ScheduledTask(tags).runnerFor(pubKey)

    /**
     * The private tags are NIP-44 between the author and the runner when one is named, else the
     * author's to themselves. The author tries first the peer NIP-FB's writers encrypt to
     * ([ScheduledTask.privateTagsPeer]: the valid runner, else itself), then, for what other
     * writers did with a conflict or an odd `p` (an older writer may have encrypted to one of them,
     * an uppercase one included), every other key in a public `p`, then itself. Each key is tried
     * once, whatever its case. A key in a public `p` reads with the author as peer; nobody else
     * reads them.
     */
    override fun privateTagsPeers(reader: HexKey): List<HexKey> {
        val task = ScheduledTask(tags)
        val runners = task.runners
        if (runners.isEmpty()) return super.privateTagsPeers(reader)
        return when (reader) {
            pubKey -> {
                val others = runners.filter(Hex::isHexKey).map { it.lowercase() }
                (listOf(task.privateTagsPeer(pubKey)) + others + pubKey).distinct()
            }
            in runners -> listOf(pubKey)
            else -> emptyList()
        }
    }

    /**
     * The task, its public tags merged with the private ones [signer] decrypts; null when the
     * signer is neither the author nor a runner the task names, or can't decrypt the content. A
     * null for an event that exists means unreadable: never rewrite it from nothing. A runner reads
     * the task to run it, never to rewrite it (it can't sign the author's event).
     */
    suspend fun task(signer: NostrSigner): ScheduledTask? {
        if (!canReadPrivateTags(signer.pubKey)) return null
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

        /** [author]'s tasks, for a relay read: the one under [dTag], or every one when it is null. */
        fun filter(
            author: HexKey,
            dTag: String? = null,
        ) = Filter(kinds = listOf(KIND), authors = listOf(author), tags = dTag?.let { mapOf("d" to listOf(it)) })

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
         * what [PrivateTagsInContent.encryptNip44] sends for an empty list. [peer] is the other
         * key of the conversation: the runner, or the signer itself.
         */
        private suspend fun encrypt(
            privateTags: TagArray,
            signer: NostrSigner,
            peer: HexKey = signer.pubKey,
        ) = signer.nip44Encrypt(PrivateTagsInContent.encode(privateTags), peer)

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
         * missing), its private tags encrypted by [signer] to the task's runner when it names one
         * ([ScheduledTask.runner]), else to itself. So setting or clearing the runner
         * ([ScheduledTask.withRunner]) and building the event re-encrypts to the new peer.
         */
        suspend fun update(
            task: ScheduledTask,
            signer: NostrSigner,
            dTag: String,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<ScheduledTaskEvent> {
            val public = if (task.publicTags.any(DTag::isTag)) task.publicTags else arrayOf(DTag.assemble(dTag)) + task.publicTags
            return EventTemplate(createdAt, KIND, public, encrypt(task.privateTags, signer, task.privateTagsPeer(signer.pubKey)))
        }

        /**
         * The edit of [previous] (NIP-FB, Editing) that holds [task]: `created_at` =
         * [nextCreatedAt], and no public `client` tag ([ScheduledTask.withoutPublicClientTag]).
         * Sign it with a signer that adds no `client` tag of its own.
         */
        suspend fun rewrite(
            task: ScheduledTask,
            signer: NostrSigner,
            dTag: String,
            previous: Event?,
            now: Long = TimeUtils.now(),
        ): EventTemplate<ScheduledTaskEvent> = update(task.withoutPublicClientTag(), signer, dTag, nextCreatedAt(previous, now))
    }
}
