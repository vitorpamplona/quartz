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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.concord.cord02Community.ConcordChannel
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.RankKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * The order a Community's sidebar lists its Channels in (CORD-03 §2).
 *
 * A Channel may carry `position`, a rank key (CORD-04 §3's alphabet, [RankKey]): Channels with one
 * come first, compared byte by byte, then every Channel without one. A `position` of another type,
 * or one that isn't a rank key, reads as absent.
 *
 * Before the spec had `position`, the reference client (Armada) wrote its own advisory order into
 * the Channel's `custom` object, `custom["armada.order"] = {"position": <integer ≥ 0>}`, and still
 * reads only that. A Channel with no spec `position` sorts by that legacy one next, so a Community
 * Armada ordered lists the same here, and one never ordered at all reads alphabetically.
 *
 * Ties, and Channels with neither, sort by `name` compared as UTF-8 bytes, then by id. Categories
 * (CORD-03 §4), which group Channels before this order applies, are not folded yet.
 */
object ConcordChannelOrder {
    /** The `custom` key Armada keeps its legacy Channel order under. */
    const val ARMADA_ORDER_KEY = "armada.order"

    private const val MAX_SAFE_INTEGER = 9007199254740991.0

    /** The CORD-03 §2 `position` of a Channel's [content] (its edition, or its opened seal), or null when absent or malformed. */
    fun positionOf(content: JsonObject): String? {
        val p = content["position"] as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.takeIf { RankKey.isRankKey(it) }
    }

    /**
     * Armada's legacy position in a Channel's [content]: `custom["armada.order"].position`, a JSON
     * integer from 0 to 2^53 − 1 (a safe integer, as JavaScript reads it), or null when absent or
     * anything else.
     */
    fun armadaOrderOf(content: JsonObject): Long? {
        val custom = content["custom"] as? JsonObject ?: return null
        val order = custom[ARMADA_ORDER_KEY] as? JsonObject ?: return null
        val p = order["position"] as? JsonPrimitive ?: return null
        if (p.isString) return null
        val value = p.doubleOrNull ?: return null
        if (!value.isFinite() || value < 0 || value > MAX_SAFE_INTEGER || value != floor(value)) return null
        return value.toLong()
    }

    /** [positionOf] and [armadaOrderOf] of a plain Channel edition's JSON [content]; nulls when it isn't a JSON object. */
    fun ordersOf(content: String): Pair<String?, Long?> {
        val obj =
            try {
                ConcordJson.instance.parseToJsonElement(content) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null to null
        return positionOf(obj) to armadaOrderOf(obj)
    }

    /** Unsigned byte order of two strings' UTF-8 encodings. */
    fun compareUtf8(
        a: String,
        b: String,
    ): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    /**
     * Compares two Channels' sort keys: a spec [position] first (byte order; absent after present),
     * then Armada's legacy [armadaOrder] (absent after present), then [name] as UTF-8 bytes, then [id].
     */
    fun compare(
        position: String?,
        armadaOrder: Long?,
        name: String,
        id: String,
        otherPosition: String?,
        otherArmadaOrder: Long?,
        otherName: String,
        otherId: String,
    ): Int {
        if (position != otherPosition) {
            if (position == null) return 1
            if (otherPosition == null) return -1
            return RankKey.compare(position, otherPosition)
        }
        if (armadaOrder != otherArmadaOrder) {
            if (armadaOrder == null) return 1
            if (otherArmadaOrder == null) return -1
            return armadaOrder.compareTo(otherArmadaOrder)
        }
        val byName = compareUtf8(name, otherName)
        if (byName != 0) return byName
        return id.lowercase().compareTo(otherId.lowercase())
    }

    /** The sidebar order of folded Channels. */
    val comparator: Comparator<ConcordChannel> =
        Comparator { a, b ->
            compare(a.position, a.armadaOrder, a.definition.name, a.channelIdHex, b.position, b.armadaOrder, b.definition.name, b.channelIdHex)
        }

    /** [channels] in sidebar order. */
    fun sorted(channels: Collection<ConcordChannel>): List<ConcordChannel> = channels.sortedWith(comparator)
}
