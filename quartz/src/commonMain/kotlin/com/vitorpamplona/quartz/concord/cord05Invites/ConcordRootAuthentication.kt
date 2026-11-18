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
package com.vitorpamplona.quartz.concord.cord05Invites

import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey

/**
 * The genesis-anchor root authentication a client runs before persisting a join (CORD-05 §1,
 * PR #23): the `community_root` is not in the `community_id` commitment, so a bundle could pair a
 * real Community's identity with an attacker-chosen root. Accepting therefore requires the delivered
 * keys to open the owner's genesis metadata edition (`vsk 0`, `eid` = `community_id`); on a rotated
 * plane (epoch > 0), where compaction may have left no owner-signed metadata head, the anchor is the
 * Community-bound metadata head plus at least one owner-signed edition.
 */
object ConcordRootAuthentication {
    enum class Verdict {
        /** The delivered keys open the anchor: the root is the Community's. */
        AUTHENTIC,

        /** Nothing was read: the verdict is drawn from absence, so an empty plane defers the join. */
        DEFER,

        /** The plane opened but holds no anchor: refuse the join. */
        REFUSED,
    }

    /**
     * Judges the Control Plane [editions] opened under a bundle's delivered keys for the Community
     * [communityId] owned by [ownerPubKey] at root epoch [rootEpoch]. [completeRead] is false when the
     * read failed or fell short; an incomplete or empty read defers rather than refuses.
     */
    fun verify(
        editions: Collection<ControlEdition>,
        communityId: ByteArray,
        ownerPubKey: HexKey,
        rootEpoch: Long,
        completeRead: Boolean = true,
    ): Verdict {
        if (editions.isEmpty()) return Verdict.DEFER
        val cid = communityId.toHexKey()
        val owner = ownerPubKey.lowercase()
        val bound = ControlEdition.admissibleIn(editions, communityId)
        val metadata = bound.filter { it.entityKind == ControlEntityKind.METADATA && it.entityIdHex == cid }
        val authentic =
            if (rootEpoch == 0L) {
                metadata.any { it.author.lowercase() == owner && it.prevHash == null }
            } else {
                metadata.isNotEmpty() && bound.any { it.author.lowercase() == owner }
            }
        return when {
            authentic -> Verdict.AUTHENTIC
            completeRead -> Verdict.REFUSED
            else -> Verdict.DEFER
        }
    }
}
