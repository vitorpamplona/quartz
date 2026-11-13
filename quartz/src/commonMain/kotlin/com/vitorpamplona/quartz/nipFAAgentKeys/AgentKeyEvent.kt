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
package com.vitorpamplona.quartz.nipFAAgentKeys

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.tagArray
import com.vitorpamplona.quartz.nip01Core.diff.DiffableEvent
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip51Lists.PrivateTagArrayEvent
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.coroutines.cancellation.CancellationException

/**
 * NIP-FA AI agent keys (kind 12000, draft): the keys a user gives the Nostr clients they use
 * to run AI tasks on their behalf, each with the root of the OpenAI-compatible API it
 * works against. Replaceable; the content is NIP-44 encrypted to the author, as a JSON
 * array of private tags:
 *
 * ```
 * [["key", "sk-or-v1-…", "https://openrouter.ai/api/v1"]]
 * ```
 *
 * Clients use the first `key` tag whose URL NIP-FA allows ([usableKeys]); any others follow in order
 * of preference, and a key over plain `http` to another machine is ignored. Rewrites
 * reorder and remove the tag arrays they read and keep every private tag they don't know
 * ([replaceKeys]), and never write over content they couldn't read. It needs no public tags; a
 * NIP-70 `["-"]` is optional (pass it through the builder's initializer).
 *
 * Not searchable: everything it holds is encrypted. Design notes:
 * `quartz/plans/2026-11-10-agent-credentials-nip.md`.
 */
@Immutable
class AgentKeyEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : PrivateTagArrayEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    DiffableEvent<AgentKeyDiff> {
    override fun diffFrom(older: Event): AgentKeyDiff? {
        if (older !is AgentKeyEvent || older.pubKey != pubKey) return null
        return AgentKeyDiff(privateItemsChangeFrom(older))
    }

    override fun dTag() = FIXED_D_TAG

    /**
     * Every well-formed key, in the user's order of preference, the ones readers ignore included
     * (see [usableKeys]); `null` when [signer] can't decrypt (not the author, or refused).
     */
    suspend fun agentKeys(signer: NostrSigner): List<AgentKeyTag>? = privateTags(signer)?.agentKeys()

    /** The keys NIP-FA's readers use ([usableAgentKeys]), the default first; `null` when [signer] can't decrypt. */
    suspend fun usableKeys(signer: NostrSigner): List<AgentKeyTag>? = privateTags(signer)?.usableAgentKeys()

    /** The key clients use, the first [usableKeys]; `null` when there is none or [signer] can't decrypt. */
    suspend fun preferredKey(signer: NostrSigner): AgentKeyTag? = privateTags(signer)?.preferredAgentKey()

    companion object {
        const val KIND = 12000
        const val FIXED_D_TAG = ""

        fun createAddress(pubKey: HexKey) = Address(KIND, pubKey, FIXED_D_TAG)

        /**
         * Always encrypts the JSON, `[]` included: NIP-44 can't encrypt an empty string, which
         * is what [PrivateTagsInContent.encryptNip44] sends for an empty list, and a user who
         * removes their last key still needs a valid event.
         */
        private suspend fun encrypt(
            privateTags: TagArray,
            signer: NostrSigner,
        ) = signer.nip44Encrypt(PrivateTagsInContent.encode(privateTags), signer.pubKey)

        /**
         * A new event holding [keys], first one preferred. Encrypting needs [signer], which
         * must be the author's; the template still has to be signed by it.
         */
        suspend fun build(
            keys: List<AgentKeyTag>,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<AgentKeyEvent>.() -> Unit = {},
        ): EventTemplate<AgentKeyEvent> {
            val privateTags = tagArray<AgentKeyEvent> { agentKeys(keys) }
            return eventTemplate(KIND, encrypt(privateTags, signer), createdAt, initializer)
        }

        /**
         * The private tags of [earlierVersion], for a rewrite. Unlike [privateTags], never null:
         * a rewrite that started from an empty list would delete the user's keys.
         *
         * @throws SignerExceptions.UnauthorizedDecryptionException when [signer] isn't the author.
         * @throws SignerExceptions the signer's own failure to decrypt (refused, timed out, …).
         * @throws UnreadableAgentKeysException when it decrypts to something that isn't a tag array.
         */
        suspend fun readForRewrite(
            earlierVersion: AgentKeyEvent,
            signer: NostrSigner,
        ): TagArray {
            if (signer.pubKey != earlierVersion.pubKey) throw SignerExceptions.UnauthorizedDecryptionException()
            if (earlierVersion.content.isBlank()) return emptyArray()
            val json = signer.decrypt(earlierVersion.content, signer.pubKey)
            return try {
                PrivateTagsInContent.decode(json)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw UnreadableAgentKeysException()
            }
        }

        /**
         * [earlierVersion] with its keys replaced by [keys], keeping its public tags and its
         * private tag arrays as they were: a key it already had keeps its array (extra values
         * included), `key` tags this client can't read and every other private tag survive.
         *
         * @throws SignerExceptions when [signer] can't read [earlierVersion]; see [readForRewrite].
         * @throws UnreadableAgentKeysException when [earlierVersion] decrypts to something unreadable.
         */
        suspend fun replaceKeys(
            earlierVersion: AgentKeyEvent,
            keys: List<AgentKeyTag>,
            signer: NostrSigner,
            createdAt: Long = nextCreatedAt(earlierVersion),
        ): EventTemplate<AgentKeyEvent> = rewrite(earlierVersion, readForRewrite(earlierVersion, signer), keys, signer, createdAt)

        /** [earlierVersion] with [key] made the preferred one: moved to the front, or added there if new. */
        suspend fun setPreferred(
            earlierVersion: AgentKeyEvent,
            key: AgentKeyTag,
            signer: NostrSigner,
            createdAt: Long = nextCreatedAt(earlierVersion),
        ): EventTemplate<AgentKeyEvent> {
            val earlierPrivate = readForRewrite(earlierVersion, signer)
            val current = earlierPrivate.agentKeys()
            return rewrite(earlierVersion, earlierPrivate, listOf(key) + current.filter { it != key }, signer, createdAt)
        }

        /** [earlierVersion] without [key]. */
        suspend fun remove(
            earlierVersion: AgentKeyEvent,
            key: AgentKeyTag,
            signer: NostrSigner,
            createdAt: Long = nextCreatedAt(earlierVersion),
        ): EventTemplate<AgentKeyEvent> {
            val earlierPrivate = readForRewrite(earlierVersion, signer)
            return rewrite(earlierVersion, earlierPrivate, earlierPrivate.agentKeys().filter { it != key }, signer, createdAt)
        }

        /**
         * `max(now, previous.createdAt + 1)`: a rewrite built from an older read never loses to
         * it by clock skew (NIP-FA, Editing).
         */
        fun nextCreatedAt(
            previous: Event?,
            now: Long = TimeUtils.now(),
        ) = if (previous == null) now else maxOf(now, previous.createdAt + 1)

        /**
         * [earlierVersion] with its keys set to [keys], from [earlierPrivate], its private tags
         * as [readForRewrite] returned them: for a caller that already read them to decide the
         * new order, so the signer is asked to decrypt once. Public tags and every private tag
         * other than the keys stay as they were ([withAgentKeys]).
         */
        suspend fun rewrite(
            earlierVersion: AgentKeyEvent,
            earlierPrivate: TagArray,
            keys: List<AgentKeyTag>,
            signer: NostrSigner,
            createdAt: Long = nextCreatedAt(earlierVersion),
        ) = EventTemplate<AgentKeyEvent>(
            createdAt,
            KIND,
            earlierVersion.tags,
            encrypt(earlierPrivate.withAgentKeys(keys), signer),
        )
    }
}

/**
 * The agent keys decrypted, but not to a tag array this client can read. Rewriting would
 * replace whatever is there, so nothing is written.
 */
class UnreadableAgentKeysException : IllegalStateException("The agent keys event holds content this client can't read; not overwriting it")
