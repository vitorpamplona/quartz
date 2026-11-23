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
package com.vitorpamplona.quartz.llamagarden.seederRequest

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.llamagarden.seederRequest.tags.InfoHashTag
import com.vitorpamplona.quartz.llamagarden.torrent.ModelTorrentEvent
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastMapNotNull
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.events.toETag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A llama.garden seeder request (kind 30102): "this torrent has no seeders, please seed it." The
 * viewer publishes one per user per torrent (`d` = `<pubkey>:<infohash>`), pointing at the listing
 * (`e`) and its infohash (`i`), with the listing's title in `name`, after mining a NIP-13 `nonce`.
 *
 * **Kind 30102 is shared** with drss's event lists, a showcase app's votes, vouch counters and
 * game results. [isSeederRequest] tells this shape apart; EventFactory tries drss's stricter
 * classifier first and parses the rest as UnrecognizedKind30102Event.
 *
 * Not searchable: [name] is a copy of the listing's title, which the listing itself indexes.
 */
@Immutable
class SeederRequestEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider {
    /** The torrent listing (`e`) that needs seeders. */
    fun torrentListing(): ETag? = tags.torrentListing()

    /** The id of [torrentListing]. */
    fun torrentListingId(): HexKey? = tags.torrentListingId()

    /**
     * The listing's `d` (`i`), as the viewer writes it: the infohash of a catalog listing, or
     * `user:<pubkey>:<hash>` for a user submission.
     */
    fun listingKey(): String? = tags.infoHash()

    /**
     * The infohash that needs seeders, lower-cased: [listingKey] or the part of `d` after the first
     * colon, when it is one. Null for a request about a user submission, which has no infohash yet.
     */
    fun infoHash(): String? =
        (listingKey() ?: dTag().substringAfter(':', "").ifEmpty { null })
            ?.takeIf(ModelTorrentEvent::isInfoHash)
            ?.lowercase()

    /** The listing's title, as the requester saw it. */
    fun name() = tags.name()

    fun clientName() = tags.clientName()

    override fun eventHints(): List<EventIdHint> = tags.fastMapNotNull(ETag::parseAsHint)

    /** `REFERENCES`: the torrent listing that needs seeders (`e`). */
    override fun linkedEventIds(): List<HexKey> = listOfNotNull(torrentListingId())

    companion object {
        const val KIND = 30102

        /**
         * True for llama.garden's shape: the listing's key in `i` beside its event in `e`. Either alone
         * is too common to mean this (`i` is also NIP-73's external id).
         */
        fun isSeederRequest(tags: TagArray): Boolean = tags.fastAny { InfoHashTag.parse(it) != null } && tags.fastAny { ETag.parseId(it) != null }

        /** The `d` the viewer uses, so a user's second request for the same torrent replaces the first. */
        fun dTagFor(
            requester: HexKey,
            infoHash: String,
        ) = "$requester:${infoHash.lowercase()}"

        /**
         * A request for seeders of [torrent], by [requester] (the key that will sign it). Like the viewer,
         * it keys the request by the listing's `d`, so a user submission's request carries
         * `user:<pubkey>:<hash>` and reads back with a null [infoHash]. Mine it before publishing, as
         * llama.garden's viewer does.
         */
        fun build(
            torrent: EventHintBundle<ModelTorrentEvent>,
            requester: HexKey,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<SeederRequestEvent>.() -> Unit = {},
        ): EventTemplate<SeederRequestEvent> = build(torrent.toETag(), torrent.event.infoHash() ?: torrent.event.dTag(), requester, torrent.event.title(), createdAt, initializer)

        /** [build] for a caller that holds the listing only by id and its `d` ([infoHash] for a catalog listing). */
        fun build(
            torrent: ETag,
            infoHash: String,
            requester: HexKey,
            name: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<SeederRequestEvent>.() -> Unit = {},
        ) = eventTemplate<SeederRequestEvent>(KIND, "", createdAt) {
            dTag(dTagFor(requester, infoHash))
            torrentListing(torrent)
            infoHash(infoHash.lowercase())
            name?.let { name(it) }
            initializer()
        }
    }
}
