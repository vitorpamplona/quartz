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
package com.vitorpamplona.quartz.nip01Core.hints

import com.vitorpamplona.quartz.experimental.trustedLists.addressables.AddressableTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.addressables.tags.AddressMemberTag
import com.vitorpamplona.quartz.experimental.trustedLists.events.EventTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.events.tags.EventMemberTag
import com.vitorpamplona.quartz.experimental.trustedLists.tags.MemberTagFields
import com.vitorpamplona.quartz.experimental.trustedLists.users.UserTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.users.tags.PubKeyMemberTag
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.events.ETag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip51Lists.followSet.FollowSetEvent
import com.vitorpamplona.quartz.nip51Lists.mediaStarterPack.MediaStarterPackEvent
import com.vitorpamplona.quartz.nip51Lists.starterPack.StarterPackEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.addressables.AddressableAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.events.EventAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.UserAssertionEvent
import com.vitorpamplona.quartz.utils.Hex

/**
 * The nostr records an event NOMINATES as what it is about: the subjects of a pointer event.
 *
 * The hint providers ([EventHintProvider], [PubKeyHintProvider], [AddressHintProvider]) list
 * every record an event links to, mentions and threading included. This is narrower: only the
 * records the event's own NIP defines as its subject, for the families whose content is *about*
 * something else (a list is found by its title, an assertion by its petname, a label by its
 * value, and the reader wants the record on the other end):
 *
 * | kind | family | subject |
 * |---|---|---|
 * | 1985 | NIP-32 label | every `e` / `p` / `a` tag (`r` and `t` name no nostr record) |
 * | 30382 / 30383 / 30384 | NIP-85 assertion | the `d` tag: a pubkey, an event id, a coordinate |
 * | 30392 / 30393 / 30394 | Trusted List | that kind's member tag, `p` / `e` / `a`, with its 0..100 score |
 * | 30000, 39089, 39092 | NIP-51 people list, follow pack, media follow pack | every public `p` tag |
 *
 * Left out on purpose: NIP-73 external ids (30385, 30395: no nostr record to name), a Trusted
 * List's `about`/observer tags (what the list was computed over, not its members), follow lists
 * (kind 3, the social graph rather than a curation) and mute lists (a nomination against).
 * Private NIP-51 entries are encrypted and never read.
 *
 * Dispatch is on [Event.kind], never on the runtime class, so a plain [Event] (one rebuilt from a
 * store row, or parsed by a build whose `EventFactory` predates a family) gives the same answer.
 * Values come back canonical so they compare as strings: ids and pubkeys as 64 lowercase hex
 * (an `e`/`p` value in another case is lowercased; an assertion's `d` must already be exact,
 * since a differently spelled `d` is a different address), coordinates re-assembled as
 * `kind:lowercase-pubkey:d` (an `naddr1…` spelling is decoded).
 *
 * What a caller DOES with the subjects (expand a search, gate on who signed the pointer, which
 * kinds a subject may convert into) is the caller's policy.
 */
class SubjectReferences(
    val eventIds: List<HexKey> = emptyList(),
    val pubKeys: List<HexKey> = emptyList(),
    /** Coordinates, `kind:pubkey:d`, canonical. */
    val addresses: List<String> = emptyList(),
    /**
     * The pointer's confidence per reference (keyed by the id, pubkey or canonical coordinate), on
     * the 0..100 scale. Only Trusted List membership carries one; a value off that scale is absent
     * rather than clamped ([MemberTagFields.score]). An absent entry means "as sure as the pointer
     * itself", not "unsure".
     */
    val scores: Map<String, Int> = emptyMap(),
) {
    val size: Int get() = eventIds.size + pubKeys.size + addresses.size

    fun isEmpty() = size == 0

    companion object {
        val NONE = SubjectReferences()

        /** The NIP-51 lists of people whose `p` tags are members. */
        val PEOPLE_LISTS: Set<Kind> = setOf(FollowSetEvent.KIND, StarterPackEvent.KIND, MediaStarterPackEvent.KIND)

        /** Every kind [of] can extract a subject from; any other kind answers [NONE]. */
        val KINDS: Set<Kind> =
            setOf(
                LabelEvent.KIND,
                UserAssertionEvent.KIND,
                EventAssertionEvent.KIND,
                AddressableAssertionEvent.KIND,
                UserTrustedListEvent.KIND,
                EventTrustedListEvent.KIND,
                AddressableTrustedListEvent.KIND,
            ) + PEOPLE_LISTS

        /** The subjects [event] nominates, or [NONE] for a kind outside [KINDS]. */
        fun of(event: Event): SubjectReferences =
            when (event.kind) {
                LabelEvent.KIND -> {
                    SubjectReferences(
                        eventIds = event.tags.mapNotNull { canonicalKey(ETag.parseId(it)) },
                        pubKeys = event.tags.mapNotNull { canonicalKey(PTag.parseKey(it)) },
                        addresses = event.tags.mapNotNull { canonicalAddress(ATag.parseAddressId(it)) },
                    )
                }

                UserAssertionEvent.KIND -> {
                    SubjectReferences(pubKeys = listOfNotNull(exactKey(event.tags.dTag())))
                }

                EventAssertionEvent.KIND -> {
                    SubjectReferences(eventIds = listOfNotNull(exactKey(event.tags.dTag())))
                }

                AddressableAssertionEvent.KIND -> {
                    SubjectReferences(addresses = listOfNotNull(canonicalAddress(event.tags.dTag())))
                }

                UserTrustedListEvent.KIND -> {
                    val keyOf = { tag: Array<String> -> canonicalKey(PubKeyMemberTag.parseKey(tag)) }
                    SubjectReferences(pubKeys = event.tags.mapNotNull(keyOf), scores = event.tags.memberScores(keyOf))
                }

                EventTrustedListEvent.KIND -> {
                    val idOf = { tag: Array<String> -> canonicalKey(EventMemberTag.parseId(tag)) }
                    SubjectReferences(eventIds = event.tags.mapNotNull(idOf), scores = event.tags.memberScores(idOf))
                }

                AddressableTrustedListEvent.KIND -> {
                    val addressOf = { tag: Array<String> -> canonicalAddress(AddressMemberTag.parseAddressId(tag)) }
                    SubjectReferences(addresses = event.tags.mapNotNull(addressOf), scores = event.tags.memberScores(addressOf))
                }

                FollowSetEvent.KIND, StarterPackEvent.KIND, MediaStarterPackEvent.KIND -> {
                    SubjectReferences(pubKeys = event.tags.mapNotNull { canonicalKey(PTag.parseKey(it)) })
                }

                else -> {
                    NONE
                }
            }

        /** A 64-hex `e`/`p` value, lowercased; null when it is not hex. */
        private fun canonicalKey(raw: String?): HexKey? = raw?.takeIf(Hex::isHexKey)?.lowercase()

        /** An assertion `d` that is exactly a key (64 lowercase hex): the address IS the subject. */
        private fun exactKey(raw: String?): HexKey? = raw?.takeIf(UserAssertionEvent::isSubjectPubKey)

        /** A coordinate as `kind:lowercase-pubkey:d`, or null when it does not parse. */
        private fun canonicalAddress(raw: String?): String? = raw?.let(Address::parse)?.let { Address.assemble(it.kind, it.pubKeyHex.lowercase(), it.dTag) }

        /** `reference -> score` for every member whose tag carries a score on the 0..100 scale. */
        private fun TagArray.memberScores(referenceOf: (Array<String>) -> String?): Map<String, Int> {
            var scores: MutableMap<String, Int>? = null
            for (tag in this) {
                val score = MemberTagFields.score(tag) ?: continue
                val reference = referenceOf(tag) ?: continue
                (scores ?: LinkedHashMap<String, Int>().also { scores = it })[reference] = score
            }
            return scores ?: emptyMap()
        }
    }
}
