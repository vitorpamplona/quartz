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

import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordStrictJson
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRosterContent
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.CanonicalDecimal
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.utils.Log
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A Private Channel's **sealed** metadata as the Control Plane carries it (CORD-03 §2): only
 * `private`, the channel [epoch] the seal is under and the [sealed] NIP-44 payload are plaintext
 * (plus `deleted` on a deletion). [prevHex] is the edition's own `ep`, which the sealed plaintext
 * must name for the seal to open.
 */
data class SealedChannelMeta(
    val epoch: Long,
    val sealed: String,
    val deleted: Boolean,
    val prevHex: String?,
)

/** What a sealed Channel edition opens to (CORD-03 §2): its [name], [creator], and every other field it carries in [fields]. */
data class OpenedChannelSeal(
    val name: String,
    val creator: String?,
    val publicUntil: Long?,
    val fields: JsonObject,
)

/**
 * Sealed Private Channel metadata (CORD-03 §2, Roles v2): everything but what every member needs
 * to resolve access is NIP-44 encrypted under the Channel's own conversation key at the named
 * channel epoch, so a member without the key sees that a Private Channel exists, never its name.
 */
object ConcordChannelSeal {
    private const val TAG = "ConcordChannelSeal"

    /** A JSON object as `JSON.parse` reads it (Armada `readChannelContent`, `openSealedChannel`): strict, a repeated key keeping its last value. */
    private fun jsonObject(text: String): JsonObject? = ConcordStrictJson.parse(text, allowLoneSurrogates = true)?.value as? JsonObject

    /**
     * The sealed form of a Channel edition's [content], or null when it is the plain form or
     * breaks the sealed one. [prevHex] is the edition's `ep` (lowercase hex), null on a first edition.
     */
    fun readSealed(
        content: String,
        prevHex: String?,
    ): SealedChannelMeta? {
        val c = jsonObject(content) ?: return null
        val deletedEl = c["deleted"]
        if (deletedEl != null && (deletedEl !is JsonPrimitive || deletedEl.isString || deletedEl.booleanOrNull == null)) return null
        if ((c["private"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } != true) return null
        val sealed = (c["sealed"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val epochText = (c["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val epoch = CanonicalDecimal.parse(epochText) ?: return null
        return SealedChannelMeta(epoch, sealed, (deletedEl as? JsonPrimitive)?.booleanOrNull == true, prevHex?.lowercase())
    }

    /**
     * Opens [sealed] with the Channel's conversation key at the seal's epoch ([conversationKey],
     * `group_key("concord/channel", channel_key, channel_id, epoch).conv_key`), or null when it
     * won't open, names another predecessor than the edition's own (a replay), or carries no name.
     */
    fun open(
        sealed: SealedChannelMeta,
        conversationKey: ByteArray,
    ): OpenedChannelSeal? {
        val plain =
            try {
                Nip44.v2.decrypt(sealed.sealed, conversationKey)
            } catch (e: Exception) {
                Log.d(TAG) { "sealed channel metadata did not open: ${e.message}" }
                return null
            }
        val obj = jsonObject(plain) ?: return null
        val prev =
            (obj["prev"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { RankedRosterContent.isHex64(it) }
                ?.lowercase()
        if (prev != sealed.prevHex) return null
        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val cut = RankedRosterContent.cutName(name)
        if (cut.isEmpty()) return null
        val custom = obj["custom"]
        if (custom != null && custom !is JsonObject) return null
        val creator = (obj["creator"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { RankedRosterContent.isHex64(it) && it == it.lowercase() }
        val publicUntil = (obj["public_until"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val fields = JsonObject(obj.filterKeys { it != "prev" && it != "creator" && it != "public_until" && it != "private" && it != "deleted" } + ("name" to JsonPrimitive(cut)))
        return OpenedChannelSeal(cut, creator, publicUntil, fields)
    }

    /**
     * The sealed content of an edition carrying [fields] (`name` required, every other plain
     * field the Channel carries, unknown ones included) under [conversationKey] at channel [epoch],
     * naming [prevHex] (the edition's `ep`, null on a first edition) and, when given, its [creator]
     * and the [publicUntil] of a conversion.
     */
    fun seal(
        fields: JsonObject,
        conversationKey: ByteArray,
        epoch: Long,
        prevHex: String?,
        creator: String? = null,
        publicUntil: Long? = null,
    ): String {
        require((fields["name"] as? JsonPrimitive)?.isString == true) { "a sealed Channel needs a name" }
        val plain = LinkedHashMap(fields.filterKeys { it != "private" && it != "deleted" && it != "prev" && it != "creator" && it != "public_until" })
        creator?.let { plain["creator"] = JsonPrimitive(it.lowercase()) }
        publicUntil?.let { plain["public_until"] = JsonPrimitive(it) }
        plain["prev"] = prevHex?.let { JsonPrimitive(it.lowercase()) } ?: JsonNull
        val sealed = Nip44.v2.encrypt(ConcordJson.instance.encodeToString(JsonObject.serializer(), JsonObject(plain)), conversationKey).encodePayload()
        val out = linkedMapOf("private" to JsonPrimitive(true), "epoch" to JsonPrimitive(epoch.toString()), "sealed" to JsonPrimitive(sealed))
        return ConcordJson.instance.encodeToString(JsonObject.serializer(), JsonObject(out))
    }
}
