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
package com.vitorpamplona.quartz.nip19Bech32

import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.hints.types.EventIdHint
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip19Bech32.entities.Entity
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEmbed
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

fun NEvent.toEventHint() = relay.map { EventIdHint(hex, it) }

fun NAddress.toAddressHint() = relay.map { AddressHint(aTag(), it) }

fun NProfile.toPubKeyHint() = relay.map { PubKeyHint(hex, it) }

// The list readers below run in hint providers, on every relay copy of every event that can cite
// NIP-19 entities, and almost all of them cite none. Each has a `…To(destination)` form that
// appends to a caller's list, creating it on the first match and returning it (`null` while
// nothing matched; see `fastMapNotNullTo`), and the plain form returns `emptyList()` without
// allocating when nothing matches.

fun List<Entity>.eventHints(): List<EventIdHint> = eventHintsTo(null) ?: emptyList()

fun List<Entity>.eventHintsTo(destination: ArrayList<EventIdHint>?): ArrayList<EventIdHint>? {
    var out = destination
    for (i in indices) {
        val entity = this[i]
        if (entity is NEvent) {
            val relays = entity.relay
            for (r in relays.indices) {
                if (out == null) out = ArrayList(4)
                out.add(EventIdHint(entity.hex, relays[r]))
            }
        }
    }
    return out
}

fun List<Entity>.eventIds(): List<HexKey> = eventIdsTo(null) ?: emptyList()

fun List<Entity>.eventIdsTo(destination: ArrayList<HexKey>?): ArrayList<HexKey>? {
    var out = destination
    for (i in indices) {
        val id =
            when (val entity = this[i]) {
                is NEvent -> entity.hex
                is NNote -> entity.hex
                is NEmbed -> if (entity.isVerified) entity.event.id else null
                else -> null
            } ?: continue
        if (out == null) out = ArrayList(4)
        out.add(id)
    }
    return out
}

fun List<Entity>.addressHints(): List<AddressHint> = addressHintsTo(null) ?: emptyList()

fun List<Entity>.addressHintsTo(destination: ArrayList<AddressHint>?): ArrayList<AddressHint>? {
    var out = destination
    for (i in indices) {
        val entity = this[i]
        if (entity is NAddress) {
            val relays = entity.relay
            if (relays.isEmpty()) continue
            val address = entity.aTag()
            for (r in relays.indices) {
                if (out == null) out = ArrayList(4)
                out.add(AddressHint(address, relays[r]))
            }
        }
    }
    return out
}

fun List<Entity>.addressIds(): List<String> = addressIdsTo(null) ?: emptyList()

fun List<Entity>.addressIdsTo(destination: ArrayList<String>?): ArrayList<String>? {
    var out = destination
    for (i in indices) {
        val address =
            when (val entity = this[i]) {
                is NAddress -> entity.aTag()
                is NEmbed -> if (entity.event is AddressableEvent && entity.isVerified) entity.event.addressTag() else null
                else -> null
            } ?: continue
        if (out == null) out = ArrayList(4)
        out.add(address)
    }
    return out
}

fun List<Entity>.pubKeyHints(): List<PubKeyHint> = pubKeyHintsTo(null) ?: emptyList()

fun List<Entity>.pubKeyHintsTo(destination: ArrayList<PubKeyHint>?): ArrayList<PubKeyHint>? {
    var out = destination
    for (i in indices) {
        val entity = this[i]
        if (entity is NProfile) {
            val relays = entity.relay
            for (r in relays.indices) {
                if (out == null) out = ArrayList(4)
                out.add(PubKeyHint(entity.hex, relays[r]))
            }
        }
    }
    return out
}

/**
 * The pubkeys cited as `npub` / `nprofile`. An `nsec` is deliberately NOT here: its hex is a
 * PRIVATE key, so reporting a pasted one as a "linked pubkey" would publish the secret to every
 * index, hint store and relay filter that consumes this list.
 */
fun List<Entity>.pubKeys(): List<HexKey> = pubKeysTo(null) ?: emptyList()

/** [pubKeys] appended to [destination]; see the note above [eventHints]. Never includes an `nsec`. */
fun List<Entity>.pubKeysTo(destination: ArrayList<HexKey>?): ArrayList<HexKey>? {
    var out = destination
    for (i in indices) {
        val key =
            when (val entity = this[i]) {
                is NProfile -> entity.hex
                is NPub -> entity.hex
                else -> null
            } ?: continue
        if (out == null) out = ArrayList(4)
        out.add(key)
    }
    return out
}
