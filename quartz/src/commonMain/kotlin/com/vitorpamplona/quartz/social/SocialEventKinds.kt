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
package com.vitorpamplona.quartz.social

import com.vitorpamplona.quartz.feedDefinition.FeedDefinitionEvent
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
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
import com.vitorpamplona.quartz.nip30CustomEmoji.pack.EmojiPackEvent
import com.vitorpamplona.quartz.nip30CustomEmoji.selection.EmojiListEvent
import com.vitorpamplona.quartz.nip30CustomEmoji.stickers.StickerPackEvent
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip35Torrents.TorrentCommentEvent
import com.vitorpamplona.quartz.nip35Torrents.TorrentEvent
import com.vitorpamplona.quartz.nip37Drafts.DraftWrapEvent
import com.vitorpamplona.quartz.nip37Drafts.privateOutbox.PrivateOutboxRelayListEvent
import com.vitorpamplona.quartz.nip38UserStatus.UserStatusEvent
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.PinListEvent
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
import com.vitorpamplona.quartz.nip58Badges.accepted.AcceptedBadgeSetEvent
import com.vitorpamplona.quartz.nip58Badges.award.BadgeAwardEvent
import com.vitorpamplona.quartz.nip58Badges.definition.BadgeDefinitionEvent
import com.vitorpamplona.quartz.nip58Badges.profile.ProfileBadgesEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
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
import com.vitorpamplona.quartz.nip88Polls.poll.PollEvent
import com.vitorpamplona.quartz.nip88Polls.response.PollResponseEvent
import com.vitorpamplona.quartz.nip89AppHandlers.definition.AppDefinitionEvent
import com.vitorpamplona.quartz.nip89AppHandlers.recommendation.AppRecommendationEvent
import com.vitorpamplona.quartz.nip94FileMetadata.FileMetadataEvent
import com.vitorpamplona.quartz.nipA0VoiceMessages.VoiceEvent
import com.vitorpamplona.quartz.nipA0VoiceMessages.VoiceReplyEvent
import com.vitorpamplona.quartz.nipA4PublicMessages.PublicMessageEvent
import com.vitorpamplona.quartz.nipB0WebBookmarks.WebBookmarkEvent
import com.vitorpamplona.quartz.nipC0CodeSnippets.CodeSnippetEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-social` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object SocialEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(AcceptedBadgeSetEvent.KIND, ::AcceptedBadgeSetEvent)
        registry.register(AppDefinitionEvent.KIND, ::AppDefinitionEvent)
        registry.register(AppRecommendationEvent.KIND, ::AppRecommendationEvent)
        registry.register(AppDataEvent.KIND, ::AppDataEvent)
        registry.register(AppSpecificDataEvent.KIND, ::AppSpecificDataEvent)
        registry.register(ArticleCurationSetEvent.KIND, ::ArticleCurationSetEvent)
        registry.register(BadgeAwardEvent.KIND, ::BadgeAwardEvent)
        registry.register(BadgeDefinitionEvent.KIND, ::BadgeDefinitionEvent)
        registry.register(BlockedRelayListEvent.KIND, ::BlockedRelayListEvent)
        registry.register(NestsServersEvent.KIND, ::NestsServersEvent)
        registry.register(BroadcastRelayListEvent.KIND, ::BroadcastRelayListEvent)
        registry.register(BookmarkListEvent.KIND, ::BookmarkListEvent)
        registry.register(OldBookmarkListEvent.KIND, ::OldBookmarkListEvent)
        registry.register(CalendarDateSlotEvent.KIND, ::CalendarDateSlotEvent)
        registry.register(CalendarCollectionEvent.KIND, ::CalendarCollectionEvent)
        registry.register(CalendarTimeSlotEvent.KIND, ::CalendarTimeSlotEvent)
        registry.register(CalendarRSVPEvent.KIND, ::CalendarRSVPEvent)
        registry.register(ChatEvent.KIND, ::ChatEvent)
        registry.register(CodeSnippetEvent.KIND, ::CodeSnippetEvent)
        registry.register(FavoriteRelayListEvent.KIND, ::FavoriteRelayListEvent)
        registry.register(ChannelCreateEvent.KIND, ::ChannelCreateEvent)
        registry.register(ChannelHideMessageEvent.KIND, ::ChannelHideMessageEvent)
        registry.register(PublicChatListEvent.KIND, ::PublicChatListEvent)
        registry.register(ChannelMessageEvent.KIND, ::ChannelMessageEvent)
        registry.register(ChannelMetadataEvent.KIND, ::ChannelMetadataEvent)
        registry.register(ChannelMuteUserEvent.KIND, ::ChannelMuteUserEvent)
        registry.register(CommentEvent.KIND, ::CommentEvent)
        registry.register(CommunityDefinitionEvent.KIND, ::CommunityDefinitionEvent)
        registry.register(CommunityListEvent.KIND, ::CommunityListEvent)
        registry.register(CommunityPostApprovalEvent.KIND, ::CommunityPostApprovalEvent)
        registry.register(CommunityRulesEvent.KIND, ::CommunityRulesEvent)
        registry.register(ContactListEvent.KIND, ::ContactListEvent)
        registry.register(DraftWrapEvent.KIND, ::DraftWrapEvent)
        registry.register(EmojiPackEvent.KIND, ::EmojiPackEvent)
        registry.register(StickerPackEvent.KIND, ::StickerPackEvent)
        registry.register(EmojiListEvent.KIND, ::EmojiListEvent)
        registry.register(FeedDefinitionEvent.KIND, ::FeedDefinitionEvent)
        registry.register(FileMetadataEvent.KIND, ::FileMetadataEvent)
        registry.register(StarterPackEvent.KIND, ::StarterPackEvent)
        registry.register(GenericRepostEvent.KIND, ::GenericRepostEvent)
        registry.register(GeohashListEvent.KIND, ::GeohashListEvent)
        registry.register(GitAuthorListEvent.KIND, ::GitAuthorListEvent)
        registry.register(GitRepositoryListEvent.KIND, ::GitRepositoryListEvent)
        registry.register(GoodWikiAuthorListEvent.KIND, ::GoodWikiAuthorListEvent)
        registry.register(GoodWikiRelayListEvent.KIND, ::GoodWikiRelayListEvent)
        registry.register(ZapGoalEvent.KIND, ::ZapGoalEvent)
        registry.register(FavoriteAlgoFeedsListEvent.KIND, ::FavoriteAlgoFeedsListEvent)
        registry.register(FavoriteFollowSetsListEvent.KIND, ::FavoriteFollowSetsListEvent)
        registry.register(InterestListEvent.KIND, ::InterestListEvent)
        registry.register(HighlightEvent.KIND, ::HighlightEvent)
        registry.register(IndexerRelayListEvent.KIND, ::IndexerRelayListEvent)
        registry.register(InterestSetEvent.KIND, ::InterestSetEvent)
        registry.register(LabelEvent.KIND, ::LabelEvent)
        registry.register(KindMuteSetEvent.KIND, ::KindMuteSetEvent)
        registry.register(BookmarkSetEvent.KIND, ::BookmarkSetEvent)
        registry.register(LiveActivitiesChatMessageEvent.KIND, ::LiveActivitiesChatMessageEvent)
        registry.register(LiveActivitiesClipEvent.KIND, ::LiveActivitiesClipEvent)
        registry.register(LiveActivitiesEvent.KIND, ::LiveActivitiesEvent)
        registry.register(LiveActivitiesRaidEvent.KIND, ::LiveActivitiesRaidEvent)
        registry.register(LongFormContentEvent.KIND, ::LongFormContentEvent)
        registry.register(LongFormDraftEvent.KIND, ::LongFormDraftEvent)
        registry.register(MeetingRoomEvent.KIND, ::MeetingRoomEvent)
        registry.register(MeetingRoomPresenceEvent.KIND, ::MeetingRoomPresenceEvent)
        registry.register(MeetingSpaceEvent.KIND, ::MeetingSpaceEvent)
        registry.register(MediaFollowListEvent.KIND, ::MediaFollowListEvent)
        registry.register(MediaStarterPackEvent.KIND, ::MediaStarterPackEvent)
        registry.register(MuteListEvent.KIND, ::MuteListEvent)
        registry.register(FollowSetEvent.KIND, ::FollowSetEvent)
        registry.register(PictureCurationSetEvent.KIND, ::PictureCurationSetEvent)
        registry.register(PictureEvent.KIND, ::PictureEvent)
        registry.register(PinListEvent.KIND, ::PinListEvent)
        registry.register(ProfileBadgesEvent.KIND, ::ProfileBadgesEvent)
        registry.register(PollEvent.KIND, ::PollEvent)
        registry.register(PollResponseEvent.KIND, ::PollResponseEvent)
        registry.register(PrivateOutboxRelayListEvent.KIND, ::PrivateOutboxRelayListEvent)
        registry.register(ProxyRelayListEvent.KIND, ::ProxyRelayListEvent)
        registry.register(PublicMessageEvent.KIND, ::PublicMessageEvent)
        registry.register(ReactionEvent.KIND, ::ReactionEvent)
        registry.register(ExternalReactionEvent.KIND, ::ExternalReactionEvent)
        registry.register(WikiMergeRequestEvent.KIND, ::WikiMergeRequestEvent)
        registry.register(WikiMergeAcceptanceEvent.KIND, ::WikiMergeAcceptanceEvent)
        registry.register(WikiRedirectEvent.KIND, ::WikiRedirectEvent)
        registry.register(RelaySetEvent.KIND, ::RelaySetEvent)
        registry.register(ReportEvent.KIND, ::ReportEvent)
        registry.register(RepostEvent.KIND, ::RepostEvent)
        registry.register(SearchRelayListEvent.KIND, ::SearchRelayListEvent)
        registry.register(SimpleGroupListEvent.KIND, ::SimpleGroupListEvent)
        registry.register(UserStatusEvent.KIND, ::UserStatusEvent)
        registry.register(TextNoteEvent.KIND, ::TextNoteEvent)
        registry.register(ThreadEvent.KIND, ::ThreadEvent)
        registry.register(TorrentEvent.KIND, ::TorrentEvent)
        registry.register(TorrentCommentEvent.KIND, ::TorrentCommentEvent)
        registry.register(TrustedRelayListEvent.KIND, ::TrustedRelayListEvent)
        registry.register(VideoCurationSetEvent.KIND, ::VideoCurationSetEvent)
        registry.register(AddressableNormalVideoEvent.KIND, ::AddressableNormalVideoEvent)
        registry.register(AddressableShortVideoEvent.KIND, ::AddressableShortVideoEvent)
        registry.register(TextTrackEvent.KIND, ::TextTrackEvent)
        registry.register(VideoViewEvent.KIND, ::VideoViewEvent)
        registry.register(VideoNormalEvent.KIND, ::VideoNormalEvent)
        registry.register(VideoShortEvent.KIND, ::VideoShortEvent)
        registry.register(VoiceEvent.KIND, ::VoiceEvent)
        registry.register(VoiceReplyEvent.KIND, ::VoiceReplyEvent)
        registry.register(WebBookmarkEvent.KIND, ::WebBookmarkEvent)
        registry.register(WikiArticleEvent.KIND, ::WikiArticleEvent)
    }
}
