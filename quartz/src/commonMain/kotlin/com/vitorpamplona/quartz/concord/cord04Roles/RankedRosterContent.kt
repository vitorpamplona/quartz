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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Roles v2 Role (CORD-04 §2, vsk 13): a named bundle of [permissions] at a [rank] key. [position]
 * is the rank's dense place among every key the fold saw (1 is the highest Role; the owner is 0),
 * which is what rank comparisons use.
 */
data class RankedRole(
    val roleId: String,
    val name: String,
    val rank: String,
    val position: Long,
    val permissions: ConcordPermissions,
    val color: Long,
    val display: Boolean,
    val mentionable: Boolean,
)

/** A Grant as the Roles v2 fold reads it (CORD-04 §2): its member and the Roles that resolve. */
data class RankedGrant(
    val member: String,
    val roleIds: List<String>,
    val controlWrap: String?,
)

/** One sealed standing a Tenure gives its member (CORD-04 §9), its rank mapped to a [position]. */
data class SealedStanding(
    val position: Long,
    val permissions: ConcordPermissions,
    val heads: Set<String>,
)

/** A channel-permission overwrite (CORD-04 §8), its bits masked to [ConcordPermissions.OVERWRITABLE]. */
data class ChannelOverwrite(
    /** The Channel's or Category's id, lowercase hex. */
    val scope: String,
    /** `everyone`, `role` or `member`. */
    val targetKind: String,
    /** The role id or member key, lowercase hex; null for `everyone`. */
    val targetId: String?,
    val allow: ConcordPermissions,
    val deny: ConcordPermissions,
) {
    companion object {
        const val EVERYONE = "everyone"
        const val ROLE = "role"
        const val MEMBER = "member"
    }
}

/**
 * The strict readers of Roles v2 Control content (CORD-04 §2, §8, §9): a field of the wrong type
 * makes the content unparseable, ids are 64 hex digits read in either case, and permissions are
 * a decimal string or a JSON integer within a u64. Ports of Armada's `roster.ts` parsers.
 */
object RankedRosterContent {
    const val MAX_LEGACY_CHANNELS = 256
    const val MAX_TENURE_ENTRIES = 16
    const val MAX_TENURE_HEADS = 512
    private const val MAX_CONTROL_WRAP_CHARS = 1024

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    fun isHex64(s: String?): Boolean = s != null && HEX64.matches(s)

    internal fun parseObject(content: String): JsonObject? =
        try {
            ConcordJson.instance.parseToJsonElement(content) as? JsonObject
        } catch (_: Exception) {
            null
        }

    private fun isNone(e: JsonElement?): Boolean = e == null || e is JsonNull

    private fun stringOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun booleanOf(e: JsonElement?): Boolean? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    /**
     * CORD-04 §3 `permissions`: ASCII digits (leading zeros allowed) up to 2^64 − 1, as a string or
     * a JSON integer. Null when malformed.
     */
    fun parsePermissionBits(e: JsonElement?): ConcordPermissions? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val text = p.content
        if (!isDigits(text)) return null
        return text.toULongOrNull()?.let { ConcordPermissions(it) }
    }

    private fun uintOf(
        e: JsonElement?,
        max: Long,
    ): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        val v = p.content.toLongOrNull() ?: return null
        return v.takeIf { it in 0..max }
    }

    /** A Role name cut to the last whole character within 64 UTF-8 bytes (CORD-04 §2). */
    fun cutName(name: String): String {
        if (name.encodeToByteArray().size <= ConcordLimits.NAME_MAX_BYTES) return name
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAtCompat(i)
            val chars = if (cp > 0xFFFF) 2 else 1
            val piece = name.substring(i, i + chars)
            val size = piece.encodeToByteArray().size
            if (bytes + size > ConcordLimits.NAME_MAX_BYTES) break
            out.append(piece)
            bytes += size
            i += chars
        }
        return out.toString()
    }

    private fun String.codePointAtCompat(i: Int): Int {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            return ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
        }
        return c.code
    }

    /** A vsk-13 Role edition's content, before its rank gets a position: a live Role, or a deletion. */
    sealed class ParsedRole {
        data class Live(
            val roleId: String,
            val name: String,
            val rank: String,
            val permissions: ConcordPermissions,
            val color: Long,
            val display: Boolean,
            val mentionable: Boolean,
        ) : ParsedRole()

        data class Deleted(
            val roleId: String,
        ) : ParsedRole()
    }

    /** A vsk-13 Role (CORD-04 §2). Null when it is not one: a wrong-typed field, or a live Role with no rank key. */
    fun parseRankedRole(content: String): ParsedRole? {
        val c = parseObject(content) ?: return null
        val roleId = stringOf(c["role_id"])?.takeIf { isHex64(it) }?.lowercase() ?: return null

        fun optOk(
            key: String,
            string: Boolean,
        ): Boolean {
            val v = c[key]
            if (isNone(v)) return true
            return if (string) stringOf(v) != null else booleanOf(v) != null
        }
        if (!optOk("name", true) || !optOk("rank", true)) return null
        if (!optOk("deleted", false) || !optOk("display", false) || !optOk("mentionable", false)) return null
        // Absent reads as none, but a present null fails (Vector `perm_decimal_string_opt`).
        val permissions = if (!c.containsKey("permissions")) ConcordPermissions.NONE else parsePermissionBits(c["permissions"]) ?: return null
        val color = if (isNone(c["color"])) 0L else uintOf(c["color"], 0xFFFFFFFFL) ?: return null
        if (booleanOf(c["deleted"]) == true) return ParsedRole.Deleted(roleId)
        val rank = stringOf(c["rank"])
        if (!RankKey.isRankKey(rank)) return null
        return ParsedRole.Live(
            roleId = roleId,
            name = cutName(stringOf(c["name"]) ?: ""),
            rank = rank!!,
            permissions = permissions,
            color = color,
            display = booleanOf(c["display"]) == true,
            mentionable = booleanOf(c["mentionable"]) == true,
        )
    }

    /** `@everyone`'s content (CORD-04 §2): its bits and the legacy Channel list (lowercased, malformed dropped, ≤ 256). */
    class Everyone(
        val permissions: ConcordPermissions,
        val legacy: List<String>?,
    )

    fun parseEveryone(content: String): Everyone? {
        val c = parseObject(content) ?: return null
        if (c.containsKey("role_id")) return null
        val permissions = parsePermissionBits(c["permissions"]) ?: return null
        val legacyEl = c["legacy"]
        if (isNone(legacyEl)) return Everyone(permissions, null)
        val arr = legacyEl as? JsonArray ?: return null
        val raw = ArrayList<String>(arr.size)
        for (e in arr) raw.add(stringOf(e) ?: return null)
        // In serde's BTreeSet order, cut before lowercasing (Vector `parse_everyone_content`).
        val legacy =
            raw
                .distinct()
                .sorted()
                .filter { isHex64(it) }
                .take(MAX_LEGACY_CHANNELS)
                .map { it.lowercase() }
                .distinct()
                .sorted()
        return Everyone(permissions, legacy)
    }

    /** A Grant (CORD-04 §2), as Vector's `GrantRead`: `member` required, `role_ids` absent or strings. */
    fun parseGrant(content: String): RankedGrant? {
        val c = parseObject(content) ?: return null
        val member = stringOf(c["member"])?.takeIf { isHex64(it) }?.lowercase() ?: return null
        val idsEl = c["role_ids"]
        val ids = ArrayList<String>()
        if (idsEl != null) {
            val arr = idsEl as? JsonArray ?: return null
            for (e in arr) ids.add(stringOf(e) ?: return null)
        }
        val wrapEl = c["control_wrap"]
        if (!isNone(wrapEl) && stringOf(wrapEl) == null) return null
        val wrap = stringOf(wrapEl)?.takeIf { it.isNotEmpty() && it.length <= MAX_CONTROL_WRAP_CHARS }
        return RankedGrant(member, ids.take(ConcordLimits.MAX_ROLES_PER_MEMBER).map { it.lowercase() }, wrap)
    }

    /** One Tenure entry as written (CORD-04 §9), its rank still a key. */
    class TenureEntry(
        val rank: String,
        val permissions: ConcordPermissions,
        val heads: List<String>,
    )

    class Tenure(
        val member: String,
        val sealed: List<TenureEntry>,
    )

    /** A Tenure; entries with a malformed rank key, and malformed heads, are dropped, then capped. */
    fun parseTenure(content: String): Tenure? {
        val c = parseObject(content) ?: return null
        val member = stringOf(c["member"])?.lowercase() ?: return null
        val rawEl = c["sealed"]
        val raw = if (rawEl == null) JsonArray(emptyList()) else rawEl as? JsonArray ?: return null
        val sealed = ArrayList<TenureEntry>()
        for (item in raw) {
            val e = item as? JsonObject ?: return null
            val rank = stringOf(e["rank"]) ?: return null
            val permissions = parsePermissionBits(e["permissions"]) ?: return null
            val headsEl = e["heads"]
            val heads = ArrayList<String>()
            if (headsEl != null) {
                val arr = headsEl as? JsonArray ?: return null
                for (h in arr) heads.add(stringOf(h) ?: return null)
            }
            if (!RankKey.isRankKey(rank)) continue
            sealed.add(TenureEntry(rank, permissions, heads.filter { isHex64(it) }.map { it.lowercase() }.take(MAX_TENURE_HEADS)))
        }
        return Tenure(member, sealed.take(MAX_TENURE_ENTRIES))
    }

    /** An overwrite (CORD-04 §8), its bits masked to the overwritable set as it parses. */
    fun parseOverwrite(content: String): ChannelOverwrite? {
        val c = parseObject(content) ?: return null
        val scope = stringOf(c["scope"])?.takeIf { isHex64(it) }?.lowercase() ?: return null
        val t = c["target"] as? JsonObject ?: return null
        val kind = stringOf(t["kind"])
        val id = t["id"]
        val targetId: String?
        when {
            kind == ChannelOverwrite.EVERYONE && isNone(id) -> targetId = null
            (kind == ChannelOverwrite.ROLE || kind == ChannelOverwrite.MEMBER) && isHex64(stringOf(id)) -> targetId = stringOf(id)!!.lowercase()
            else -> return null
        }
        val allow = parsePermissionBits(c["allow"]) ?: return null
        val deny = parsePermissionBits(c["deny"]) ?: return null
        return ChannelOverwrite(scope, kind!!, targetId, allow and ConcordPermissions.OVERWRITABLE, deny and ConcordPermissions.OVERWRITABLE)
    }

    /** A Channel's sync (CORD-04 §8): its channel and the Category it takes overwrites from, if any. */
    fun parseSync(content: String): Pair<String, String?>? {
        val c = parseObject(content) ?: return null
        val channel = stringOf(c["channel"])?.takeIf { isHex64(it) }?.lowercase() ?: return null
        val sync = c["sync"]
        if (!isNone(sync) && stringOf(sync) == null) return null
        val category = stringOf(sync)?.takeIf { isHex64(it) }?.lowercase()
        return channel to category
    }
}
