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
package com.vitorpamplona.quartz.nip58Badges.award

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.EventHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.utils.TimeUtils

@Immutable
class BadgeAwardEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    EventHintProvider,
    AddressHintProvider,
    PubKeyHintProvider {
    override fun pubKeyHints() = tags.mapNotNull(PTag::parseAsHint)

    /** `AWARDED`: the users the badge is awarded to (`p`). */
    override fun linkedPubKeys() = awardeeIds()

    override fun eventHints() = tags.mapNotNull(ETag::parseAsHint)

    /** `MENTION`: events tagged with `e` (NIP-58 defines none on an award). */
    override fun linkedEventIds() = mentionedEventIds()

    override fun addressHints() = tags.mapNotNull(ATag::parseAsHint)

    /** `BADGE_DEFINITION`: the badge definition being awarded (`a`). */
    override fun linkedAddressIds() = awardDefinitionIds()

    fun awardees() = tags.awardees()

    fun awardeeIds() = tags.awardeeIds()

    fun awardDefinition() = tags.awardDefinitions()

    /** The address ids of [awardDefinition] (`a`), in tag order. NIP-58 writes one. */
    fun awardDefinitionIds(): List<String> = tags.mapNotNull(ATag::parseAddressId)

    /** Events tagged with `e`, in tag order. NIP-58 defines no `e` here; clients may add them. */
    fun mentionedEvents(): List<ETag> = tags.mapNotNull(ETag::parse)

    /** The ids of [mentionedEvents] (`e`), in tag order. */
    fun mentionedEventIds(): List<HexKey> = tags.mapNotNull(ETag::parseId)

    companion object {
        const val KIND = 8

        fun build(
            awardDefinition: ATag,
            awardees: List<PTag>,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<BadgeAwardEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            awardDefinition(awardDefinition)
            awardees(awardees)
            initializer()
        }
    }
}
