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
package com.vitorpamplona.quartz.buzz

import com.vitorpamplona.quartz.buzz.aeEngrams.EngramEvent
import com.vitorpamplona.quartz.buzz.agentProfiles.AgentProfileEvent
import com.vitorpamplona.quartz.buzz.amTurnMetrics.AgentTurnMetricEvent
import com.vitorpamplona.quartz.buzz.aoObserver.ObserverFrameEvent
import com.vitorpamplona.quartz.buzz.apPersonas.PersonaEvent
import com.vitorpamplona.quartz.buzz.arArtifacts.ArtifactEvent
import com.vitorpamplona.quartz.buzz.arArtifacts.ArtifactRemovalEvent
import com.vitorpamplona.quartz.buzz.audit.AuditEntryEvent
import com.vitorpamplona.quartz.buzz.cwChannelWindow.ThreadSummaryEvent
import com.vitorpamplona.quartz.buzz.cwChannelWindow.ThreadWindowBoundsEvent
import com.vitorpamplona.quartz.buzz.cwChannelWindow.WindowBoundsEvent
import com.vitorpamplona.quartz.buzz.dm.DmAddMemberEvent
import com.vitorpamplona.quartz.buzz.dm.DmCreatedEvent
import com.vitorpamplona.quartz.buzz.dm.DmHideEvent
import com.vitorpamplona.quartz.buzz.dm.DmOpenEvent
import com.vitorpamplona.quartz.buzz.dvDmVisibility.DmVisibilityEvent
import com.vitorpamplona.quartz.buzz.erReminders.EventReminderEvent
import com.vitorpamplona.quartz.buzz.forum.ForumCommentEvent
import com.vitorpamplona.quartz.buzz.forum.ForumPostEvent
import com.vitorpamplona.quartz.buzz.forum.ForumVoteEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleEndedEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleGuidelinesEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleLivenessEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleParticipantJoinedEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleParticipantLeftEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleReactionEvent
import com.vitorpamplona.quartz.buzz.huddles.HuddleStartedEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.ArchiveRequestEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.ArchivedIdentitiesListEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.ArchivedIdentityEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.UnarchiveRequestEvent
import com.vitorpamplona.quartz.buzz.iaIdentityArchival.UnarchivedIdentityEvent
import com.vitorpamplona.quartz.buzz.jobs.JobAcceptedEvent
import com.vitorpamplona.quartz.buzz.jobs.JobCancelEvent
import com.vitorpamplona.quartz.buzz.jobs.JobErrorEvent
import com.vitorpamplona.quartz.buzz.jobs.JobProgressEvent
import com.vitorpamplona.quartz.buzz.jobs.JobRequestEvent
import com.vitorpamplona.quartz.buzz.jobs.JobResultEvent
import com.vitorpamplona.quartz.buzz.managedAgents.ManagedAgentEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationBanEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationResolveReportEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationTimeoutEvent
import com.vitorpamplona.quartz.buzz.moderation.ModerationUntimeoutEvent
import com.vitorpamplona.quartz.buzz.moderation.ProductFeedbackEvent
import com.vitorpamplona.quartz.buzz.mpProjects.ProjectEvent
import com.vitorpamplona.quartz.buzz.notifications.MemberAddedNotificationEvent
import com.vitorpamplona.quartz.buzz.notifications.MemberRemovedNotificationEvent
import com.vitorpamplona.quartz.buzz.pairing.PairingEvent
import com.vitorpamplona.quartz.buzz.plPushLease.PushLeaseEvent
import com.vitorpamplona.quartz.buzz.presence.PresenceUpdateEvent
import com.vitorpamplona.quartz.buzz.presence.TypingIndicatorEvent
import com.vitorpamplona.quartz.buzz.relayAdmin.RelayAdminAddMemberEvent
import com.vitorpamplona.quartz.buzz.relayAdmin.RelayAdminChangeRoleEvent
import com.vitorpamplona.quartz.buzz.relayAdmin.RelayAdminRemoveMemberEvent
import com.vitorpamplona.quartz.buzz.stream.CanvasEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageBookmarkedEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageDiffEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageEditEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessagePinnedEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageScheduledEvent
import com.vitorpamplona.quartz.buzz.stream.StreamMessageV2Event
import com.vitorpamplona.quartz.buzz.stream.StreamReminderEvent
import com.vitorpamplona.quartz.buzz.stream.SystemMessageEvent
import com.vitorpamplona.quartz.buzz.stream.sidecars.ChannelSummaryEvent
import com.vitorpamplona.quartz.buzz.stream.sidecars.PresenceSnapshotEvent
import com.vitorpamplona.quartz.buzz.teamCatalog.TeamCatalogEvent
import com.vitorpamplona.quartz.buzz.teams.TeamEvent
import com.vitorpamplona.quartz.buzz.workflow.ApprovalDenyEvent
import com.vitorpamplona.quartz.buzz.workflow.ApprovalGrantEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowApprovalDeniedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowApprovalGrantedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowApprovalRequestedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowCancelledEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowCompletedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowDefEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowFailedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowStepCompletedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowStepFailedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowStepStartedEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowTriggerEvent
import com.vitorpamplona.quartz.buzz.workflow.WorkflowTriggeredEvent
import com.vitorpamplona.quartz.buzz.wpWorkspaceProfile.SetWorkspaceProfileEvent
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-buzz` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object BuzzEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(AgentTurnMetricEvent.KIND, ::AgentTurnMetricEvent)
        registry.register(EngramEvent.KIND, ::EngramEvent)
        registry.register(AgentProfileEvent.KIND, ::AgentProfileEvent)
        registry.register(ObserverFrameEvent.KIND, ::ObserverFrameEvent)
        registry.register(PersonaEvent.KIND, ::PersonaEvent)
        registry.register(ArtifactEvent.KIND, ::ArtifactEvent)
        registry.register(ArtifactRemovalEvent.KIND, ::ArtifactRemovalEvent)
        registry.register(AuditEntryEvent.KIND, ::AuditEntryEvent)
        registry.register(WindowBoundsEvent.KIND, ::WindowBoundsEvent)
        registry.register(ThreadWindowBoundsEvent.KIND, ::ThreadWindowBoundsEvent)
        registry.register(DmAddMemberEvent.KIND, ::DmAddMemberEvent)
        registry.register(DmCreatedEvent.KIND, ::DmCreatedEvent)
        registry.register(DmHideEvent.KIND, ::DmHideEvent)
        registry.register(DmOpenEvent.KIND, ::DmOpenEvent)
        registry.register(DmVisibilityEvent.KIND, ::DmVisibilityEvent)
        registry.register(EventReminderEvent.KIND, ::EventReminderEvent)
        registry.register(ForumCommentEvent.KIND, ::ForumCommentEvent)
        registry.register(ForumPostEvent.KIND, ::ForumPostEvent)
        registry.register(ForumVoteEvent.KIND, ::ForumVoteEvent)
        registry.register(HuddleEndedEvent.KIND, ::HuddleEndedEvent)
        registry.register(HuddleGuidelinesEvent.KIND, ::HuddleGuidelinesEvent)
        registry.register(HuddleLivenessEvent.KIND, ::HuddleLivenessEvent)
        registry.register(HuddleParticipantJoinedEvent.KIND, ::HuddleParticipantJoinedEvent)
        registry.register(HuddleParticipantLeftEvent.KIND, ::HuddleParticipantLeftEvent)
        registry.register(HuddleReactionEvent.KIND, ::HuddleReactionEvent)
        registry.register(HuddleStartedEvent.KIND, ::HuddleStartedEvent)
        registry.register(ArchiveRequestEvent.KIND, ::ArchiveRequestEvent)
        registry.register(ArchivedIdentitiesListEvent.KIND, ::ArchivedIdentitiesListEvent)
        registry.register(ArchivedIdentityEvent.KIND, ::ArchivedIdentityEvent)
        registry.register(UnarchiveRequestEvent.KIND, ::UnarchiveRequestEvent)
        registry.register(UnarchivedIdentityEvent.KIND, ::UnarchivedIdentityEvent)
        registry.register(JobAcceptedEvent.KIND, ::JobAcceptedEvent)
        registry.register(JobCancelEvent.KIND, ::JobCancelEvent)
        registry.register(JobErrorEvent.KIND, ::JobErrorEvent)
        registry.register(JobProgressEvent.KIND, ::JobProgressEvent)
        registry.register(JobRequestEvent.KIND, ::JobRequestEvent)
        registry.register(JobResultEvent.KIND, ::JobResultEvent)
        registry.register(ManagedAgentEvent.KIND, ::ManagedAgentEvent)
        registry.register(ModerationBanEvent.KIND, ::ModerationBanEvent)
        registry.register(ModerationResolveReportEvent.KIND, ::ModerationResolveReportEvent)
        registry.register(ModerationTimeoutEvent.KIND, ::ModerationTimeoutEvent)
        registry.register(ModerationUntimeoutEvent.KIND, ::ModerationUntimeoutEvent)
        registry.register(ProductFeedbackEvent.KIND, ::ProductFeedbackEvent)
        registry.register(ProjectEvent.KIND, ::ProjectEvent)
        registry.register(MemberAddedNotificationEvent.KIND, ::MemberAddedNotificationEvent)
        registry.register(MemberRemovedNotificationEvent.KIND, ::MemberRemovedNotificationEvent)
        registry.register(PairingEvent.KIND, ::PairingEvent)
        registry.register(PushLeaseEvent.KIND, ::PushLeaseEvent)
        registry.register(TypingIndicatorEvent.KIND, ::TypingIndicatorEvent)
        registry.register(RelayAdminAddMemberEvent.KIND, ::RelayAdminAddMemberEvent)
        registry.register(RelayAdminChangeRoleEvent.KIND, ::RelayAdminChangeRoleEvent)
        registry.register(RelayAdminRemoveMemberEvent.KIND, ::RelayAdminRemoveMemberEvent)
        registry.register(CanvasEvent.KIND, ::CanvasEvent)
        registry.register(StreamMessageBookmarkedEvent.KIND, ::StreamMessageBookmarkedEvent)
        registry.register(StreamMessageDiffEvent.KIND, ::StreamMessageDiffEvent)
        registry.register(StreamMessageEditEvent.KIND, ::StreamMessageEditEvent)
        registry.register(StreamMessagePinnedEvent.KIND, ::StreamMessagePinnedEvent)
        registry.register(StreamMessageScheduledEvent.KIND, ::StreamMessageScheduledEvent)
        registry.register(StreamMessageV2Event.KIND, ::StreamMessageV2Event)
        registry.register(StreamReminderEvent.KIND, ::StreamReminderEvent)
        registry.register(SystemMessageEvent.KIND, ::SystemMessageEvent)
        registry.register(ChannelSummaryEvent.KIND, ::ChannelSummaryEvent)
        registry.register(PresenceSnapshotEvent.KIND, ::PresenceSnapshotEvent)
        registry.register(TeamEvent.KIND, ::TeamEvent)
        registry.register(TeamCatalogEvent.KIND, ::TeamCatalogEvent)
        registry.register(ApprovalDenyEvent.KIND, ::ApprovalDenyEvent)
        registry.register(ApprovalGrantEvent.KIND, ::ApprovalGrantEvent)
        registry.register(WorkflowApprovalDeniedEvent.KIND, ::WorkflowApprovalDeniedEvent)
        registry.register(WorkflowApprovalGrantedEvent.KIND, ::WorkflowApprovalGrantedEvent)
        registry.register(WorkflowApprovalRequestedEvent.KIND, ::WorkflowApprovalRequestedEvent)
        registry.register(WorkflowCancelledEvent.KIND, ::WorkflowCancelledEvent)
        registry.register(WorkflowCompletedEvent.KIND, ::WorkflowCompletedEvent)
        registry.register(WorkflowDefEvent.KIND, ::WorkflowDefEvent)
        registry.register(WorkflowFailedEvent.KIND, ::WorkflowFailedEvent)
        registry.register(WorkflowStepCompletedEvent.KIND, ::WorkflowStepCompletedEvent)
        registry.register(WorkflowStepFailedEvent.KIND, ::WorkflowStepFailedEvent)
        registry.register(WorkflowStepStartedEvent.KIND, ::WorkflowStepStartedEvent)
        registry.register(WorkflowTriggerEvent.KIND, ::WorkflowTriggerEvent)
        registry.register(WorkflowTriggeredEvent.KIND, ::WorkflowTriggeredEvent)
        registry.register(SetWorkspaceProfileEvent.KIND, ::SetWorkspaceProfileEvent)

        // kind:39005 is shared with NIP-29's group pin list (GroupPinnedEvent, the kind's default).
        // Buzz's thread summary always carries the channel's `h` tag; a pin list never does.
        registry.variant(ThreadSummaryEvent.KIND, matches = { tags -> tags.any { it.size > 1 && it[0] == GroupIdTag.TAG_NAME } }, builder = ::ThreadSummaryEvent)
        // kind:20001 is shared with BitChat's GeohashPresenceEvent, which always carries a `g` tag
        // and claims those events as a variant. Everything else is Buzz presence.
        registry.register(PresenceUpdateEvent.KIND, ::PresenceUpdateEvent)
    }
}
