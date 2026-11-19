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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip19Bech32.entities.Entity
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.SiteSnapshotEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A Namecoin-selected nSite target, before relay retrieval and manifest validation. */
sealed interface NamecoinNsiteTarget {
    /** The current root manifest ([RootSiteEvent.KIND]) by [author]. */
    data class Root(
        val author: HexKey,
        val relays: List<NormalizedRelayUrl> = emptyList(),
    ) : NamecoinNsiteTarget {
        val kind: Int get() = RootSiteEvent.KIND
    }

    /** The current named manifest at kind [kind], [author], and exact [dTag] coordinate. */
    data class Named(
        val author: HexKey,
        val dTag: String,
        val relays: List<NormalizedRelayUrl> = emptyList(),
    ) : NamecoinNsiteTarget {
        val kind: Int get() = NamedSiteEvent.KIND
    }

    /**
     * One exact event id, including all retrieval constraints carried by its pointer.
     *
     * A null [author] or [kind] is unconstrained by the pointer, not by the nSite profile: the
     * retrieved event must still match [allowedKinds] and every non-null hint. Callers must not
     * replace an exact kind 15128/35128 selection with the current event at its coordinate.
     */
    data class ExactEvent(
        val eventId: HexKey,
        val author: HexKey? = null,
        val kind: Int? = null,
        val relays: List<NormalizedRelayUrl> = emptyList(),
    ) : NamecoinNsiteTarget {
        val allowedKinds: Set<Int> get() = ALLOWED_KINDS

        companion object {
            val ALLOWED_KINDS: Set<Int> =
                setOf(RootSiteEvent.KIND, NamedSiteEvent.KIND, SiteSnapshotEvent.KIND)
        }
    }
}

/** Successful Namecoin-to-nSite mapping and its normalized browser-name context. */
data class NamecoinNsiteResult(
    val target: NamecoinNsiteTarget,
    /** The apex Namecoin name that was resolved (for example, d/example). */
    val namecoinName: String,
    /** The selected dotted subdomain, or "_" for the apex. */
    val localPart: String = "_",
)

/** Detailed outcome of selecting an nSite target from a .bit hostname. */
sealed class NamecoinNsiteResolveOutcome {
    data class Success(
        val result: NamecoinNsiteResult,
    ) : NamecoinNsiteResolveOutcome()

    /** No mapping exists at the selected record, or the requested subdomain selector is missing. */
    data class NoNsiteMapping(
        val name: String,
    ) : NamecoinNsiteResolveOutcome()

    /** A selected nsite or legacy identity mapping is present but invalid or ambiguous. */
    data class MappingFailure(
        val name: String,
        val error: String,
    ) : NamecoinNsiteResolveOutcome()

    /** An existing Namecoin lookup/traversal failure, preserved without remapping. */
    data class NamecoinFailure(
        val cause: NamecoinResolveOutcome,
    ) : NamecoinNsiteResolveOutcome()
}

/** Synchronous mapping of an already selected Namecoin Domain Name Object. */
internal object NamecoinNsiteMapper {
    private val BARE_NSITE_REGEX = Regex("^[a-z0-9-]{1,13}$")

    fun map(
        value: JsonObject,
        namecoinName: String,
        localPart: String,
    ): NamecoinNsiteResolveOutcome {
        val nostrObject = value["nostr"] as? JsonObject
        val nestedNsitePresent = nostrObject?.containsKey("nsite") == true
        val nsitePresent = nestedNsitePresent || value.containsKey("nsite")

        if (nsitePresent) {
            val nsite = if (nestedNsitePresent) nostrObject["nsite"] else value["nsite"]
            val pointer =
                (nsite as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return mappingFailure(namecoinName, "nsite must be a JSON string")

            NamecoinNsiteNip19Decoder.decode(pointer)?.let { decoded ->
                val target =
                    directTarget(decoded)
                        ?: return mappingFailure(namecoinName, "NIP-19 pointer is not a valid nSite target")
                return success(target, namecoinName, localPart)
            }

            if (!BARE_NSITE_REGEX.matches(pointer) || pointer.endsWith('-')) {
                return mappingFailure(namecoinName, "nsite is neither a canonical NIP-19 pointer nor a bare named-site identifier")
            }

            return when (val selected = selectLegacyIdentity(value)) {
                is LegacySelection.Success ->
                    success(
                        NamecoinNsiteTarget.Named(selected.author, pointer, selected.relays),
                        namecoinName,
                        localPart,
                    )

                is LegacySelection.Failure -> mappingFailure(namecoinName, selected.error)
                is LegacySelection.Absent ->
                    mappingFailure(namecoinName, "bare named-site identifier requires a legacy nostr author")
            }
        }

        return when (val selected = selectLegacyIdentity(value)) {
            is LegacySelection.Success ->
                success(
                    NamecoinNsiteTarget.Root(selected.author, selected.relays),
                    namecoinName,
                    localPart,
                )

            is LegacySelection.Failure -> mappingFailure(namecoinName, selected.error)
            is LegacySelection.Absent -> NamecoinNsiteResolveOutcome.NoNsiteMapping(namecoinName)
        }
    }

    private fun directTarget(entity: Entity): NamecoinNsiteTarget? =
        when (entity) {
            is NPub -> NamecoinNsiteTarget.Root(entity.hex)
            is NProfile -> NamecoinNsiteTarget.Root(entity.hex, entity.relay)
            is NAddress ->
                when {
                    entity.kind == RootSiteEvent.KIND && entity.dTag.isEmpty() ->
                        NamecoinNsiteTarget.Root(entity.author, entity.relay)

                    entity.kind == NamedSiteEvent.KIND && entity.dTag.isNotEmpty() ->
                        NamecoinNsiteTarget.Named(entity.author, entity.dTag, entity.relay)

                    else -> null
                }

            is NEvent ->
                if (entity.kind != null && entity.kind !in NamecoinNsiteTarget.ExactEvent.ALLOWED_KINDS) {
                    null
                } else {
                    NamecoinNsiteTarget.ExactEvent(entity.hex, entity.author, entity.kind, entity.relay)
                }

            is NNote -> NamecoinNsiteTarget.ExactEvent(entity.hex)
            else -> null
        }

    private sealed interface LegacySelection {
        data object Absent : LegacySelection

        data class Success(
            val author: HexKey,
            val relays: List<NormalizedRelayUrl>,
        ) : LegacySelection

        data class Failure(
            val error: String,
        ) : LegacySelection
    }

    private fun selectLegacyIdentity(value: JsonObject): LegacySelection {
        val nostr = value["nostr"] ?: return LegacySelection.Absent
        if (nostr is JsonPrimitive) {
            val author = parsePubkey(nostr) ?: return LegacySelection.Failure("nostr is not a valid legacy pubkey")
            return LegacySelection.Success(author, emptyList())
        }
        val obj = nostr as? JsonObject ?: return LegacySelection.Failure("nostr must be a pubkey string or object")

        if (obj.containsKey("names")) {
            val names =
                obj["names"] as? JsonObject
                    ?: return LegacySelection.Failure("nostr.names must be an object")
            val selected =
                if (names.containsKey("_")) {
                    names["_"]
                } else {
                    val nonRoot = names.entries.filter { it.key != "_" }
                    if (nonRoot.size != 1) {
                        return LegacySelection.Failure("nostr.names must contain _ or exactly one non-_ entry")
                    }
                    nonRoot.single().value
                }
            val author =
                parsePubkey(selected)
                    ?: return LegacySelection.Failure("selected nostr.names entry is not a valid pubkey")
            val relays =
                relaysForNamesAuthor(obj["relays"], author)
                    ?: return LegacySelection.Failure("nostr.relays contains an invalid relay hint")
            return LegacySelection.Success(author, relays)
        }

        if (obj.containsKey("pubkey")) {
            val author =
                parsePubkey(obj["pubkey"])
                    ?: return LegacySelection.Failure("nostr.pubkey is not a valid pubkey")
            val relays =
                parseRelayArray(obj["relays"])
                    ?: return LegacySelection.Failure("nostr.relays contains an invalid relay hint")
            return LegacySelection.Success(author, relays)
        }

        return LegacySelection.Absent
    }

    private fun parsePubkey(element: JsonElement?): HexKey? {
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return value.takeIf { NamecoinNameResolver.isValidPubkey(it) }?.lowercase()
    }

    private fun relaysForNamesAuthor(
        element: JsonElement?,
        author: HexKey,
    ): List<NormalizedRelayUrl>? {
        if (element == null) return emptyList()
        val relays = element as? JsonObject ?: return null
        if (!relays.containsKey(author)) return emptyList()
        return parseRelayArray(relays[author])
    }

    private fun parseRelayArray(element: JsonElement?): List<NormalizedRelayUrl>? {
        if (element == null) return emptyList()
        val array = element as? JsonArray ?: return null
        val relays = ArrayList<NormalizedRelayUrl>(array.size)
        for (item in array) {
            val relay = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!relay.all { it.code in 33..126 }) return null
            relays += RelayUrlNormalizer.normalizeHintOrNull(relay) ?: return null
        }
        return relays
    }

    private fun success(
        target: NamecoinNsiteTarget,
        namecoinName: String,
        localPart: String,
    ): NamecoinNsiteResolveOutcome.Success = NamecoinNsiteResolveOutcome.Success(NamecoinNsiteResult(target, namecoinName, localPart))

    private fun mappingFailure(
        namecoinName: String,
        error: String,
    ): NamecoinNsiteResolveOutcome.MappingFailure = NamecoinNsiteResolveOutcome.MappingFailure(namecoinName, error)
}
