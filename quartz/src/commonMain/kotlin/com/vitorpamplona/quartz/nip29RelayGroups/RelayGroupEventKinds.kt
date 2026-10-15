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
package com.vitorpamplona.quartz.nip29RelayGroups

import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupAdminsEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMembersEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupParticipantsEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupPinnedEvent
import com.vitorpamplona.quartz.nip29RelayGroups.metadata.GroupRolesEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.CreateGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.DeleteGroupEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupCreateInviteEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupDeleteEventEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupEditMetadataEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupPutUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupRemoveUserEvent
import com.vitorpamplona.quartz.nip29RelayGroups.moderation.GroupUpdatePinListEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupJoinRequestEvent
import com.vitorpamplona.quartz.nip29RelayGroups.request.GroupLeaveRequestEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-relay-groups` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object RelayGroupEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(GroupPutUserEvent.KIND, ::GroupPutUserEvent)
        registry.register(GroupRemoveUserEvent.KIND, ::GroupRemoveUserEvent)
        registry.register(GroupEditMetadataEvent.KIND, ::GroupEditMetadataEvent)
        registry.register(GroupDeleteEventEvent.KIND, ::GroupDeleteEventEvent)
        registry.register(CreateGroupEvent.KIND, ::CreateGroupEvent)
        registry.register(DeleteGroupEvent.KIND, ::DeleteGroupEvent)
        registry.register(GroupCreateInviteEvent.KIND, ::GroupCreateInviteEvent)
        registry.register(GroupJoinRequestEvent.KIND, ::GroupJoinRequestEvent)
        registry.register(GroupLeaveRequestEvent.KIND, ::GroupLeaveRequestEvent)
        registry.register(GroupMetadataEvent.KIND, ::GroupMetadataEvent)
        registry.register(GroupAdminsEvent.KIND, ::GroupAdminsEvent)
        registry.register(GroupMembersEvent.KIND, ::GroupMembersEvent)
        registry.register(GroupRolesEvent.KIND, ::GroupRolesEvent)
        registry.register(GroupParticipantsEvent.KIND, ::GroupParticipantsEvent)
        registry.register(GroupUpdatePinListEvent.KIND, ::GroupUpdatePinListEvent)

        registry.register(GroupPinnedEvent.KIND, ::GroupPinnedEvent)
    }
}
