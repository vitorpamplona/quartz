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
package com.vitorpamplona.quartz.utils

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
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChatEditEvent
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordTimerNoticeEvent
import com.vitorpamplona.quartz.concord.cord04Roles.control.ControlEditionEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEvent
import com.vitorpamplona.quartz.concord.cord05Invites.bundle.ConcordInviteBundleEvent
import com.vitorpamplona.quartz.contextvm.cep06Announcements.CvmServerAnnouncementEvent
import com.vitorpamplona.quartz.contextvm.cep06Announcements.CvmToolsListEvent
import com.vitorpamplona.quartz.cyberspace.CyberspaceBagEvent
import com.vitorpamplona.quartz.cyberspace.deck0003Sno.SnoAvatarEvent
import com.vitorpamplona.quartz.cyberspace.deck0003Sno.SnoObjectEvent
import com.vitorpamplona.quartz.cyberspace.deck0003Sno.SnoShardEvent
import com.vitorpamplona.quartz.drss.eventList.DrssEventListEvent
import com.vitorpamplona.quartz.drss.eventList.UnrecognizedKind30102Event
import com.vitorpamplona.quartz.drss.feedList.DrssFeedListEvent
import com.vitorpamplona.quartz.drss.feedList.UnrecognizedKind30100Event
import com.vitorpamplona.quartz.drss.listFolder.DrssListFolderEvent
import com.vitorpamplona.quartz.drss.listFolder.UnrecognizedKind30101Event
import com.vitorpamplona.quartz.drss.listenProgress.DrssListenProgressAppDataEvent
import com.vitorpamplona.quartz.drss.listenProgress.DrssListenProgressEvent
import com.vitorpamplona.quartz.drss.provenance.DrssProvenanceEvent
import com.vitorpamplona.quartz.drss.provenance.DrssProvenanceFolderEvent
import com.vitorpamplona.quartz.drss.provenance.UnrecognizedKind30104Event
import com.vitorpamplona.quartz.drss.provenance.UnrecognizedKind31985Event
import com.vitorpamplona.quartz.drss.readWriteLists.DrssReadWriteListsEvent
import com.vitorpamplona.quartz.drss.show.DrssShowEvent
import com.vitorpamplona.quartz.experimental.agora.FundraiserEvent
import com.vitorpamplona.quartz.experimental.attestations.attestation.AttestationEvent
import com.vitorpamplona.quartz.experimental.attestations.proficiency.AttestorProficiencyEvent
import com.vitorpamplona.quartz.experimental.attestations.recommendation.AttestorRecommendationEvent
import com.vitorpamplona.quartz.experimental.attestations.request.AttestationRequestEvent
import com.vitorpamplona.quartz.experimental.audio.header.AudioHeaderEvent
import com.vitorpamplona.quartz.experimental.audio.track.AudioTrackEvent
import com.vitorpamplona.quartz.experimental.ballots.BallotEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdDetectionEvent
import com.vitorpamplona.quartz.experimental.birdstar.BirdexEvent
import com.vitorpamplona.quartz.experimental.bitchat.geohash.GeohashChatEvent
import com.vitorpamplona.quartz.experimental.bitchat.geohash.GeohashPresenceEvent
import com.vitorpamplona.quartz.experimental.citations.ExternalCitationEvent
import com.vitorpamplona.quartz.experimental.citations.HardcopyCitationEvent
import com.vitorpamplona.quartz.experimental.citations.PromptCitationEvent
import com.vitorpamplona.quartz.experimental.clink.debits.DebitEvent
import com.vitorpamplona.quartz.experimental.clink.manage.ManageEvent
import com.vitorpamplona.quartz.experimental.clink.offers.OfferEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.AddressableListHeaderEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.ListHeaderEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.AddressableListItemEvent
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.ListItemEvent
import com.vitorpamplona.quartz.experimental.decoupling.setup.EncryptionKeyListEvent
import com.vitorpamplona.quartz.experimental.decoupling.transfer.request.EncryptionKeyRequestEvent
import com.vitorpamplona.quartz.experimental.decoupling.transfer.response.EncryptionKeyTransferEvent
import com.vitorpamplona.quartz.experimental.edits.TextNoteModificationEvent
import com.vitorpamplona.quartz.experimental.ephemChat.chat.EphemeralChatEvent
import com.vitorpamplona.quartz.experimental.ephemChat.list.EphemeralChatListEvent
import com.vitorpamplona.quartz.experimental.fitness.workout.ExerciseTemplateEvent
import com.vitorpamplona.quartz.experimental.fitness.workout.WorkoutRecordEvent
import com.vitorpamplona.quartz.experimental.fitness.workout.WorkoutTemplateEvent
import com.vitorpamplona.quartz.experimental.interactiveStories.InteractiveStoryPrologueEvent
import com.vitorpamplona.quartz.experimental.interactiveStories.InteractiveStoryReadingStateEvent
import com.vitorpamplona.quartz.experimental.interactiveStories.InteractiveStorySceneEvent
import com.vitorpamplona.quartz.experimental.kanban.board.KanbanBoardEvent
import com.vitorpamplona.quartz.experimental.kanban.board.UnrecognizedKind30301Event
import com.vitorpamplona.quartz.experimental.kanban.card.KanbanCardEvent
import com.vitorpamplona.quartz.experimental.kanban.card.UnrecognizedKind30302Event
import com.vitorpamplona.quartz.experimental.library.BlossomPieceIndexEvent
import com.vitorpamplona.quartz.experimental.library.BookshelfDirectoryEvent
import com.vitorpamplona.quartz.experimental.library.LearningResourceEvent
import com.vitorpamplona.quartz.experimental.medical.FhirResourceEvent
import com.vitorpamplona.quartz.experimental.music.playlist.MusicPlaylistEvent
import com.vitorpamplona.quartz.experimental.music.track.MusicTrackEvent
import com.vitorpamplona.quartz.experimental.nests.admin.AdminCommandEvent
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.application.SoftwareApplicationEvent
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.asset.SoftwareAssetEvent
import com.vitorpamplona.quartz.experimental.nip95.data.FileStorageEvent
import com.vitorpamplona.quartz.experimental.nip95.header.FileStorageHeaderEvent
import com.vitorpamplona.quartz.experimental.nipsOnNostr.NipTextEvent
import com.vitorpamplona.quartz.experimental.nns.NNSEvent
import com.vitorpamplona.quartz.experimental.notifications.wake.WakeUpEvent
import com.vitorpamplona.quartz.experimental.postingStreak.PostingStreakEvent
import com.vitorpamplona.quartz.experimental.predictionMarkets.PredictionMarketEvent
import com.vitorpamplona.quartz.experimental.pressstr.ebook.EbookEvent
import com.vitorpamplona.quartz.experimental.pressstr.issue.MagazineIssueEvent
import com.vitorpamplona.quartz.experimental.pressstr.magazine.MagazineEvent
import com.vitorpamplona.quartz.experimental.profileGallery.ProfileGalleryEntryEvent
import com.vitorpamplona.quartz.experimental.profileTheme.active.ActiveProfileThemeEvent
import com.vitorpamplona.quartz.experimental.profileTheme.definition.ThemeDefinitionEvent
import com.vitorpamplona.quartz.experimental.ps1saves.Ps1SaveEvent
import com.vitorpamplona.quartz.experimental.publications.PublicationContentEvent
import com.vitorpamplona.quartz.experimental.publications.PublicationIndexEvent
import com.vitorpamplona.quartz.experimental.ratings.EntityRatingEvent
import com.vitorpamplona.quartz.experimental.ratings.RelayReviewEvent
import com.vitorpamplona.quartz.experimental.roadstr.confirmation.RoadEventConfirmationEvent
import com.vitorpamplona.quartz.experimental.roadstr.report.RoadEventReportEvent
import com.vitorpamplona.quartz.experimental.topEight.TopEightEvent
import com.vitorpamplona.quartz.experimental.trustedLists.addressables.AddressableTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.events.EventTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.externalIds.ExternalIdTrustedListEvent
import com.vitorpamplona.quartz.experimental.trustedLists.users.UserTrustedListEvent
import com.vitorpamplona.quartz.experimental.videoCollaboration.VideoCollaborationEvent
import com.vitorpamplona.quartz.experimental.walletScrutiny.assetBundle.AssetBundleEvent
import com.vitorpamplona.quartz.experimental.walletScrutiny.verification.BuildVerificationEvent
import com.vitorpamplona.quartz.experimental.zapPolls.ZapPollEvent
import com.vitorpamplona.quartz.experimental.zapstore.identityProof.IdentityProofEvent
import com.vitorpamplona.quartz.feedDefinition.FeedDefinitionEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.LegacyKeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.NotificationRequestEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenListEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenRemovalEvent
import com.vitorpamplona.quartz.marmot.mip05PushNotifications.TokenRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip03Timestamp.OtsEvent
import com.vitorpamplona.quartz.nip04Dm.messages.EncryptedDmEvent
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip15Marketplace.auction.AuctionEvent
import com.vitorpamplona.quartz.nip15Marketplace.bid.BidEvent
import com.vitorpamplona.quartz.nip15Marketplace.bidConfirmation.BidConfirmationEvent
import com.vitorpamplona.quartz.nip15Marketplace.marketplace.MarketplaceEvent
import com.vitorpamplona.quartz.nip15Marketplace.product.ProductEvent
import com.vitorpamplona.quartz.nip15Marketplace.stall.StallEvent
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip23LongContent.LongFormContentEvent
import com.vitorpamplona.quartz.nip23LongContent.draft.LongFormDraftEvent
import com.vitorpamplona.quartz.nip25Reactions.ExternalReactionEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelCreateEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelHideMessageEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelMetadataEvent
import com.vitorpamplona.quartz.nip28PublicChat.admin.ChannelMuteUserEvent
import com.vitorpamplona.quartz.nip28PublicChat.list.PublicChatListEvent
import com.vitorpamplona.quartz.nip28PublicChat.message.ChannelMessageEvent
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
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag
import com.vitorpamplona.quartz.nip30CustomEmoji.pack.EmojiPackEvent
import com.vitorpamplona.quartz.nip30CustomEmoji.selection.EmojiListEvent
import com.vitorpamplona.quartz.nip30CustomEmoji.stickers.StickerPackEvent
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip34Git.ci.coordinatorAdvertisement.CiCoordinatorAdvertisementEvent
import com.vitorpamplona.quartz.nip34Git.ci.jobResult.CiJobResultEvent
import com.vitorpamplona.quartz.nip34Git.ci.manualTrigger.CiManualTriggerEvent
import com.vitorpamplona.quartz.nip34Git.ci.repositoryStatus.CiRepositoryStatusEvent
import com.vitorpamplona.quartz.nip34Git.ci.requestReadiness.CiRequestReadinessListEvent
import com.vitorpamplona.quartz.nip34Git.ci.secretUpdate.CiSecretUpdateEvent
import com.vitorpamplona.quartz.nip34Git.ci.serviceRequest.CiServiceRequestEvent
import com.vitorpamplona.quartz.nip34Git.ci.serviceStop.CiServiceStopEvent
import com.vitorpamplona.quartz.nip34Git.ci.workflowProgress.CiWorkflowProgressEvent
import com.vitorpamplona.quartz.nip34Git.ci.workflowResult.CiWorkflowResultEvent
import com.vitorpamplona.quartz.nip34Git.coverNote.GitCoverNoteEvent
import com.vitorpamplona.quartz.nip34Git.grasp.UserGraspListEvent
import com.vitorpamplona.quartz.nip34Git.issue.GitIssueEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestUpdateEvent
import com.vitorpamplona.quartz.nip34Git.reply.GitReplyEvent
import com.vitorpamplona.quartz.nip34Git.repository.GitRepositoryEvent
import com.vitorpamplona.quartz.nip34Git.review.GitReviewEvent
import com.vitorpamplona.quartz.nip34Git.state.GitRepositoryStateEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusAppliedEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusClosedEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusDraftEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusOpenEvent
import com.vitorpamplona.quartz.nip35Torrents.TorrentCommentEvent
import com.vitorpamplona.quartz.nip35Torrents.TorrentEvent
import com.vitorpamplona.quartz.nip37Drafts.DraftWrapEvent
import com.vitorpamplona.quartz.nip37Drafts.privateOutbox.PrivateOutboxRelayListEvent
import com.vitorpamplona.quartz.nip38UserStatus.UserStatusEvent
import com.vitorpamplona.quartz.nip39ExtIdentities.ExternalIdentitiesEvent
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip43RelayMembers.addMember.RelayAddMemberEvent
import com.vitorpamplona.quartz.nip43RelayMembers.inviteRequest.RelayInviteRequestEvent
import com.vitorpamplona.quartz.nip43RelayMembers.joinRequest.RelayJoinRequestEvent
import com.vitorpamplona.quartz.nip43RelayMembers.leaveRequest.RelayLeaveRequestEvent
import com.vitorpamplona.quartz.nip43RelayMembers.list.RelayMembershipListEvent
import com.vitorpamplona.quartz.nip43RelayMembers.removeMember.RelayRemoveMemberEvent
import com.vitorpamplona.quartz.nip43RelayMembers.roles.RelayRoleEvent
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcInfoEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcNotificationEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcRequestEvent
import com.vitorpamplona.quartz.nip47WalletConnect.events.NwcResponseEvent
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.PinListEvent
import com.vitorpamplona.quartz.nip51Lists.appCurationSet.AppCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.articleCurationSet.ArticleCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.BookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.OldBookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkSet.BookmarkSetEvent
import com.vitorpamplona.quartz.nip51Lists.favoriteAlgoFeedsList.FavoriteAlgoFeedsListEvent
import com.vitorpamplona.quartz.nip51Lists.favoriteFollowSetsList.FavoriteFollowSetsListEvent
import com.vitorpamplona.quartz.nip51Lists.followSet.FollowSetEvent
import com.vitorpamplona.quartz.nip51Lists.geohashList.GeohashListEvent
import com.vitorpamplona.quartz.nip51Lists.gitAuthorList.GitAuthorListEvent
import com.vitorpamplona.quartz.nip51Lists.gitRepositoryList.GitRepositoryListEvent
import com.vitorpamplona.quartz.nip51Lists.goodWikiAuthorList.GoodWikiAuthorListEvent
import com.vitorpamplona.quartz.nip51Lists.goodWikiRelayList.GoodWikiRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.interestList.InterestListEvent
import com.vitorpamplona.quartz.nip51Lists.interestSet.InterestSetEvent
import com.vitorpamplona.quartz.nip51Lists.kindMuteSet.KindMuteSetEvent
import com.vitorpamplona.quartz.nip51Lists.mediaFollowList.MediaFollowListEvent
import com.vitorpamplona.quartz.nip51Lists.mediaStarterPack.MediaStarterPackEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.MuteListEvent
import com.vitorpamplona.quartz.nip51Lists.pictureCurationSet.PictureCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.BlockedRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.BroadcastRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.FavoriteRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.IndexerRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.ProxyRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.TrustedRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relaySets.RelaySetEvent
import com.vitorpamplona.quartz.nip51Lists.releaseArtifactSet.ReleaseArtifactSetEvent
import com.vitorpamplona.quartz.nip51Lists.simpleGroupList.SimpleGroupListEvent
import com.vitorpamplona.quartz.nip51Lists.starterPack.StarterPackEvent
import com.vitorpamplona.quartz.nip51Lists.videoCurationSet.VideoCurationSetEvent
import com.vitorpamplona.quartz.nip52Calendar.appt.day.CalendarDateSlotEvent
import com.vitorpamplona.quartz.nip52Calendar.appt.time.CalendarTimeSlotEvent
import com.vitorpamplona.quartz.nip52Calendar.calendar.CalendarCollectionEvent
import com.vitorpamplona.quartz.nip52Calendar.rsvp.CalendarRSVPEvent
import com.vitorpamplona.quartz.nip53LiveActivities.chat.LiveActivitiesChatMessageEvent
import com.vitorpamplona.quartz.nip53LiveActivities.clip.LiveActivitiesClipEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingRoomEvent
import com.vitorpamplona.quartz.nip53LiveActivities.meetingSpaces.MeetingSpaceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.nestsServers.NestsServersEvent
import com.vitorpamplona.quartz.nip53LiveActivities.presence.MeetingRoomPresenceEvent
import com.vitorpamplona.quartz.nip53LiveActivities.raid.LiveActivitiesRaidEvent
import com.vitorpamplona.quartz.nip53LiveActivities.streaming.LiveActivitiesEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiArticleEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiMergeAcceptanceEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiMergeRequestEvent
import com.vitorpamplona.quartz.nip54Wiki.WikiRedirectEvent
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import com.vitorpamplona.quartz.nip58Badges.accepted.AcceptedBadgeSetEvent
import com.vitorpamplona.quartz.nip58Badges.award.BadgeAwardEvent
import com.vitorpamplona.quartz.nip58Badges.definition.BadgeDefinitionEvent
import com.vitorpamplona.quartz.nip58Badges.profile.ProfileBadgesEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.EphemeralGiftWrapEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.SiteSnapshotEvent
import com.vitorpamplona.quartz.nip5dNapplets.named.NamedNappletEvent
import com.vitorpamplona.quartz.nip5dNapplets.root.RootNappletEvent
import com.vitorpamplona.quartz.nip5dNapplets.snapshot.NappletSnapshotEvent
import com.vitorpamplona.quartz.nip60Cashu.history.CashuSpendingHistoryEvent
import com.vitorpamplona.quartz.nip60Cashu.quote.CashuMintQuoteEvent
import com.vitorpamplona.quartz.nip60Cashu.token.CashuTokenEvent
import com.vitorpamplona.quartz.nip60Cashu.wallet.CashuWalletEvent
import com.vitorpamplona.quartz.nip61Nutzaps.info.NutzapInfoEvent
import com.vitorpamplona.quartz.nip61Nutzaps.nutzap.NutzapEvent
import com.vitorpamplona.quartz.nip62RequestToVanish.RequestToVanishEvent
import com.vitorpamplona.quartz.nip64Chess.challenge.accept.LiveChessGameAcceptEvent
import com.vitorpamplona.quartz.nip64Chess.challenge.offer.LiveChessGameChallengeEvent
import com.vitorpamplona.quartz.nip64Chess.draw.LiveChessDrawOfferEvent
import com.vitorpamplona.quartz.nip64Chess.end.LiveChessGameEndEvent
import com.vitorpamplona.quartz.nip64Chess.game.ChessGameEvent
import com.vitorpamplona.quartz.nip64Chess.jester.JesterEvent
import com.vitorpamplona.quartz.nip64Chess.move.LiveChessMoveEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip66RelayMonitor.discovery.RelayDiscoveryEvent
import com.vitorpamplona.quartz.nip66RelayMonitor.monitor.RelayMonitorEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.P2POrderEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroDevFee.MostroDevFeePaymentEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroDispute.MostroDisputeEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroDispute.UnrecognizedKind38386Event
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroInfo.MostroInfoEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroInfo.UnrecognizedKind38385Event
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroRating.MostroUserRatingEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.mostroRating.UnrecognizedKind38384Event
import com.vitorpamplona.quartz.nip69P2pOrderEvents.robosatsRating.RoboSatsCoordinatorRatingEvent
import com.vitorpamplona.quartz.nip69P2pOrderEvents.robosatsRating.UnrecognizedKind31986Event
import com.vitorpamplona.quartz.nip71Video.AddressableNormalVideoEvent
import com.vitorpamplona.quartz.nip71Video.AddressableShortVideoEvent
import com.vitorpamplona.quartz.nip71Video.VideoNormalEvent
import com.vitorpamplona.quartz.nip71Video.VideoShortEvent
import com.vitorpamplona.quartz.nip71Video.textTrack.TextTrackEvent
import com.vitorpamplona.quartz.nip71Video.views.VideoViewEvent
import com.vitorpamplona.quartz.nip72ModCommunities.approval.CommunityPostApprovalEvent
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import com.vitorpamplona.quartz.nip72ModCommunities.follow.CommunityListEvent
import com.vitorpamplona.quartz.nip72ModCommunities.rules.CommunityRulesEvent
import com.vitorpamplona.quartz.nip75ZapGoals.ZapGoalEvent
import com.vitorpamplona.quartz.nip78AppData.AppDataEvent
import com.vitorpamplona.quartz.nip78AppData.AppSpecificDataEvent
import com.vitorpamplona.quartz.nip7DThreads.ThreadEvent
import com.vitorpamplona.quartz.nip84Highlights.HighlightEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.addressables.AddressableAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.events.EventAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.externalIds.ExternalIdAssertionEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.TrustProviderListEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.users.UserAssertionEvent
import com.vitorpamplona.quartz.nip87Ecash.cashu.CashuMintEvent
import com.vitorpamplona.quartz.nip87Ecash.fedimint.FedimintEvent
import com.vitorpamplona.quartz.nip87Ecash.recommendation.MintRecommendationEvent
import com.vitorpamplona.quartz.nip87Ecash.recommendation.UnrecognizedKind38000Event
import com.vitorpamplona.quartz.nip88Polls.poll.PollEvent
import com.vitorpamplona.quartz.nip88Polls.response.PollResponseEvent
import com.vitorpamplona.quartz.nip89AppHandlers.definition.AppDefinitionEvent
import com.vitorpamplona.quartz.nip89AppHandlers.recommendation.AppRecommendationEvent
import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryRequest.DvmContentDiscoveryRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryResponse.DvmContentDiscoveryResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.contentSearch.DvmContentSearchRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.contentSearch.DvmContentSearchResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.dvmHeartbeat.DvmHeartbeatEvent
import com.vitorpamplona.quartz.nip90Dvms.eventCount.DvmEventCountRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventCount.DvmEventCountResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPowDelegation.DvmEventPowDelegationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPowDelegation.DvmEventPowDelegationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPublishSchedule.DvmEventPublishScheduleRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPublishSchedule.DvmEventPublishScheduleResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventTimestamping.DvmEventTimestampingRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventTimestamping.DvmEventTimestampingResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.imageGeneration.DvmImageGenerationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.imageGeneration.DvmImageGenerationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.imageToVideo.DvmImageToVideoRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.imageToVideo.DvmImageToVideoResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.malwareScanning.DvmMalwareScanRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.malwareScanning.DvmMalwareScanResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.opReturn.DvmOpReturnRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.opReturn.DvmOpReturnResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.peopleSearch.DvmPeopleSearchRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.peopleSearch.DvmPeopleSearchResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.status.DvmStatusEvent
import com.vitorpamplona.quartz.nip90Dvms.summarization.DvmSummarizationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.summarization.DvmSummarizationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textExtraction.DvmTextExtractionRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textExtraction.DvmTextExtractionResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textGeneration.DvmTextGenerationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textGeneration.DvmTextGenerationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textToSpeech.DvmTextToSpeechRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textToSpeech.DvmTextToSpeechResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.translation.DvmTranslationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.translation.DvmTranslationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.userDiscoveryRequest.DvmUserDiscoveryRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.userDiscoveryResponse.DvmUserDiscoveryResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.videoConversion.DvmVideoConversionRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.videoConversion.DvmVideoConversionResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.videoTranslation.DvmVideoTranslationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.videoTranslation.DvmVideoTranslationResponseEvent
import com.vitorpamplona.quartz.nip94FileMetadata.FileMetadataEvent
import com.vitorpamplona.quartz.nip96FileStorage.config.FileServersEvent
import com.vitorpamplona.quartz.nip98HttpAuth.HTTPAuthorizationEvent
import com.vitorpamplona.quartz.nip99Classifieds.ClassifiedsEvent
import com.vitorpamplona.quartz.nip99Classifieds.collection.ProductCollectionEvent
import com.vitorpamplona.quartz.nip99Classifieds.collection.UnrecognizedKind30405Event
import com.vitorpamplona.quartz.nip99Classifieds.review.ProductReviewEvent
import com.vitorpamplona.quartz.nip99Classifieds.shipping.ShippingOptionEvent
import com.vitorpamplona.quartz.nip99Classifieds.shipping.UnrecognizedKind30406Event
import com.vitorpamplona.quartz.nipA0VoiceMessages.VoiceEvent
import com.vitorpamplona.quartz.nipA0VoiceMessages.VoiceReplyEvent
import com.vitorpamplona.quartz.nipA3PaymentTargets.PaymentTargetsEvent
import com.vitorpamplona.quartz.nipA4PublicMessages.PublicMessageEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallAnswerEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallHangupEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallIceCandidateEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallOfferEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallRejectEvent
import com.vitorpamplona.quartz.nipACWebRtcCalls.events.CallRenegotiateEvent
import com.vitorpamplona.quartz.nipB0WebBookmarks.WebBookmarkEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.intent.Bolt12ZapIntentEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.offer.Bolt12OfferListEvent
import com.vitorpamplona.quartz.nipB1Bolt12Zaps.zap.Bolt12ZapEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import com.vitorpamplona.quartz.nipBCOnchainZaps.zap.OnchainZapEvent
import com.vitorpamplona.quartz.nipC0CodeSnippets.CodeSnippetEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.nipCCGeocaching.curation.GeocacheCurationListEvent
import com.vitorpamplona.quartz.nipCCGeocaching.foundLog.GeocacheFoundLogEvent
import com.vitorpamplona.quartz.nipCCGeocaching.listing.GeocacheListingEvent
import com.vitorpamplona.quartz.nipCCGeocaching.verification.GeocacheVerificationEvent
import com.vitorpamplona.quartz.nipF4Podcasts.authored.AuthoredPodcastsEvent
import com.vitorpamplona.quartz.nipF4Podcasts.episode.PodcastEpisodeEvent
import com.vitorpamplona.quartz.nipF4Podcasts.favorites.FavoritePodcastsListEvent
import com.vitorpamplona.quartz.nipF4Podcasts.metadata.PodcastMetadataEvent
import com.vitorpamplona.quartz.nipXXPodcasting20.episode.Podcasting20EpisodeEvent
import com.vitorpamplona.quartz.nipXXPodcasting20.episode.UnrecognizedKind30054Event
import com.vitorpamplona.quartz.nipXXPodcasting20.trailer.Podcasting20TrailerEvent
import com.vitorpamplona.quartz.nipXXPrivateNoteStorage.PnsEvent
import com.vitorpamplona.quartz.nipXXPushNotifications.deregistration.PushDeregistrationEvent
import com.vitorpamplona.quartz.nipXXPushNotifications.preferences.PushPreferencesEvent
import com.vitorpamplona.quartz.nipXXPushNotifications.registration.PushRegistrationEvent
import com.vitorpamplona.quartz.openagents.nipCapCapabilities.discovery.CapabilityDiscoveryEvent
import com.vitorpamplona.quartz.openagents.nipExtExtensions.listing.ExtensionListingEvent
import com.vitorpamplona.quartz.openagents.nipExtExtensions.release.ExtensionReleaseEvent

/**
 * EXPERIMENT: [EventFactory.create]'s kinds as a table, so the JIT compiles one small builder per kind
 * the traffic actually brings instead of four ~4 KB `when` blocks (one took 138 ms of C2 time and
 * became 51 KB of machine code). Generated from [EventFactory]'s `when` branches, in their order; the
 * first registration of a kind wins, as the first matching branch did. Selected with
 * `-Dquartz.experiment.tableFactory=true`.
 */
internal fun interface KindBuilder {
    fun build(
        id: HexKey,
        pubKey: HexKey,
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
        sig: HexKey,
    ): Event
}

internal object EventFactoryTable {
    private val kinds: IntArray
    private val builders: Array<KindBuilder>

    init {
        val all = ArrayList<Pair<Int, KindBuilder>>(512)

        fun add(
            kind: Int,
            builder: KindBuilder,
        ) {
            all.add(kind to builder)
        }

        add(AcceptedBadgeSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AcceptedBadgeSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ConcordChatEditEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ConcordChatEditEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ConcordTimerNoticeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ConcordTimerNoticeEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AdvertisedRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AdvertisedRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CvmServerAnnouncementEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CvmServerAnnouncementEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CvmToolsListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CvmToolsListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AgentTurnMetricEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AgentTurnMetricEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EngramEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EngramEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AgentProfileEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AgentProfileEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ObserverFrameEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ObserverFrameEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PersonaEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PersonaEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ArtifactEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ArtifactEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ArtifactRemovalEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ArtifactRemovalEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AuditEntryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AuditEntryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WindowBoundsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WindowBoundsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ThreadWindowBoundsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ThreadWindowBoundsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DmAddMemberEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DmAddMemberEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DmCreatedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DmCreatedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DmHideEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DmHideEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DmOpenEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DmOpenEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DmVisibilityEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DmVisibilityEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EventReminderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EventReminderEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ForumCommentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ForumCommentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ForumPostEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ForumPostEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ForumVoteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ForumVoteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleEndedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleEndedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleGuidelinesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleGuidelinesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleLivenessEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleLivenessEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleParticipantJoinedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleParticipantJoinedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleParticipantLeftEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleParticipantLeftEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleReactionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleReactionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HuddleStartedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HuddleStartedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ArchiveRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ArchiveRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ArchivedIdentitiesListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ArchivedIdentitiesListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ArchivedIdentityEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ArchivedIdentityEvent(id, pubKey, createdAt, tags, content, sig) }
        add(UnarchiveRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> UnarchiveRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(UnarchivedIdentityEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> UnarchivedIdentityEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JobAcceptedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JobAcceptedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JobCancelEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JobCancelEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JobErrorEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JobErrorEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JobProgressEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JobProgressEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JobRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JobRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JobResultEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JobResultEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ManagedAgentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ManagedAgentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ModerationBanEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ModerationBanEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ModerationResolveReportEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ModerationResolveReportEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ModerationTimeoutEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ModerationTimeoutEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ModerationUntimeoutEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ModerationUntimeoutEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ProductFeedbackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProductFeedbackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ProjectEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProjectEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MemberAddedNotificationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MemberAddedNotificationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MemberRemovedNotificationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MemberRemovedNotificationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PairingEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PairingEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PushLeaseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PushLeaseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TypingIndicatorEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TypingIndicatorEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayAdminAddMemberEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayAdminAddMemberEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayAdminChangeRoleEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayAdminChangeRoleEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayAdminRemoveMemberEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayAdminRemoveMemberEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CanvasEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CanvasEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StreamMessageBookmarkedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamMessageBookmarkedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StreamMessageDiffEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamMessageDiffEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StreamMessageEditEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamMessageEditEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StreamMessagePinnedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamMessagePinnedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StreamMessageScheduledEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamMessageScheduledEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StreamMessageV2Event.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamMessageV2Event(id, pubKey, createdAt, tags, content, sig) }
        add(StreamReminderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StreamReminderEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SystemMessageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SystemMessageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChannelSummaryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChannelSummaryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PresenceSnapshotEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PresenceSnapshotEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TeamEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TeamEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TeamCatalogEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TeamCatalogEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ApprovalDenyEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ApprovalDenyEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ApprovalGrantEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ApprovalGrantEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowApprovalDeniedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowApprovalDeniedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowApprovalGrantedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowApprovalGrantedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowApprovalRequestedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowApprovalRequestedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowCancelledEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowCancelledEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowCompletedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowCompletedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowDefEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowDefEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowFailedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowFailedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowStepCompletedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowStepCompletedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowStepFailedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowStepFailedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowStepStartedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowStepStartedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowTriggerEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowTriggerEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkflowTriggeredEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkflowTriggeredEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SetWorkspaceProfileEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SetWorkspaceProfileEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AppCurationSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AppCurationSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AppDefinitionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AppDefinitionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AppRecommendationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AppRecommendationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AppDataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AppDataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AppSpecificDataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            when {
                DrssListenProgressAppDataEvent.isListenProgress(tags) -> DrssListenProgressAppDataEvent(id, pubKey, createdAt, tags, content, sig)
                DrssReadWriteListsEvent.isReadWriteLists(tags) -> DrssReadWriteListsEvent(id, pubKey, createdAt, tags, content, sig)
                else -> AppSpecificDataEvent(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(AttestationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AttestationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AttestationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AttestationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AttestorRecommendationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AttestorRecommendationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AttestorProficiencyEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AttestorProficiencyEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ArticleCurationSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ArticleCurationSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AudioHeaderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AudioHeaderEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AuctionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AuctionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AudioTrackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AudioTrackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BadgeAwardEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BadgeAwardEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BadgeDefinitionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BadgeDefinitionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BirdDetectionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BirdDetectionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BirdexEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BirdexEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BidEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BidEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BidConfirmationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BidConfirmationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BlockedRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BlockedRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BlossomServersEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BlossomServersEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NestsServersEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NestsServersEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BlossomAuthorizationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BlossomAuthorizationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BroadcastRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BroadcastRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BookmarkListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BookmarkListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(OldBookmarkListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> OldBookmarkListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CalendarDateSlotEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CalendarDateSlotEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CalendarCollectionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CalendarCollectionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CalendarTimeSlotEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CalendarTimeSlotEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CalendarRSVPEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CalendarRSVPEvent(id, pubKey, createdAt, tags, content, sig) }
        // drss.io's reader (RSS and podcasts bridged to Nostr) publishes its lists and records on
        // kinds other apps use too. Each is told apart by its drss shape; the rest stays
        // addressable but unread. Its show definitions (31341) have the kind to themselves.
        add(DrssFeedListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (DrssFeedListEvent.isFeedList(tags, content)) {
                DrssFeedListEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30100Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(DrssListFolderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (DrssListFolderEvent.isListFolder(tags)) {
                DrssListFolderEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30101Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(DrssEventListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (DrssEventListEvent.isEventList(tags)) {
                DrssEventListEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30102Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(DrssProvenanceFolderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (DrssProvenanceFolderEvent.isProvenanceFolder(tags)) {
                DrssProvenanceFolderEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30104Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(DrssProvenanceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (DrssProvenanceEvent.isProvenance(tags)) {
                DrssProvenanceEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind31985Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(DrssShowEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DrssShowEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CapabilityDiscoveryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CapabilityDiscoveryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CallAnswerEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CallAnswerEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CallHangupEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CallHangupEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CallIceCandidateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CallIceCandidateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CallOfferEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CallOfferEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CallRejectEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CallRejectEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CallRenegotiateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CallRenegotiateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CashuMintEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CashuMintEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CashuMintQuoteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CashuMintQuoteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CashuTokenEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CashuTokenEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CashuSpendingHistoryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CashuSpendingHistoryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CashuWalletEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CashuWalletEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChatEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChatEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupPutUserEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupPutUserEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupRemoveUserEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupRemoveUserEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupEditMetadataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupEditMetadataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupDeleteEventEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupDeleteEventEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CreateGroupEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CreateGroupEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DeleteGroupEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DeleteGroupEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupCreateInviteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupCreateInviteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupJoinRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupJoinRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupLeaveRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupLeaveRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupMetadataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupMetadataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupAdminsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupAdminsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupMembersEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupMembersEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupRolesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupRolesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupParticipantsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupParticipantsEvent(id, pubKey, createdAt, tags, content, sig) }
        // kind:39005 is shared by two relay-signed, addressable events that never coexist on
        // one relay: NIP-29's group pin list (relay29 family) and Buzz's NIP-CW thread
        // summary. Disambiguate by Buzz's required `h` (channel) tag — a thread summary is
        // addressed by `d` = the thread ROOT EVENT ID and also carries `e` = that root and
        // `h` = the channel, while a pin list is addressed by `d` = the GROUP ID with the
        // pinned ids as `e` tags and no `h` at all.
        //
        // Without this, a Buzz thread summary parses as a GroupPinnedEvent — silently, since
        // nothing throws — and `pinnedEventIds()` would read the summary's `e` tag and report
        // the thread root as a pinned message. Buzz publishes these live to channel
        // subscribers (`side_effects.rs`, Redis fan-out to `EventTopic::Channel`), not only on
        // its HTTP bridge, so any future channel-scoped filter that asks for 39005 receives
        // them. Buzz's own pinned message is a different kind entirely (40004).
        add(GroupPinnedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (tags.any { it.size > 1 && it[0] == GroupIdTag.TAG_NAME }) {
                ThreadSummaryEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                GroupPinnedEvent(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(GroupUpdatePinListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupUpdatePinListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChessGameEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChessGameEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CodeSnippetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CodeSnippetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FavoriteRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FavoriteRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(JesterEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> JesterEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveChessGameChallengeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveChessGameChallengeEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveChessGameAcceptEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveChessGameAcceptEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveChessMoveEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveChessMoveEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveChessGameEndEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveChessGameEndEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveChessDrawOfferEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveChessDrawOfferEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChannelCreateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChannelCreateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChannelHideMessageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChannelHideMessageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PublicChatListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PublicChatListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChannelMessageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChannelMessageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChannelMetadataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChannelMetadataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChannelMuteUserEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChannelMuteUserEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ChatMessageEncryptedFileHeaderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChatMessageEncryptedFileHeaderEvent(id.ifBlank { EventHasher.hashId(pubKey, createdAt, kind, tags, content) }, pubKey, createdAt, tags, content, sig) }
        add(ChatMessageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ChatMessageEvent(id.ifBlank { EventHasher.hashId(pubKey, createdAt, kind, tags, content) }, pubKey, createdAt, tags, content, sig) }
        add(DmRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DmRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ClassifiedsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ClassifiedsEvent(id, pubKey, createdAt, tags, content, sig) }
        // Open Markets kinds 30405/30406: Shopstr reuses both numbers for encrypted carts and buyer
        // escrow records. Disambiguate by tags and fall back to an Unrecognized class: still
        // addressable, so stores replace and delete it by `d`, but never indexed.
        add(ProductCollectionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (ProductCollectionEvent.isProductCollection(tags)) {
                ProductCollectionEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30405Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(ShippingOptionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (ShippingOptionEvent.isShippingOption(tags)) {
                ShippingOptionEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30406Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(ProductReviewEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProductReviewEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CommentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CommentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CommunityDefinitionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CommunityDefinitionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CommunityListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CommunityListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CommunityPostApprovalEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CommunityPostApprovalEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CommunityRulesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CommunityRulesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ContactListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ContactListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DeletionRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DeletionRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EncryptionKeyRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EncryptionKeyRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EncryptionKeyTransferEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EncryptionKeyTransferEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DraftWrapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DraftWrapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EbookEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EbookEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MagazineEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MagazineEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MagazineIssueEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MagazineIssueEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EmojiPackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EmojiPackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StickerPackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StickerPackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EmojiListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EmojiListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EphemeralChatEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EphemeralChatEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EphemeralChatListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EphemeralChatListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExternalIdentitiesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExternalIdentitiesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExtensionListingEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExtensionListingEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExtensionReleaseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExtensionReleaseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FedimintEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FedimintEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FeedDefinitionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FeedDefinitionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FileMetadataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FileMetadataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ProfileGalleryEntryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProfileGalleryEntryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FileServersEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FileServersEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FileStorageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FileStorageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FileStorageHeaderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FileStorageHeaderEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FhirResourceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FhirResourceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StarterPackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StarterPackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FundraiserEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FundraiserEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GenericRepostEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GenericRepostEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CyberspaceBagEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CyberspaceBagEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SnoObjectEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SnoObjectEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SnoAvatarEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SnoAvatarEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SnoShardEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SnoShardEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GeocacheListingEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GeocacheListingEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GeocacheFoundLogEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GeocacheFoundLogEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GeocacheVerificationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GeocacheVerificationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GeocacheCurationListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GeocacheCurationListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GeohashChatEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GeohashChatEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GeohashListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GeohashListEvent(id, pubKey, createdAt, tags, content, sig) }
        // kind:20001 is shared by BitChat's GeohashPresenceEvent and Buzz's
        // PresenceUpdateEvent. Disambiguate by BitChat's required `g` (geohash) tag:
        // BitChat presence always carries one (empty content); Buzz presence never does
        // (the status lives in content, plus a `status` tag from clients or a `p` tag on
        // relay-synthesized reads). This routes both inbound parse and outbound signing,
        // since both go through this factory.
        add(GeohashPresenceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (tags.any { it.size > 1 && it[0] == "g" }) {
                GeohashPresenceEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                PresenceUpdateEvent(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(GiftWrapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GiftWrapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EphemeralGiftWrapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EphemeralGiftWrapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitAuthorListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitAuthorListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitRepositoryListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitRepositoryListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitIssueEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitIssueEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitReplyEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitReplyEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitPatchEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitPatchEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitPullRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitPullRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitPullRequestUpdateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitPullRequestUpdateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitRepositoryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitRepositoryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitRepositoryStateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitRepositoryStateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitStatusOpenEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitStatusOpenEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitStatusAppliedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitStatusAppliedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitStatusClosedEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitStatusClosedEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitStatusDraftEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitStatusDraftEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitCoverNoteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitCoverNoteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GitReviewEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GitReviewEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiManualTriggerEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiManualTriggerEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiJobResultEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiJobResultEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiWorkflowResultEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiWorkflowResultEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiServiceRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiServiceRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiServiceStopEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiServiceStopEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiWorkflowProgressEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiWorkflowProgressEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiCoordinatorAdvertisementEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiCoordinatorAdvertisementEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiRequestReadinessListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiRequestReadinessListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiRepositoryStatusEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiRepositoryStatusEvent(id, pubKey, createdAt, tags, content, sig) }
        add(CiSecretUpdateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> CiSecretUpdateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(UserGraspListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> UserGraspListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GoodWikiAuthorListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GoodWikiAuthorListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GoodWikiRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GoodWikiRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ZapGoalEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ZapGoalEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FavoriteAlgoFeedsListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FavoriteAlgoFeedsListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FavoriteFollowSetsListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FavoriteFollowSetsListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(InterestListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> InterestListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HighlightEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HighlightEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HTTPAuthorizationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HTTPAuthorizationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(IndexerRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> IndexerRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(InterestSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> InterestSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(InteractiveStoryPrologueEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> InteractiveStoryPrologueEvent(id, pubKey, createdAt, tags, content, sig) }
        add(InteractiveStorySceneEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> InteractiveStorySceneEvent(id, pubKey, createdAt, tags, content, sig) }
        add(InteractiveStoryReadingStateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> InteractiveStoryReadingStateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LabelEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LabelEvent(id, pubKey, createdAt, tags, content, sig) }
        add(KindMuteSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> KindMuteSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BookmarkSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BookmarkSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveActivitiesChatMessageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveActivitiesChatMessageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveActivitiesClipEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveActivitiesClipEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveActivitiesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveActivitiesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LiveActivitiesRaidEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LiveActivitiesRaidEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ZapReceiptEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ZapReceiptEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NwcRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NwcRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NwcResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NwcResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(OfferEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> OfferEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DebitEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DebitEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ManageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ManageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NwcInfoEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NwcInfoEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NwcNotificationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NwcNotificationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NwcNotificationEvent.LEGACY_KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NwcNotificationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PrivateZapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PrivateZapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ZapRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ZapRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LongFormContentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LongFormContentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LongFormDraftEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LongFormDraftEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MarketplaceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MarketplaceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MeetingRoomEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MeetingRoomEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MeetingRoomPresenceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MeetingRoomPresenceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MeetingSpaceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MeetingSpaceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AdminCommandEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AdminCommandEvent(id, pubKey, createdAt, tags, content, sig) }
        // kind:38000 is NIP-87's mint recommendation, but unrelated apps reuse the number:
        // BAO Markets publishes prediction markets on it, an auditable-voting app publishes
        // ballots, and most of what a big relay holds for it is spam (`d`-only "sybil test
        // votes"). Parsing all of it as a recommendation rendered markets, ballots and spam
        // as ecash mint cards. Disambiguate by tags, recommendation first — its `k` (the
        // recommended mint's kind) or, on old events without one, its `u` (mint URL) or the
        // mint's `a` — and fall back to UnrecognizedKind38000Event: still addressable, so
        // stores replace and delete it by `d`, but never indexed and drawn as no card.
        add(MintRecommendationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            when {
                MintRecommendationEvent.isMintRecommendation(tags) -> MintRecommendationEvent(id, pubKey, createdAt, tags, content, sig)
                BallotEvent.isBallot(tags) -> BallotEvent(id, pubKey, createdAt, tags, content, sig)
                PredictionMarketEvent.isPredictionMarket(tags) -> PredictionMarketEvent(id, pubKey, createdAt, tags, content, sig)
                else -> UnrecognizedKind38000Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(MediaFollowListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MediaFollowListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MediaStarterPackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MediaStarterPackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MetadataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MetadataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MusicPlaylistEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MusicPlaylistEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MusicTrackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MusicTrackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(MuteListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MuteListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(KeyPackageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> KeyPackageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(KeyPackageRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> KeyPackageRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LegacyKeyPackageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LegacyKeyPackageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WelcomeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WelcomeEvent(id, pubKey, createdAt, tags, content, sig) }
        add(GroupEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> GroupEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NotificationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NotificationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TokenRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TokenRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TokenListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TokenListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TokenRemovalEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TokenRemovalEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NamedSiteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NamedSiteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NappletSnapshotEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NappletSnapshotEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RootNappletEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RootNappletEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NamedNappletEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NamedNappletEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NNSEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NNSEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NipTextEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NipTextEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NutzapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NutzapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NutzapInfoEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NutzapInfoEvent(id, pubKey, createdAt, tags, content, sig) }
        add(NostrConnectEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> NostrConnectEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmStatusEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmStatusEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmHeartbeatEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmHeartbeatEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTextExtractionRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTextExtractionRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTextExtractionResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTextExtractionResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmSummarizationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmSummarizationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmSummarizationResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmSummarizationResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTranslationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTranslationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTranslationResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTranslationResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTextGenerationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTextGenerationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTextGenerationResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTextGenerationResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmImageGenerationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmImageGenerationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmImageGenerationResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmImageGenerationResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmVideoConversionRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmVideoConversionRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmVideoConversionResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmVideoConversionResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmVideoTranslationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmVideoTranslationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmVideoTranslationResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmVideoTranslationResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmImageToVideoRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmImageToVideoRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmImageToVideoResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmImageToVideoResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTextToSpeechRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTextToSpeechRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmTextToSpeechResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmTextToSpeechResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmContentDiscoveryRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmContentDiscoveryRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmContentDiscoveryResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmContentDiscoveryResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmUserDiscoveryRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmUserDiscoveryRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmUserDiscoveryResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmUserDiscoveryResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmContentSearchRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmContentSearchRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmContentSearchResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmContentSearchResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmPeopleSearchRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmPeopleSearchRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmPeopleSearchResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmPeopleSearchResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventCountRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventCountRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventCountResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventCountResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmMalwareScanRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmMalwareScanRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmMalwareScanResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmMalwareScanResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventTimestampingRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventTimestampingRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventTimestampingResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventTimestampingResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmOpReturnRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmOpReturnRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmOpReturnResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmOpReturnResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventPublishScheduleRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventPublishScheduleRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventPublishScheduleResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventPublishScheduleResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventPowDelegationRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventPowDelegationRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(DvmEventPowDelegationResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> DvmEventPowDelegationResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(OnchainZapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> OnchainZapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(Bolt12ZapEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> Bolt12ZapEvent(id, pubKey, createdAt, tags, content, sig) }
        add(Bolt12ZapIntentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> Bolt12ZapIntentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(Bolt12OfferListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> Bolt12OfferListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(OtsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> OtsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PaymentTargetsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PaymentTargetsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FollowSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FollowSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PictureCurationSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PictureCurationSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(P2POrderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> P2POrderEvent(id, pubKey, createdAt, tags, content, sig) }
        // Mostro split its kind 38383 into 38384 ratings / 38385 info / 38386 disputes,
        // but Paygress (a compute marketplace) publishes heartbeats, lease revocations and
        // promotion announcements on the same three numbers, and other apps add their own.
        // Each is told apart by its Mostro `z` tag; the rest stays addressable but unread.
        add(MostroUserRatingEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (MostroUserRatingEvent.isMostroRating(tags)) {
                MostroUserRatingEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind38384Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(MostroInfoEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (MostroInfoEvent.isMostroInfo(tags)) {
                MostroInfoEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind38385Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(MostroDisputeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (MostroDisputeEvent.isMostroDispute(tags)) {
                MostroDisputeEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind38386Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(MostroDevFeePaymentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> MostroDevFeePaymentEvent(id, pubKey, createdAt, tags, content, sig) }
        // RoboSats coordinator ratings carry a coordinator-signed token; Borkstr's NIP
        // compatibility reports share the number without one.
        add(RoboSatsCoordinatorRatingEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (RoboSatsCoordinatorRatingEvent.isCoordinatorRating(tags)) {
                RoboSatsCoordinatorRatingEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind31986Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        // kind:30301 is a Kanban board in NIP PR #1665, but WalletScrutiny publishes its
        // reproducible-build verifications on it and an encrypted planner app its tasks.
        // Verifications carry `i` + `status`, boards a `title` or a named `col`; the planner's
        // events fall to UnrecognizedKind30301Event (addressable, never indexed, no edges).
        add(KanbanBoardEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            when {
                BuildVerificationEvent.isBuildVerification(tags) -> BuildVerificationEvent(id, pubKey, createdAt, tags, content, sig)
                KanbanBoardEvent.isKanbanBoard(tags) -> KanbanBoardEvent(id, pubKey, createdAt, tags, content, sig)
                else -> UnrecognizedKind30301Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        // Kanban cards (NIP PR #1665) share 30302 with Fieldbook's team memberships.
        add(KanbanCardEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            if (KanbanCardEvent.isKanbanCard(tags)) {
                KanbanCardEvent(id, pubKey, createdAt, tags, content, sig)
            } else {
                UnrecognizedKind30302Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(PictureEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PictureEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PinListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PinListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PnsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PnsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ProfileBadgesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProfileBadgesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ZapPollEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ZapPollEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PollEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PollEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PollResponseEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PollResponseEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PodcastMetadataEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PodcastMetadataEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PodcastEpisodeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PodcastEpisodeEvent(id, pubKey, createdAt, tags, content, sig) }
        add(Ps1SaveEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> Ps1SaveEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PostingStreakEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PostingStreakEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ActiveProfileThemeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ActiveProfileThemeEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ThemeDefinitionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ThemeDefinitionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AuthoredPodcastsEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AuthoredPodcastsEvent(id, pubKey, createdAt, tags, content, sig) }
        add(FavoritePodcastsListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> FavoritePodcastsListEvent(id, pubKey, createdAt, tags, content, sig) }
        // Podcasting-2.0 episodes (podstr's draft) share 30054 with the listening-progress records
        // drss.io wrote until it moved them to 30078 (`position`, `duration`, an `a` to the 30023
        // article the episode is bridged as).
        add(Podcasting20EpisodeEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig ->
            when {
                Podcasting20EpisodeEvent.isPodcastEpisode(tags) -> Podcasting20EpisodeEvent(id, pubKey, createdAt, tags, content, sig)
                DrssListenProgressEvent.isListenProgress(tags) -> DrssListenProgressEvent(id, pubKey, createdAt, tags, content, sig)
                else -> UnrecognizedKind30054Event(id, pubKey, createdAt, tags, content, sig)
            }
        }
        add(Podcasting20TrailerEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> Podcasting20TrailerEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ProductEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProductEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EncryptedDmEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EncryptedDmEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EncryptionKeyListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EncryptionKeyListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PrivateOutboxRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PrivateOutboxRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ProxyRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ProxyRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PublicMessageEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PublicMessageEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ReactionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ReactionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExternalReactionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExternalReactionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExternalCitationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExternalCitationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(HardcopyCitationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> HardcopyCitationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PromptCitationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PromptCitationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(LearningResourceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> LearningResourceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BookshelfDirectoryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BookshelfDirectoryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(BlossomPieceIndexEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> BlossomPieceIndexEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WikiMergeRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WikiMergeRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WikiMergeAcceptanceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WikiMergeAcceptanceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WikiRedirectEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WikiRedirectEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EntityRatingEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EntityRatingEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PublicationIndexEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PublicationIndexEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PublicationContentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PublicationContentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayReviewEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayReviewEvent(id, pubKey, createdAt, tags, content, sig) }
        add(UserAssertionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> UserAssertionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EventAssertionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EventAssertionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AddressableAssertionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AddressableAssertionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExternalIdAssertionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExternalIdAssertionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(UserTrustedListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> UserTrustedListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(EventTrustedListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> EventTrustedListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AddressableTrustedListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AddressableTrustedListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExternalIdTrustedListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExternalIdTrustedListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ListHeaderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ListHeaderEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AddressableListHeaderEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AddressableListHeaderEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ListItemEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ListItemEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AddressableListItemEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AddressableListItemEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayAddMemberEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayAddMemberEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayRemoveMemberEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayRemoveMemberEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayMembershipListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayMembershipListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayRoleEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayRoleEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayJoinRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayJoinRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayInviteRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayInviteRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayLeaveRequestEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayLeaveRequestEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayAuthEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayAuthEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayDiscoveryEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayDiscoveryEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelayMonitorEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelayMonitorEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ReleaseArtifactSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ReleaseArtifactSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RelaySetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RelaySetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ReportEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ReportEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RoadEventConfirmationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RoadEventConfirmationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RoadEventReportEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RoadEventReportEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RootSiteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RootSiteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SiteSnapshotEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SiteSnapshotEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RepostEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RepostEvent(id, pubKey, createdAt, tags, content, sig) }
        add(RequestToVanishEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> RequestToVanishEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ConcordCommunityListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ConcordCommunityListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ConcordCommunityListFragmentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ConcordCommunityListFragmentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ControlEditionEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ControlEditionEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ConcordInviteListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ConcordInviteListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ConcordInviteBundleEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ConcordInviteBundleEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SealEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SealEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SearchRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SearchRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SimpleGroupListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SimpleGroupListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SoftwareApplicationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SoftwareApplicationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(SoftwareAssetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> SoftwareAssetEvent(id, pubKey, createdAt, tags, content, sig) }
        // Zapstore's APK-signing-certificate proof and WalletScrutiny's asset bundles sit
        // beside NIP-82: both are about app releases, neither points at a NIP-82 event.
        add(IdentityProofEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> IdentityProofEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AssetBundleEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AssetBundleEvent(id, pubKey, createdAt, tags, content, sig) }
        add(StallEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> StallEvent(id, pubKey, createdAt, tags, content, sig) }
        add(UserStatusEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> UserStatusEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TextNoteEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TextNoteEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ThreadEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ThreadEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TextNoteModificationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TextNoteModificationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TopEightEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TopEightEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TorrentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TorrentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TorrentCommentEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TorrentCommentEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TrustedRelayListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TrustedRelayListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TrustProviderListEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TrustProviderListEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VideoCurationSetEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VideoCurationSetEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AddressableNormalVideoEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AddressableNormalVideoEvent(id, pubKey, createdAt, tags, content, sig) }
        add(AddressableShortVideoEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> AddressableShortVideoEvent(id, pubKey, createdAt, tags, content, sig) }
        add(TextTrackEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> TextTrackEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VideoViewEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VideoViewEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PushRegistrationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PushRegistrationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PushDeregistrationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PushDeregistrationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(PushPreferencesEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> PushPreferencesEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VideoCollaborationEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VideoCollaborationEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VideoNormalEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VideoNormalEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VideoShortEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VideoShortEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VoiceEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VoiceEvent(id, pubKey, createdAt, tags, content, sig) }
        add(VoiceReplyEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> VoiceReplyEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WakeUpEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WakeUpEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WebBookmarkEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WebBookmarkEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WikiArticleEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WikiArticleEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkoutRecordEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkoutRecordEvent(id, pubKey, createdAt, tags, content, sig) }
        add(ExerciseTemplateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> ExerciseTemplateEvent(id, pubKey, createdAt, tags, content, sig) }
        add(WorkoutTemplateEvent.KIND) { id, pubKey, createdAt, kind, tags, content, sig -> WorkoutTemplateEvent(id, pubKey, createdAt, tags, content, sig) }

        val seen = HashSet<Int>()
        val kept = all.filter { seen.add(it.first) }.sortedBy { it.first }
        kinds = IntArray(kept.size) { kept[it].first }
        builders = Array(kept.size) { kept[it].second }
    }

    /** The typed event for [kind], or null for a kind with no compiled-in class. */
    fun create(
        id: HexKey,
        pubKey: HexKey,
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
        sig: HexKey,
    ): Event? {
        val i = kinds.binarySearch(kind)
        return if (i >= 0) builders[i].build(id, pubKey, createdAt, kind, tags, content, sig) else null
    }

    /** Kinds registered, for tests. */
    val size: Int get() = kinds.size

    fun kindAt(i: Int): Int = kinds[i]
}
