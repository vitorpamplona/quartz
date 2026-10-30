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
package com.vitorpamplona.quartz.nip86RelayManagement.server

import com.vitorpamplona.quartz.nip43RelayMembers.roles.RelayRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A relay that persists its NIP-86 state must get ALL of it back on restart: a store that came
 * back without its allowed events, roles or invite codes enforces less than the admin set, and
 * nothing says so.
 */
class BanStoreJsonTest {
    private val alice = "aa".padEnd(64, '0')
    private val bob = "bb".padEnd(64, '0')
    private val carol = "cc".padEnd(64, '0')
    private val note = "11".padEnd(64, '0')
    private val other = "22".padEnd(64, '0')

    private fun populated(): BanStore =
        BanStore().apply {
            banPubkey(alice, "spam")
            banPubkey(carol)
            allowPubkey(bob, "trusted")
            banEvent(note, "illegal")
            allowEvent(other)
            allowKind(1)
            disallowKind(4)
            createRole(RelayRole("mod", label = "Moderator", description = "keeps order", color = 120, order = 1))
            createRole(RelayRole("guest"))
            assignRole(bob, "mod")
            assignRole(bob, "guest")
            createClaim("invite-123")
        }

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val before = populated()
        val after = BanStore().apply { seedFromJson(before.toJson()) }

        assertEquals(before.listBannedPubkeys().toSet(), after.listBannedPubkeys().toSet())
        assertEquals(before.listAllowedPubkeys().toSet(), after.listAllowedPubkeys().toSet())
        assertEquals(before.listBannedEvents().toSet(), after.listBannedEvents().toSet())
        assertEquals(before.listAllowedEvents().toSet(), after.listAllowedEvents().toSet())
        assertEquals(before.listAllowedKinds(), after.listAllowedKinds())
        assertEquals(before.listDisallowedKinds(), after.listDisallowedKinds())
        assertEquals(before.listRoles(), after.listRoles())
        assertEquals(listOf("mod", "guest"), after.rolesOf(bob), "assignment order is kept")
        assertEquals(before.listClaims().toSet(), after.listClaims().toSet())
        assertTrue(after.isValidClaim("invite-123"))
        assertTrue(after.isAllowedEvent(other))
    }

    @Test
    fun aMissingReasonStaysMissing() {
        val after = BanStore().apply { seedFromJson(populated().toJson()) }
        assertEquals(null, after.listBannedPubkeys().toMap()[carol])
        assertEquals("spam", after.listBannedPubkeys().toMap()[alice])
        assertEquals(null, after.listAllowedEvents().toMap()[other])
    }

    @Test
    fun seedingDoesNotFireTheMutationHook() {
        var mutations = 0
        val json = populated().toJson()
        BanStore { mutations++ }.seedFromJson(json)
        assertEquals(0, mutations)
    }

    @Test
    fun anEmptyStoreRoundTrips() {
        val after = BanStore().apply { seedFromJson(BanStore().toJson()) }
        assertTrue(after.listBannedPubkeys().isEmpty())
        assertTrue(after.listRoles().isEmpty())
        assertTrue(after.listClaims().isEmpty())
    }

    @Test
    fun aFileWithOnlyTheModerationListsStillLoads() {
        // The shape a relay persisted before roles, claims and allowed events were saved: every
        // missing key is an empty list, and an empty reason is the empty string it says.
        val legacy =
            """
            {
              "bannedPubkeys": [ { "value": "$alice", "reason": "spam" } ],
              "allowedPubkeys": [ { "value": "$bob", "reason": "" } ],
              "bannedEvents": [ { "value": "$note", "reason": "illegal" } ],
              "allowedKinds": [ 1, 7 ],
              "disallowedKinds": [ 4 ]
            }
            """.trimIndent()
        val store = BanStore().apply { seedFromJson(legacy) }
        assertTrue(store.isBanned(alice))
        assertEquals("", store.listAllowedPubkeys().toMap()[bob])
        assertTrue(store.isBannedEvent(note))
        assertEquals(listOf(1, 7), store.listAllowedKinds())
        assertFalse(store.isKindAllowed(4))
        assertTrue(store.listRoles().isEmpty())
    }

    @Test
    fun unknownKeysFromANewerVersionAreIgnored() {
        val store = BanStore().apply { seedFromJson("""{ "bannedPubkeys": [ { "value": "$alice" } ], "somethingNew": { "x": 1 } }""") }
        assertTrue(store.isBanned(alice))
    }

    @Test
    fun aMalformedDocumentIsRefusedAndLoadsNothing() {
        val store = populated()
        listOf(
            "not json",
            "[]",
            """{ "bannedPubkeys": "not a list" }""",
            """{ "bannedPubkeys": [ { "reason": "no value" } ] }""",
            """{ "bannedPubkeys": [ { "value": "" } ] }""",
            """{ "allowedKinds": [ "one" ] }""",
            """{ "roles": [ { "label": "no id" } ] }""",
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { store.seedFromJson(bad) }
            assertTrue(store.isBanned(alice), "a refused document left the store untouched: $bad")
        }
    }

    @Test
    fun aHandEditedFileListingAnIdOnBothListsKeepsTheBan() {
        val both = """{ "bannedPubkeys": [ { "value": "$alice" } ], "allowedPubkeys": [ { "value": "$alice" } ] }"""
        val store = BanStore().apply { seedFromJson(both) }
        assertTrue(store.isBanned(alice))
        assertFalse(store.isAllowedPubkey(alice))
    }
}
