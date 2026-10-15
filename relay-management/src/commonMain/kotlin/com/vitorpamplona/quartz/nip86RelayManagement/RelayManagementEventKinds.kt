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
package com.vitorpamplona.quartz.nip86RelayManagement

import com.vitorpamplona.quartz.nip43RelayMembers.addMember.RelayAddMemberEvent
import com.vitorpamplona.quartz.nip43RelayMembers.inviteRequest.RelayInviteRequestEvent
import com.vitorpamplona.quartz.nip43RelayMembers.joinRequest.RelayJoinRequestEvent
import com.vitorpamplona.quartz.nip43RelayMembers.leaveRequest.RelayLeaveRequestEvent
import com.vitorpamplona.quartz.nip43RelayMembers.list.RelayMembershipListEvent
import com.vitorpamplona.quartz.nip43RelayMembers.removeMember.RelayRemoveMemberEvent
import com.vitorpamplona.quartz.nip43RelayMembers.roles.RelayRoleEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-relay-management` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object RelayManagementEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(RelayAddMemberEvent.KIND, ::RelayAddMemberEvent)
        registry.register(RelayRemoveMemberEvent.KIND, ::RelayRemoveMemberEvent)
        registry.register(RelayMembershipListEvent.KIND, ::RelayMembershipListEvent)
        registry.register(RelayRoleEvent.KIND, ::RelayRoleEvent)
        registry.register(RelayJoinRequestEvent.KIND, ::RelayJoinRequestEvent)
        registry.register(RelayInviteRequestEvent.KIND, ::RelayInviteRequestEvent)
        registry.register(RelayLeaveRequestEvent.KIND, ::RelayLeaveRequestEvent)
    }
}
