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

/**
 * The Control Plane entity sub-kinds (the `vsk` tag on a kind-3308 edition),
 * pinned to the Concord v2 reference client (`concord-v2/lib/kinds.ts`).
 *
 * On the wire the `vsk` value is a decimal string with no leading zeros
 * ([wire]); [ControlEntityKind.of] maps that string back to the enum.
 */
enum class ControlEntityKind(
    val wire: String,
) {
    /** Community metadata (name, icon, description). */
    METADATA("0"),

    /** A Role definition (name, position, permissions, scope, color). */
    ROLE("1"),

    /** A Channel definition (name, private flag, voice flag). */
    CHANNEL("2"),

    /** A member→roles Grant. */
    GRANT("3"),

    /** The community-wide Banlist. */
    BANLIST("4"),

    /** A live invite-link registry entry (Public/Private source of truth). */
    INVITE_LIVE("6"),

    /** The aggregate invite registry. */
    INVITE_REGISTRY("8"),

    /** A revoked invite-link marker. */
    INVITE_REVOKED("9"),

    /** The dissolution tombstone (terminal). */
    DISSOLVED("10"),

    /** A Channel's Pin List (CORD-04 §7), gated by PIN_MESSAGES. */
    PIN_LIST("11"),

    /** A Roles v2 Role or `@everyone` (CORD-04 §2), replacing [ROLE] once a Community activates it. */
    RANKED_ROLE("13"),

    /** A channel-permission overwrite (CORD-04 §8). */
    ACL("14"),

    /** A Category (CORD-03 §4). */
    CATEGORY("15"),

    /** A member's Tenure from one sealer (CORD-04 §9). */
    TENURE("16"),

    /** A Channel's sync to a Category's overwrites (CORD-04 §8). */
    SYNC("17"),

    /** A restricted Channel's Writers ledger (CORD-03 §1). */
    WRITERS("18"),
    ;

    /** True for the kinds no client before Roles v2 wrote: always marked, in every Community (CORD-04 §1). */
    val alwaysMarked: Boolean get() = wire.toInt() >= 13

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun of(wire: String): ControlEntityKind? = byWire[wire]
    }
}
