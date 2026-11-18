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
package com.vitorpamplona.quartz.concord.cord04Roles

import com.vitorpamplona.quartz.concord.cord04Roles.control.ControlEditionEvent
import com.vitorpamplona.quartz.concord.cord04Roles.control.eid
import com.vitorpamplona.quartz.concord.cord04Roles.control.ev
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.CanonicalDecimal
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.EbTag
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.EhTag
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.EidTag
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.EpTag
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.EvTag
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.VacTag
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.VskTag
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.crypto.EditionHash
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.concord.envelope.OpenedStreamEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArrayOrNull
import com.vitorpamplona.quartz.nip01Core.core.toHexKey

/**
 * The exact Grant edition an actor claims authority under (the `vac` tag,
 * CORD-04). Verifiers block until they have synced this Grant, then resolve the
 * actor's rank against it — a demoted member's stale citation is dropped.
 */
class AuthorityCitation(
    val grantId: ByteArray,
    val grantVersion: Long,
    val grantHash: ByteArray,
)

/**
 * A single Control Plane edition (a kind-3308 author rumor, CORD-02/04).
 *
 * Editions are versioned, chainable state: each carries an entity id ([entityId],
 * `eid`), a monotonically increasing [version] (`ev`), the [prevHash] of the
 * previous edition (`ep`, absent for the genesis edition), an optional
 * [authorityCitation] (`vac`) pinning the Grant the [author] acts under, and the
 * verbatim entity [content]. Its identity is [hash] — a domain-separated hash of
 * exactly those fields (see [EditionHash]) — which the next edition cites in `ep`,
 * forming an unforgeable chain.
 *
 * [entityKind] is null for a sub-kind this client does not model (Pins, vsk 11;
 * Signals, vsk 12; anything newer): nothing here folds such an edition into state, but
 * it is kept — [vsk] carries its raw sub-kind — so the anti-rollback floor and a
 * Refounding's compaction carry its head forward verbatim instead of silently dropping
 * another client's state (CORD-04 §7, CORD-06 §3).
 */
class ControlEdition(
    val entityKind: ControlEntityKind?,
    val entityId: ByteArray,
    val version: Long,
    val prevHash: ByteArray?,
    val authorityCitation: AuthorityCitation?,
    val content: String,
    /** The actor's real pubkey (the seal/rumor author). */
    val author: String,
    /** The rumor's event id — the deterministic tie-break key at equal version. */
    val rumorId: String,
    val createdAt: Long,
    /** The raw `vsk` sub-kind on the wire; [entityKind]'s wire value when it is modeled. */
    val vsk: String = entityKind?.wire ?: "",
    /**
     * The `edition_binding(community_id)` a **marked** edition names in its `eb` tag (CORD-04 §1),
     * or null for an unmarked edition. A marked edition's [hash] covers its signer, and a reader
     * refuses one bound to another Community ([isAdmissibleIn]).
     */
    val binding: ByteArray? = null,
) {
    @Deprecated("Use the constructor with binding instead. Removed in 3.0.0.", level = DeprecationLevel.HIDDEN)
    constructor(
        entityKind: ControlEntityKind?,
        entityId: ByteArray,
        version: Long,
        prevHash: ByteArray?,
        authorityCitation: AuthorityCitation?,
        content: String,
        author: String,
        rumorId: String,
        createdAt: Long,
        vsk: String,
    ) : this(entityKind, entityId, version, prevHash, authorityCitation, content, author, rumorId, createdAt, vsk, null)

    /** True when this edition is marked (`["eh","2"]`, CORD-04 §1). */
    val isMarked: Boolean get() = binding != null

    /**
     * Domain-separated edition identity; the next edition's `ep` cites this. A marked edition's
     * hash covers its signer too (CORD-04 §1), so a copy re-signed under another key is another
     * edition.
     */
    val hash: ByteArray by lazy {
        if (binding != null) {
            EditionHash.markedHash(entityId, version, prevHash, content.encodeToByteArray(), author.hexToByteArray())
        } else {
            EditionHash.hash(entityId, version, prevHash, content)
        }
    }

    /**
     * Whether a reader of the Community whose `edition_binding` is [communityBinding] may fold this
     * edition at all (CORD-04 §1): a marked edition must be bound to that Community, and an unmarked
     * one must not be of a kind no older client wrote (vsk 13 and up, [ControlEntityKind.alwaysMarked]).
     */
    fun isAdmissibleIn(communityBinding: ByteArray): Boolean {
        if (binding != null) return binding.contentEquals(communityBinding)
        val number = vsk.toIntOrNull() ?: return true
        return number < FIRST_ALWAYS_MARKED_VSK
    }

    val entityIdHex: String get() = entityId.toHexKey()
    val hashHex: String get() = hash.toHexKey()

    /** This edition carrying [citation] as its `vac`. The hash does not cover it, so the chain is unchanged. */
    fun withCitation(citation: AuthorityCitation?): ControlEdition = ControlEdition(entityKind, entityId, version, prevHash, citation, content, author, rumorId, createdAt, vsk, binding)

    companion object {
        /** The lowest sub-kind no client before Roles v2 wrote: marked in every Community (CORD-04 §1). */
        const val FIRST_ALWAYS_MARKED_VSK = 13

        /** The machinery tags an edition may carry at most once each (CORD-04 §1, Armada `parseEdition`). */
        private val SINGLE_VALUED_TAGS =
            arrayOf(VskTag.TAG_NAME, EidTag.TAG_NAME, EvTag.TAG_NAME, EpTag.TAG_NAME, VacTag.TAG_NAME, EhTag.TAG_NAME, EbTag.TAG_NAME)

        /**
         * Drops the [editions] a reader of the Community [communityId] refuses outright (CORD-04 §1):
         * a marked edition bound to another Community, and an unmarked edition of a kind that is
         * always marked. Every fold of a Community's plane runs this first.
         */
        fun admissibleIn(
            editions: Collection<ControlEdition>,
            communityId: ByteArray,
        ): List<ControlEdition> {
            val binding = ConcordKeyDerivation.editionBinding(communityId)
            return editions.filter { it.isAdmissibleIn(binding) }
        }

        /**
         * Sub-kinds that are never Control Plane editions (CORD-02 Appendix B): 6 and 9 are
         * claimed by the addressable kind-33301 invite marker, 7 is retired (the v1 owner
         * attestation), and 10 is the dissolution tombstone, which lives at its own address and
         * must never be read off this plane (see `ConcordDissolution`).
         */
        private val NOT_CONTROL_EDITIONS =
            setOf(ControlEntityKind.INVITE_LIVE.wire, "7", ControlEntityKind.INVITE_REVOKED.wire, ControlEntityKind.DISSOLVED.wire)

        /**
         * Parses an opened Control Plane wrap into a [ControlEdition], or null when it is not
         * one. On top of [fromRumor] it enforces the plane's seal kind: a Control edition
         * **MUST** ride a plaintext kind-20014 seal (CORD-02 §5, Appendix B), since only a
         * plaintext seal survives a compaction re-wrap with its signature intact. An edition
         * under an encrypted 20013 seal is refused, as the reference client refuses it.
         */
        fun fromOpened(opened: OpenedStreamEvent): ControlEdition? {
            if (opened.sealKind != ConcordStreamEnvelope.KIND_SEAL_PLAINTEXT) return null
            return fromRumor(opened.rumor, opened.author)
        }

        /**
         * Parses a decrypted, verified kind-3308 [rumor] (its [author] is the
         * rumor's pubkey) into a [ControlEdition], or returns null if it is not a
         * well-formed control edition, so the caller drops it rather than folding
         * garbage: an absent or non-canonical `vsk`, a sub-kind that is not a Control
         * edition (6, 7, 9, 10), a missing or malformed `eid`/`ev`, a malformed `ep`/`vac`,
         * or any of those machinery tags appearing more than once (an ambiguous edition
         * two readers could parse differently). A canonical `vsk` this client does not
         * model parses with a null [ControlEdition.entityKind] (see the class doc).
         *
         * Prefer [fromOpened] for anything read off a plane: this does not see the seal.
         */
        fun fromRumor(
            rumor: Event,
            author: String = rumor.pubKey,
        ): ControlEdition? {
            if (rumor.kind != ControlEditionEvent.KIND) return null
            for (name in SINGLE_VALUED_TAGS) {
                if (rumor.tags.count { it.isNotEmpty() && it[0] == name } > 1) return null
            }

            val vskWire = rumor.tags.firstOrNull { it.size >= 2 && it[0] == VskTag.TAG_NAME }?.get(1) ?: return null
            if (!CanonicalDecimal.isCanonical(vskWire) || vskWire in NOT_CONTROL_EDITIONS) return null
            val entityKind = ControlEntityKind.of(vskWire)

            val entityId = rumor.tags.eid() ?: return null
            val version = rumor.tags.ev() ?: return null

            // A present-but-malformed `ep` is a corrupt edition (reject); an absent (or blank)
            // `ep` is the genesis edition (no previous hash).
            val epTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == EpTag.TAG_NAME && it[1].isNotBlank() }
            val prevHash = if (epTag == null) null else EpTag.parse(epTag) ?: return null

            // A marked edition names its hash form and its Community (CORD-04 §1): an `eh` other than
            // "2", or one without its `eb`, is refused; an `eb` without `eh` is ignored (unmarked).
            val ehTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == EhTag.TAG_NAME }
            val binding =
                if (ehTag == null) {
                    null
                } else {
                    if (ehTag[1] != EhTag.MARKED) return null
                    val ebTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == EbTag.TAG_NAME } ?: return null
                    EbTag.parse(ebTag) ?: return null
                }
            // A marked hash covers the signer's 32-byte key.
            if (binding != null && author.hexToByteArrayOrNull()?.size != 32) return null

            // Likewise a present-but-malformed `vac` is rejected; absent means owner-authored.
            val vacTag = rumor.tags.firstOrNull { it.size >= 4 && it[0] == VacTag.TAG_NAME }
            val vac = if (vacTag == null) null else VacTag.parse(vacTag) ?: return null

            return ControlEdition(
                entityKind = entityKind,
                entityId = entityId,
                version = version,
                prevHash = prevHash,
                authorityCitation = vac,
                content = rumor.content,
                author = author,
                rumorId = rumor.id,
                createdAt = rumor.createdAt,
                vsk = vskWire,
                binding = binding,
            )
        }
    }
}
