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
package com.vitorpamplona.quartz.experimental

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
import com.vitorpamplona.quartz.nip51Lists.appCurationSet.AppCurationSetEvent
import com.vitorpamplona.quartz.nip51Lists.releaseArtifactSet.ReleaseArtifactSetEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-experimental` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object ExperimentalEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(AppCurationSetEvent.KIND, ::AppCurationSetEvent)
        registry.register(AttestationEvent.KIND, ::AttestationEvent)
        registry.register(AttestationRequestEvent.KIND, ::AttestationRequestEvent)
        registry.register(AttestorRecommendationEvent.KIND, ::AttestorRecommendationEvent)
        registry.register(AttestorProficiencyEvent.KIND, ::AttestorProficiencyEvent)
        registry.register(AudioHeaderEvent.KIND, ::AudioHeaderEvent)
        registry.register(AudioTrackEvent.KIND, ::AudioTrackEvent)
        registry.register(BirdDetectionEvent.KIND, ::BirdDetectionEvent)
        registry.register(BirdexEvent.KIND, ::BirdexEvent)
        registry.register(EncryptionKeyRequestEvent.KIND, ::EncryptionKeyRequestEvent)
        registry.register(EncryptionKeyTransferEvent.KIND, ::EncryptionKeyTransferEvent)
        registry.register(EphemeralChatEvent.KIND, ::EphemeralChatEvent)
        registry.register(EphemeralChatListEvent.KIND, ::EphemeralChatListEvent)
        registry.register(ProfileGalleryEntryEvent.KIND, ::ProfileGalleryEntryEvent)
        registry.register(FileStorageEvent.KIND, ::FileStorageEvent)
        registry.register(FileStorageHeaderEvent.KIND, ::FileStorageHeaderEvent)
        registry.register(FhirResourceEvent.KIND, ::FhirResourceEvent)
        registry.register(FundraiserEvent.KIND, ::FundraiserEvent)
        registry.register(GeohashChatEvent.KIND, ::GeohashChatEvent)
        registry.register(InteractiveStoryPrologueEvent.KIND, ::InteractiveStoryPrologueEvent)
        registry.register(InteractiveStorySceneEvent.KIND, ::InteractiveStorySceneEvent)
        registry.register(InteractiveStoryReadingStateEvent.KIND, ::InteractiveStoryReadingStateEvent)
        registry.register(OfferEvent.KIND, ::OfferEvent)
        registry.register(DebitEvent.KIND, ::DebitEvent)
        registry.register(ManageEvent.KIND, ::ManageEvent)
        registry.register(AdminCommandEvent.KIND, ::AdminCommandEvent)
        registry.register(MusicPlaylistEvent.KIND, ::MusicPlaylistEvent)
        registry.register(MusicTrackEvent.KIND, ::MusicTrackEvent)
        registry.register(NNSEvent.KIND, ::NNSEvent)
        registry.register(NipTextEvent.KIND, ::NipTextEvent)
        registry.register(ZapPollEvent.KIND, ::ZapPollEvent)
        registry.register(Ps1SaveEvent.KIND, ::Ps1SaveEvent)
        registry.register(PostingStreakEvent.KIND, ::PostingStreakEvent)
        registry.register(ActiveProfileThemeEvent.KIND, ::ActiveProfileThemeEvent)
        registry.register(ThemeDefinitionEvent.KIND, ::ThemeDefinitionEvent)
        registry.register(EncryptionKeyListEvent.KIND, ::EncryptionKeyListEvent)
        registry.register(ExternalCitationEvent.KIND, ::ExternalCitationEvent)
        registry.register(HardcopyCitationEvent.KIND, ::HardcopyCitationEvent)
        registry.register(PromptCitationEvent.KIND, ::PromptCitationEvent)
        registry.register(LearningResourceEvent.KIND, ::LearningResourceEvent)
        registry.register(BookshelfDirectoryEvent.KIND, ::BookshelfDirectoryEvent)
        registry.register(BlossomPieceIndexEvent.KIND, ::BlossomPieceIndexEvent)
        registry.register(EntityRatingEvent.KIND, ::EntityRatingEvent)
        registry.register(PublicationIndexEvent.KIND, ::PublicationIndexEvent)
        registry.register(PublicationContentEvent.KIND, ::PublicationContentEvent)
        registry.register(RelayReviewEvent.KIND, ::RelayReviewEvent)
        registry.register(UserTrustedListEvent.KIND, ::UserTrustedListEvent)
        registry.register(EventTrustedListEvent.KIND, ::EventTrustedListEvent)
        registry.register(AddressableTrustedListEvent.KIND, ::AddressableTrustedListEvent)
        registry.register(ExternalIdTrustedListEvent.KIND, ::ExternalIdTrustedListEvent)
        registry.register(ListHeaderEvent.KIND, ::ListHeaderEvent)
        registry.register(AddressableListHeaderEvent.KIND, ::AddressableListHeaderEvent)
        registry.register(ListItemEvent.KIND, ::ListItemEvent)
        registry.register(AddressableListItemEvent.KIND, ::AddressableListItemEvent)
        registry.register(ReleaseArtifactSetEvent.KIND, ::ReleaseArtifactSetEvent)
        registry.register(RoadEventConfirmationEvent.KIND, ::RoadEventConfirmationEvent)
        registry.register(RoadEventReportEvent.KIND, ::RoadEventReportEvent)
        registry.register(SoftwareApplicationEvent.KIND, ::SoftwareApplicationEvent)
        registry.register(SoftwareAssetEvent.KIND, ::SoftwareAssetEvent)
        registry.register(IdentityProofEvent.KIND, ::IdentityProofEvent)
        registry.register(AssetBundleEvent.KIND, ::AssetBundleEvent)
        registry.register(TextNoteModificationEvent.KIND, ::TextNoteModificationEvent)
        registry.register(TopEightEvent.KIND, ::TopEightEvent)
        registry.register(VideoCollaborationEvent.KIND, ::VideoCollaborationEvent)
        registry.register(WakeUpEvent.KIND, ::WakeUpEvent)
        registry.register(WorkoutRecordEvent.KIND, ::WorkoutRecordEvent)
        registry.register(ExerciseTemplateEvent.KIND, ::ExerciseTemplateEvent)
        registry.register(WorkoutTemplateEvent.KIND, ::WorkoutTemplateEvent)

        // kind:20001 is shared with Buzz's PresenceUpdateEvent. BitChat presence always carries a `g` (geohash) tag.
        registry.variant(GeohashPresenceEvent.KIND, matches = { tags -> tags.any { it.size > 1 && it[0] == "g" } }, builder = ::GeohashPresenceEvent)

        // kind:38000 is NIP-87's mint recommendation (PaymentEventKinds, priority 0), reused by an
        // auditable-voting app for ballots and by BAO Markets for prediction markets.
        registry.variant(BallotEvent.KIND, priority = 10, matches = BallotEvent::isBallot, builder = ::BallotEvent)
        registry.variant(PredictionMarketEvent.KIND, priority = 20, matches = PredictionMarketEvent::isPredictionMarket, builder = ::PredictionMarketEvent)

        // kind:30301 is a Kanban board in NIP PR #1665, but WalletScrutiny publishes its
        // reproducible-build verifications on it and an encrypted planner app its tasks.
        // Verifications carry `i` + `status`, boards a `title` or a named `col`; the planner's
        // events fall to UnrecognizedKind30301Event (addressable, never indexed, no edges).
        registry.variant(KanbanBoardEvent.KIND, priority = 0, matches = BuildVerificationEvent::isBuildVerification, builder = ::BuildVerificationEvent)
        registry.variant(KanbanBoardEvent.KIND, priority = 10, matches = KanbanBoardEvent::isKanbanBoard, builder = ::KanbanBoardEvent)
        registry.register(KanbanBoardEvent.KIND, ::UnrecognizedKind30301Event)
        registry.probeAs(KanbanBoardEvent.KIND, ::KanbanBoardEvent)

        // Kanban cards (NIP PR #1665) share 30302 with Fieldbook's team memberships.
        registry.variant(KanbanCardEvent.KIND, matches = KanbanCardEvent::isKanbanCard, builder = ::KanbanCardEvent)
        registry.register(KanbanCardEvent.KIND, ::UnrecognizedKind30302Event)
        registry.probeAs(KanbanCardEvent.KIND, ::KanbanCardEvent)
    }
}
