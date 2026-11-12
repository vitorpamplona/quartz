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
package com.vitorpamplona.quartz.nip51Lists

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.diff.ContentChange
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import kotlinx.coroutines.CancellationException

@Immutable
abstract class PrivateTagArrayEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    kind: Int,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, kind, tags, content, sig) {
    override fun isContentEncoded() = true

    /**
     * A NIP-51 LIST (10000–19999) is replaceable: NIP-01 fixes its address to `kind:pubkey:`
     * whatever tags it carries. Read from the tags, a stray `d` would split one user's list
     * into several addresses. SETS (30000–39999) are addressed by their `d`.
     */
    override fun dTag(): String = if (kind.isReplaceable()) "" else super.dTag()

    /**
     * How the NIP-44 encrypted private items changed since [older]. They can't be compared
     * item by item without decrypting, so only as a whole.
     */
    fun privateItemsChangeFrom(older: PrivateTagArrayEvent) = ContentChange.between(older.content, content)

    /**
     * The keys [reader] decrypts the private tags with, as the other side of the NIP-44
     * conversation, tried in this order; empty when [reader] can't read them. A NIP-51 list is
     * encrypted by its author to themselves, so only the author reads it, with itself as peer.
     * A kind whose private tags are shared with another key (a NIP-FB task's runner) overrides it.
     */
    open fun privateTagsPeers(reader: HexKey): List<HexKey> = if (reader == pubKey) listOf(pubKey) else emptyList()

    /** Whether [reader] is one of the keys the private tags may be encrypted to ([privateTagsPeers]). */
    fun canReadPrivateTags(reader: HexKey): Boolean = privateTagsPeers(reader).isNotEmpty()

    suspend fun decrypt(signer: NostrSigner): TagArray {
        val peers = privateTagsPeers(signer.pubKey)
        if (peers.isEmpty()) throw SignerExceptions.UnauthorizedDecryptionException()
        if (peers.size == 1) return PrivateTagsInContent.decrypt(content, signer, peers[0])

        var failure: Exception? = null
        for (peer in peers) {
            try {
                return PrivateTagsInContent.decrypt(content, signer, peer)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Only a failed decryption means "try the next peer". The signer's own answers
                // (the user rejected it, it timed out, it needs the foreground, it's gone) hold
                // for every peer: asking again would prompt the user once per peer.
                if (!isWrongPeer(e)) throw e
                failure = e
            }
        }
        throw failure ?: SignerExceptions.UnauthorizedDecryptionException()
    }

    /**
     * Whether [e], thrown while decrypting with one peer, may just mean the content was encrypted
     * to another: the signer couldn't decrypt it ([SignerExceptions.CouldNotPerformException],
     * what every signer answers to a MAC failure), or the result didn't parse.
     */
    private fun isWrongPeer(e: Exception): Boolean =
        e !is SignerExceptions ||
            e is SignerExceptions.CouldNotPerformException ||
            e is SignerExceptions.UnauthorizedDecryptionException

    suspend fun privateTags(signer: NostrSigner): TagArray? {
        if (!canReadPrivateTags(signer.pubKey)) {
            return null
        }

        return try {
            decrypt(signer)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
}
