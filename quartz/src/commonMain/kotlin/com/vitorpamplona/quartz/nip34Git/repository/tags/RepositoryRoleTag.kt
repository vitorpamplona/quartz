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
package com.vitorpamplona.quartz.nip34Git.repository.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.Hex

/** A NIP-34 repository role: lead forwarding (`M`), co-maintainer (`m`) or moderator (`o`). */
enum class RepositoryRole(
    val code: String,
) {
    LEAD("M"),
    MAINTAINER("m"),
    MODERATOR("o"),
    ;

    /** `M` and `m` both make the subject a maintainer; `o` only a moderator. */
    val isMaintainer: Boolean get() = this != MODERATOR

    companion object {
        fun of(code: String): RepositoryRole? =
            when (code) {
                "M" -> LEAD
                "m" -> MAINTAINER
                "o" -> MODERATOR
                else -> null
            }
    }
}

/**
 * One indexed role record from a repository announcement:
 *
 *     ["M"|"m"|"o", "<pubkey>", <start>, <end>, <start>, <end>, ...]
 *
 * [boundaries] alternate start and end timestamps; `null` stands for the literal `defer`, which is
 * only valid as the final end. No boundaries, or an odd number of them, means the role is active.
 */
@Immutable
data class RepositoryRoleRecord(
    val role: RepositoryRole,
    val subject: HexKey,
    val boundaries: List<Long?>,
) {
    val isActive: Boolean get() = boundaries.isEmpty() || boundaries.size % 2 == 1

    /** The start of the open interval, when the role is active and that start is signed. */
    val activeSince: Long? get() = if (isActive) boundaries.lastOrNull() else null

    /** True when the history ends in `defer`: copied third-party history whose end is unknown. */
    val endsInDefer: Boolean get() = boundaries.isNotEmpty() && boundaries.last() == null

    /** A reopened interval (a third or later boundary) needs a fresh acknowledgement. */
    val requiresFreshAcceptance: Boolean get() = boundaries.size >= 3
}

/** Codec for the indexed role tags `M`, `m` and `o` of a NIP-34 repository announcement. */
class RepositoryRoleTag {
    companion object {
        const val DEFER = "defer"

        fun isTagged(tag: Array<String>): Boolean = tag.isNotEmpty() && RepositoryRole.of(tag[0]) != null

        /** An `M` or `m` tag, well-formed or not. */
        fun isMaintainerTagged(tag: Array<String>): Boolean = tag.isNotEmpty() && RepositoryRole.of(tag[0])?.isMaintainer == true

        /** A role tag of any of [roles] about [subject], well-formed or not: it still says the author spoke about them. */
        fun isTaggedFor(
            tag: Array<String>,
            subject: HexKey,
            roles: Collection<RepositoryRole> = RepositoryRole.entries,
        ): Boolean = tag.size > 1 && tag[1] == subject && RepositoryRole.of(tag[0])?.let { it in roles } == true

        /**
         * The `role:subject` a tag speaks about (`m:<pubkey>`), even when its boundaries are malformed:
         * a malformed or repeated record still disputes that role. Null without a role or a valid pubkey.
         */
        fun parseKey(tag: Array<String>): String? {
            if (tag.size < 2) return null
            val role = RepositoryRole.of(tag[0]) ?: return null
            val subject = tag[1]
            if (subject.length != 64 || !Hex.isHex64(subject)) return null
            return role.code + ":" + subject
        }

        /** The record, or null when the role, the pubkey or any boundary is malformed. */
        fun parse(tag: Array<String>): RepositoryRoleRecord? {
            if (tag.size < 2) return null
            val role = RepositoryRole.of(tag[0]) ?: return null
            val subject = tag[1]
            if (subject.length != 64 || !Hex.isHex64(subject)) return null

            val boundaries = ArrayList<Long?>(tag.size - 2)
            for (index in 2 until tag.size) {
                val value = tag[index]
                val position = index - 2
                if (value == DEFER) {
                    // Only an end (odd position) that is also the last boundary may defer.
                    if (position % 2 != 1 || index != tag.lastIndex) return null
                    boundaries.add(null)
                } else {
                    if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
                    boundaries.add(value.toLongOrNull() ?: return null)
                }
            }
            return RepositoryRoleRecord(role, subject, boundaries)
        }

        fun assemble(record: RepositoryRoleRecord): Array<String> = (listOf(record.role.code, record.subject) + record.boundaries.map { it?.toString() ?: DEFER }).toTypedArray()
    }
}
