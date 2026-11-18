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

import kotlin.jvm.JvmInline

/**
 * A member's Concord permission set (CORD-04): a `u64` bitfield of frozen bit
 * positions. On the wire it is encoded as a **decimal string** (not a JSON
 * number) to prevent floating-point corruption of the high bits.
 *
 * Bit positions are frozen forever — new permissions claim the next free bit,
 * retired ones are burned and never reused. Bit 7 is retired; bits 10–12 are
 * reserved.
 *
 * "A member's effective permissions are the union of their Roles' bits."
 */
@JvmInline
value class ConcordPermissions(
    val bits: ULong,
) {
    fun has(bit: Int): Boolean = (bits and (1uL shl bit)) != 0uL

    /** True if every bit set in [other] is also set here (used for role-vs-actor checks). */
    fun hasAll(other: ConcordPermissions): Boolean = (bits and other.bits) == other.bits

    /** True if at least one bit set in [other] is also set here. */
    fun hasAny(other: ConcordPermissions): Boolean = (bits and other.bits) != 0uL

    infix fun union(other: ConcordPermissions): ConcordPermissions = ConcordPermissions(bits or other.bits)

    fun with(bit: Int): ConcordPermissions = ConcordPermissions(bits or (1uL shl bit))

    fun without(bit: Int): ConcordPermissions = ConcordPermissions(bits and (1uL shl bit).inv())

    /** The bits set both here and in [other]. */
    infix fun and(other: ConcordPermissions): ConcordPermissions = ConcordPermissions(bits and other.bits)

    /** The bits set here and not in [other]. */
    infix fun minus(other: ConcordPermissions): ConcordPermissions = ConcordPermissions(bits and other.bits.inv())

    /** True when no bit is set. */
    fun isEmpty(): Boolean = bits == 0uL

    /** The wire encoding: an unsigned decimal string with no leading zeros. */
    fun toWire(): String = bits.toString()

    companion object {
        val NONE = ConcordPermissions(0uL)

        /** Every bit set — the owner's implicit, supreme permission set. */
        val ALL = ConcordPermissions(ULong.MAX_VALUE)

        // Frozen bit positions (CORD-04 §Permission Bits).
        const val MANAGE_ROLES = 0
        const val MANAGE_CHANNELS = 1
        const val MANAGE_METADATA = 2
        const val KICK = 3
        const val BAN = 4
        const val MANAGE_MESSAGES = 5
        const val CREATE_INVITE = 6

        // bit 7 retired — never reuse

        const val VIEW_AUDIT_LOG = 8
        const val MENTION_EVERYONE = 9

        // bits 10 and 12 reserved (MANAGE_EMOJI, MANAGE_EVENTS)

        const val PIN_MESSAGES = 11

        // Roles v2 (CORD-04 §3): bits 13 to 26 are channel permissions, which overwrites may carry.
        const val VIEW_CHANNEL = 13
        const val SEND_MESSAGES = 14
        const val SEND_IN_THREADS = 15
        const val ATTACH_FILES = 16
        const val ADD_REACTIONS = 17
        const val READ_MESSAGE_HISTORY = 18
        const val CONNECT = 19
        const val SPEAK = 20
        const val VIDEO = 21
        const val PRIORITY_SPEAKER = 22
        const val MUTE_MEMBERS = 23
        const val DEAFEN_MEMBERS = 24
        const val MOVE_MEMBERS = 25

        /** Timeouts (CORD-04 §8). */
        const val MODERATE_MEMBERS = 26

        // bit 27 reserved (BYPASS_SLOWMODE)

        /** Another member's Nickname (CORD-04 §10). Server-wide. */
        const val MANAGE_NICKNAMES = 28

        /** One's own Nickname (CORD-04 §10). Server-wide. */
        const val CHANGE_NICKNAME = 29

        // bits 30 and 31 reserved (CREATE_PRIVATE_THREADS, MANAGE_THREADS)

        /**
         * The bits an overwrite may touch (CORD-04 §8): MANAGE_MESSAGES and bits 13 to 26, the mask
         * `134209568`. A reader clears every other bit of an overwrite as it parses it.
         */
        val OVERWRITABLE: ConcordPermissions get() = ConcordPermissions(134209568uL)

        /**
         * What the switching `@everyone` holds (CORD-04 §3): the chat and voice basics and the
         * member's own Nickname.
         */
        val EVERYONE_DEFAULT: ConcordPermissions
            get() = of(VIEW_CHANNEL, SEND_MESSAGES, SEND_IN_THREADS, ATTACH_FILES, ADD_REACTIONS, READ_MESSAGE_HISTORY, CONNECT, SPEAK, VIDEO, CHANGE_NICKNAME)

        /**
         * The **staff** bits (CORD-04 §3): the six permissions whose actions land as
         * Control Plane editions. A member holding any of them, plus always the
         * owner, is staff — the set that holds the `control_root` (CORD-02 §2).
         * `KICK` writes to the Guestbook and `MANAGE_MESSAGES` to Chat planes, so
         * neither is here. The spec's list is **normative**: a future CORD
         * introducing a permission whose actions are Control editions MUST amend it
         * explicitly, so no implementation judges membership of the set for itself —
         * extend this constant only when the spec's list changes.
         */
        val STAFF_BITS: ConcordPermissions get() = of(MANAGE_ROLES, MANAGE_CHANNELS, MANAGE_METADATA, BAN, CREATE_INVITE, PIN_MESSAGES)

        fun of(vararg bits: Int): ConcordPermissions {
            var acc = 0uL
            for (b in bits) acc = acc or (1uL shl b)
            return ConcordPermissions(acc)
        }

        /**
         * Parses the wire form — a decimal `u64` string. Returns [NONE] for a
         * blank value; throws [NumberFormatException] for a malformed or
         * out-of-range one so a corrupt edition is rejected rather than folded.
         */
        fun fromWire(wire: String): ConcordPermissions {
            if (wire.isBlank()) return NONE
            return ConcordPermissions(wire.trim().toULong())
        }

        /** Non-throwing variant: returns null on a malformed value. */
        fun fromWireOrNull(wire: String): ConcordPermissions? =
            try {
                fromWire(wire)
            } catch (_: NumberFormatException) {
                null
            }
    }
}
