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
package com.vitorpamplona.quartz.concord.appEvents

import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelEntity
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEditionBuilder
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.GrantEntity
import com.vitorpamplona.quartz.concord.cord04Roles.MetadataEntity
import com.vitorpamplona.quartz.concord.cord04Roles.RoleEntity
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

/**
 * The fixture the Concord app-event vectors share with their generator
 * (`quartz/tools/concord-vector-gen`): fixed keys, one Community owned by [owner] with a
 * `#general` Channel, a legacy Roster (Admin at position 1 holding MANAGE_ROLES, Moderator at 2
 * holding KICK, BAN and MANAGE_MESSAGES), and a clock at [T0]. [ourControl] and [ourCases] are
 * what Quartz's builders write for every case the generator also has Armada write; the generator
 * reads them back through Armada (`ours-read-by-armada.json`).
 */
object ConcordAppEventFixture {
    private val SK =
        linkedMapOf(
            "owner" to "11".repeat(32),
            "admin" to "22".repeat(32),
            "mod" to "33".repeat(32),
            "alice" to "44".repeat(32),
            "bob" to "55".repeat(32),
        )
    val PK: Map<String, String> = SK.mapValues { KeyPair(it.value.hexToByteArray()).pubKey.toHexKey() }
    val owner = PK.getValue("owner")
    val admin = PK.getValue("admin")
    val mod = PK.getValue("mod")
    val alice = PK.getValue("alice")
    val bob = PK.getValue("bob")

    val OWNER_SALT = "a5".repeat(32)
    val GENERAL = "9a".repeat(32)
    val ADMIN_ROLE = "a1".repeat(32)
    val MOD_ROLE = "a2".repeat(32)
    val HELPER_ROLE = "a3".repeat(32)

    /** 2026-01-01T00:00:00Z; the generator's reader clock is T0 + 600 s. */
    const val T0 = 1767225600L
    const val EPOCH = 0L

    val communityId: ByteArray = ConcordKeyDerivation.communityId(owner.hexToByteArray(), OWNER_SALT.hexToByteArray())

    private fun bits(vararg bit: Int) = bit.fold(ConcordPermissions.NONE) { acc, b -> acc.with(b) }

    /** Armada's stock Admin (`ADMIN_ALL`) and Moderator (`MODERATOR_ALL`) bits, so both Rosters read alike. */
    val ADMIN_BITS =
        bits(
            ConcordPermissions.MANAGE_ROLES,
            ConcordPermissions.MANAGE_CHANNELS,
            ConcordPermissions.MANAGE_METADATA,
            ConcordPermissions.KICK,
            ConcordPermissions.BAN,
            ConcordPermissions.MANAGE_MESSAGES,
            ConcordPermissions.CREATE_INVITE,
            ConcordPermissions.VIEW_AUDIT_LOG,
            ConcordPermissions.MENTION_EVERYONE,
            ConcordPermissions.PIN_MESSAGES,
        )
    val MOD_BITS = bits(ConcordPermissions.KICK, ConcordPermissions.BAN, ConcordPermissions.MANAGE_MESSAGES, ConcordPermissions.MENTION_EVERYONE)

    private fun json(role: RoleEntity) = ConcordJson.instance.encodeToString(RoleEntity.serializer(), role)

    private fun json(grant: GrantEntity) = ConcordJson.instance.encodeToString(GrantEntity.serializer(), grant)

    private fun json(channel: ChannelEntity) = ConcordJson.instance.encodeToString(ChannelEntity.serializer(), channel)

    private fun edition(
        author: String,
        kind: ControlEntityKind,
        entityId: ByteArray,
        content: String,
        secs: Long,
        resolver: AuthorityResolver? = null,
    ): Event = ControlEditionBuilder.rumor(author, kind, entityId, 1, null, content, T0 + secs, resolver?.citationFor(author))

    private fun grantEid(member: String) = ConcordKeyDerivation.grantCoordinate(communityId, member.hexToByteArray())

    /** The Control Plane every case folds: metadata, `#general`, the two Roles and their Grants, all by the owner. */
    fun ourControl(): List<Event> =
        listOf(
            edition(owner, ControlEntityKind.METADATA, communityId, ConcordJson.instance.encodeToString(MetadataEntity.serializer(), MetadataEntity(name = "Vectors")), 0),
            edition(owner, ControlEntityKind.CHANNEL, GENERAL.hexToByteArray(), json(ChannelEntity(name = "general")), 0),
            edition(owner, ControlEntityKind.ROLE, ADMIN_ROLE.hexToByteArray(), json(RoleEntity(roleId = ADMIN_ROLE, name = "Admin", position = 1, permissions = ADMIN_BITS.toWire())), 1),
            edition(owner, ControlEntityKind.ROLE, MOD_ROLE.hexToByteArray(), json(RoleEntity(roleId = MOD_ROLE, name = "Moderator", position = 2, permissions = MOD_BITS.toWire())), 1),
            edition(owner, ControlEntityKind.GRANT, grantEid(admin), json(GrantEntity(member = admin, roleIds = listOf(ADMIN_ROLE))), 2),
            edition(owner, ControlEntityKind.GRANT, grantEid(mod), json(GrantEntity(member = mod, roleIds = listOf(MOD_ROLE))), 2),
        )

    /** The Roster [ourControl] folds to. */
    fun resolver(control: List<Event>): AuthorityResolver = AuthorityResolver.resolve(control.mapNotNull { ControlEdition.fromRumor(it) }, communityId, owner)

    class Case(
        val name: String,
        val plane: String,
        val rumor: Event? = null,
        val rumors: List<Event>? = null,
    )

    fun ourCases(): List<Case> {
        val roster = resolver(ourControl())
        val ch = GENERAL
        val message = ChannelChat.message(alice, ch, EPOCH, "hello from quartz", T0 + 10, ms = 101)
        val mention = ChannelChat.message(alice, ch, EPOCH, "hey nostr:${NPub.create(bob)}", T0 + 11, arrayOf(arrayOf("p", bob)), ms = 202)
        val inlineReply = ChannelChat.inlineReply(bob, ch, EPOCH, "replying inline", message.id, alice, T0 + 12, ms = 303)
        val threadReply = ChannelChat.reply(bob, ch, EPOCH, "in the thread", message, T0 + 13, ms = 404)
        val nested = ChannelChat.reply(alice, ch, EPOCH, "deeper", threadReply, T0 + 14, ms = 505)
        val edit = ChannelChat.edit(alice, ch, EPOCH, message.id, "hello from quartz, edited", T0 + 15, ms = 606)
        val editByOther = ChannelChat.edit(bob, ch, EPOCH, message.id, "not your message", T0 + 16, ms = 707)
        val plus = ChannelChat.reaction(bob, ch, EPOCH, message.id, alice, 9, "+", T0 + 17, ms = 808)
        val emoji = ChannelChat.reaction(bob, ch, EPOCH, message.id, alice, 9, "🤙", T0 + 18, ms = 909)
        val custom =
            ChannelChat.reaction(bob, ch, EPOCH, message.id, alice, 9, ":soapbox:", T0 + 19, arrayOf(arrayOf("emoji", "soapbox", "https://example.invalid/soapbox.png")), ms = 11)
        val unreact = ChannelChat.delete(bob, ch, EPOCH, listOf(plus), T0 + 20, ms = 22)
        val deleteOwn = ChannelChat.delete(alice, ch, EPOCH, listOf(mention), T0 + 21, ms = 33)
        val hide = ChannelChat.moderationHide(mod, ch, EPOCH, listOf(inlineReply), roster.citationFor(mod), T0 + 22, ms = 44)
        val nonModerator = ChannelChat.moderationHide(bob, ch, EPOCH, listOf(nested), roster.citationFor(bob), T0 + 23, ms = 55)
        val typing = ChannelChat.typing(bob, ch, EPOCH, T0 + 600, ms = 0)

        val chat =
            listOf(
                "message" to message,
                "mention" to mention,
                "inline_reply" to inlineReply,
                "thread_reply" to threadReply,
                "thread_reply_nested" to nested,
                "edit" to edit,
                "edit_by_other" to editByOther,
                "reaction_plus" to plus,
                "reaction_emoji" to emoji,
                "reaction_custom" to custom,
                "unreact" to unreact,
                "delete_own" to deleteOwn,
                "moderator_hide" to hide,
                "delete_by_non_moderator" to nonModerator,
                "typing" to typing,
            ).map { Case(it.first, "chat", it.second) }

        val guestbook =
            listOf(
                "join" to Guestbook.join(alice, T0 + 30, subMs = 1),
                "join_with_invite" to Guestbook.join(bob, T0 + 31, subMs = 2, inviteCreator = admin),
                "join_with_labelled_invite" to Guestbook.join(mod, T0 + 31, subMs = 3, inviteCreator = admin, inviteLabel = "Reddit"),
                "leave" to Guestbook.leave(alice, T0 + 32, subMs = 4),
                "kick_by_moderator" to Guestbook.kick(mod, bob, T0 + 33, subMs = 5, citation = roster.citationFor(mod)),
                "kick_by_member" to Guestbook.kick(alice, mod, T0 + 34, subMs = 6),
            ).map { Case("guestbook_${it.first}", "guestbook", it.second) }

        fun helper(
            perms: ConcordPermissions,
            position: Long,
        ) = json(RoleEntity(roleId = HELPER_ROLE, name = "Helper", position = position, permissions = perms.toWire()))
        val helperEid = HELPER_ROLE.hexToByteArray()
        val bobGrant = json(GrantEntity(member = bob, roleIds = listOf(MOD_ROLE)))
        val control =
            listOf(
                "role_subset_of_authors_bits" to edition(admin, ControlEntityKind.ROLE, helperEid, helper(bits(ConcordPermissions.KICK), 3), 40, roster),
                "role_with_a_bit_the_author_lacks" to
                    edition(admin, ControlEntityKind.ROLE, helperEid, helper(bits(ConcordPermissions.MODERATE_MEMBERS), 3), 41, roster),
                "role_at_the_authors_own_rank" to edition(admin, ControlEntityKind.ROLE, helperEid, helper(bits(ConcordPermissions.KICK), 1), 42, roster),
                "role_by_member_without_manage_roles" to edition(mod, ControlEntityKind.ROLE, helperEid, helper(bits(ConcordPermissions.KICK), 3), 43, roster),
                "role_uncited" to edition(admin, ControlEntityKind.ROLE, helperEid, helper(bits(ConcordPermissions.KICK), 3), 47),
                "grant_by_admin" to edition(admin, ControlEntityKind.GRANT, grantEid(bob), bobGrant, 44, roster),
                "grant_by_admin_uncited" to edition(admin, ControlEntityKind.GRANT, grantEid(bob), bobGrant, 45),
                "grant_by_moderator" to edition(mod, ControlEntityKind.GRANT, grantEid(bob), bobGrant, 46, roster),
                "grant_strip_uncited" to edition(admin, ControlEntityKind.GRANT, grantEid(mod), json(GrantEntity(member = mod, roleIds = emptyList())), 48),
            ).map { Case("control_${it.first}", "control", it.second) }

        // Quartz's ChannelEntity has no position: a Channel we write sorts by name in every reader.
        val order =
            listOf("c1" to "zeta", "c2" to "alpha", "c3" to "beta").map { (id, name) ->
                edition(owner, ControlEntityKind.CHANNEL, id.repeat(32).hexToByteArray(), json(ChannelEntity(name = name)), 50)
            }

        return chat + guestbook + control + Case("channel_order", "control", rumors = order)
    }
}
