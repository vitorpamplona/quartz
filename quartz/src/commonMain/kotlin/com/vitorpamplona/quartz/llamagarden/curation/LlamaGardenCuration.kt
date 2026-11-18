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
package com.vitorpamplona.quartz.llamagarden.curation

import com.vitorpamplona.quartz.llamagarden.torrent.ModelTorrentEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.events.eTag
import com.vitorpamplona.quartz.nip01Core.tags.events.toETag
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip32Labeling.label
import com.vitorpamplona.quartz.nip32Labeling.labelNamespace
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * llama.garden's curation: NIP-32 labels (kind 1985) in the `llama.garden` namespace that approve or
 * reject torrent listings (`e`). The viewer reads approvals only from its whitelisted curators and
 * then fetches the approved listings by id from any author, so a curator's approval is what puts a
 * user submission into the catalog. Whose labels to trust is the app's call; these read and write
 * the shape.
 *
 * The shape on relays (2026-11): `["L","llama.garden"]`, `["l","approve","llama.garden"]`,
 * `["e",<listing id>,<relay>]`.
 */
object LlamaGardenCuration {
    const val NAMESPACE = "llama.garden"
    const val APPROVE = "approve"
    const val REJECT = "reject"

    /** The listing verdicts a label carries in [NAMESPACE]. */
    enum class Verdict { APPROVE, REJECT }

    /** This label's verdict on its `e` targets; reject wins when a label carries both. Null outside [NAMESPACE]. */
    fun verdict(label: LabelEvent): Verdict? {
        var approve = false
        for (l in label.labelsByNamespace(NAMESPACE)) {
            when (l.label) {
                REJECT -> return Verdict.REJECT
                APPROVE -> approve = true
            }
        }
        return if (approve) Verdict.APPROVE else null
    }

    fun isApproval(label: LabelEvent) = verdict(label) == Verdict.APPROVE

    fun isRejection(label: LabelEvent) = verdict(label) == Verdict.REJECT

    /** The listings (`e`) [label] approves; empty unless it is an approval. */
    fun approvedListingIds(label: LabelEvent): List<HexKey> = if (isApproval(label)) label.labeledEvents() else emptyList()

    /** The listings (`e`) [label] rejects; empty unless it is a rejection. */
    fun rejectedListingIds(label: LabelEvent): List<HexKey> = if (isRejection(label)) label.labeledEvents() else emptyList()

    /** A curator's approval of [listing], in the viewer's shape. */
    fun buildApproval(
        listing: EventHintBundle<ModelTorrentEvent>,
        createdAt: Long = TimeUtils.now(),
    ): EventTemplate<LabelEvent> = buildVerdict(listing, APPROVE, createdAt)

    /** A curator's rejection of [listing]. */
    fun buildRejection(
        listing: EventHintBundle<ModelTorrentEvent>,
        createdAt: Long = TimeUtils.now(),
    ): EventTemplate<LabelEvent> = buildVerdict(listing, REJECT, createdAt)

    private fun buildVerdict(
        listing: EventHintBundle<ModelTorrentEvent>,
        verdict: String,
        createdAt: Long,
    ) = eventTemplate<LabelEvent>(LabelEvent.KIND, "", createdAt) {
        labelNamespace(NAMESPACE)
        label(LabelTag(verdict, NAMESPACE))
        eTag(listing.toETag())
    }
}
