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
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip51Lists.PrivateTagArrayEvent
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.utils.TimeUtils

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
 * Clients use the first `key` tag; any others follow in order of preference. Rewrites
 * keep every private tag they don't know ([replaceKeys]). Public tags carry nothing
 * secret: an `alt`, and optionally a NIP-70 `["-"]`.
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

    /** Every key, in the user's order of preference; `null` when [signer] can't decrypt (not the author, or refused). */
    suspend fun agentKeys(signer: NostrSigner): List<AgentKeyTag>? = privateTags(signer)?.agentKeys()

    /** The key clients use; `null` when there is none or [signer] can't decrypt. */
    suspend fun preferredKey(signer: NostrSigner): AgentKeyTag? = privateTags(signer)?.preferredAgentKey()

    companion object {
        const val KIND = 12000
        const val FIXED_D_TAG = ""
        const val ALT_DESCRIPTION = "AI agent keys (encrypted)"

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
            return eventTemplate(KIND, encrypt(privateTags, signer), createdAt) {
                alt(ALT_DESCRIPTION)
                initializer()
            }
        }

        /**
         * [earlierVersion] with its keys replaced by [keys], keeping its public tags and every
         * private tag that isn't a `key`, so data another client added survives.
         *
         * @throws SignerExceptions.UnauthorizedDecryptionException when [signer] can't read [earlierVersion].
         */
        suspend fun replaceKeys(
            earlierVersion: AgentKeyEvent,
            keys: List<AgentKeyTag>,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<AgentKeyEvent> {
            val earlierPrivate = earlierVersion.privateTags(signer) ?: throw SignerExceptions.UnauthorizedDecryptionException()
            val privateTags = tagArray<AgentKeyEvent> { agentKeys(keys) } + earlierPrivate.withoutAgentKeys()
            return EventTemplate(
                createdAt,
                KIND,
                earlierVersion.tags,
                encrypt(privateTags, signer),
            )
        }

        /** [earlierVersion] with [key] made the preferred one: moved to the front, or added there if new. */
        suspend fun setPreferred(
            earlierVersion: AgentKeyEvent,
            key: AgentKeyTag,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<AgentKeyEvent> {
            val current = earlierVersion.agentKeys(signer) ?: throw SignerExceptions.UnauthorizedDecryptionException()
            return replaceKeys(earlierVersion, listOf(key) + current.filter { it != key }, signer, createdAt)
        }

        /** [earlierVersion] without [key]. */
        suspend fun remove(
            earlierVersion: AgentKeyEvent,
            key: AgentKeyTag,
            signer: NostrSigner,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<AgentKeyEvent> {
            val current = earlierVersion.agentKeys(signer) ?: throw SignerExceptions.UnauthorizedDecryptionException()
            return replaceKeys(earlierVersion, current.filter { it != key }, signer, createdAt)
        }
    }
}
