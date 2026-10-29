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
package com.vitorpamplona.quartz.drss

import com.vitorpamplona.quartz.drss.clips.DrssClip
import com.vitorpamplona.quartz.drss.clips.clipRangeMillis
import com.vitorpamplona.quartz.drss.eventList.DrssEventListEvent
import com.vitorpamplona.quartz.drss.eventList.tags.ListItemTag
import com.vitorpamplona.quartz.drss.listFolder.DrssListFolderEvent
import com.vitorpamplona.quartz.drss.listenProgress.DrssListenPass
import com.vitorpamplona.quartz.drss.listenProgress.DrssListenProgressEvent
import com.vitorpamplona.quartz.drss.provenance.DrssProvenanceFolderEvent
import com.vitorpamplona.quartz.drss.readWriteLists.DrssReadWriteListsEvent
import com.vitorpamplona.quartz.drss.readWriteLists.tags.ListRoleTag
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip78AppData.AppSpecificDataEvent
import com.vitorpamplona.quartz.nip84Highlights.HighlightEvent
import com.vitorpamplona.quartz.nip94FileMetadata.tags.ThumbTag
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a client writes for drss.io: its read and write lists, listening progress and folder edits. */
class DrssWritesTest {
    private val me = "dd81a8bacbab0b5c3007d1672fb8301383b4e9583d431835985057223eb298a5"
    private val episode = ATag(30023, "4093f1972489272756a98103e457a26914f00216317cf38d57710b431360d30b", "seroter-daily-reading-844")

    private fun <T : Event> EventTemplate<T>.parse(): T = EventFactory.create("11".repeat(32), me, createdAt, kind, tags, content, "00".repeat(64))

    @Test
    fun readsDrssOwnPreferenceAsOneFolderForBoth() {
        // plantimals' preference on relay.nostr.io, 2026-10-29.
        val pref =
            EventFactory.create<Event>(
                "11".repeat(32),
                me,
                1_786_133_499L,
                30078,
                arrayOf(arrayOf("d", "read-write-lists"), arrayOf("a", "30101:$me:drss", "wss://relay.nostr.io")),
                "",
                "00".repeat(64),
            )
        assertIs<DrssReadWriteListsEvent>(pref)
        assertEquals("30101:$me:drss", pref.readList()?.toTag())
        assertEquals(pref.readList(), pref.writeList())
        assertEquals(listOf("30101:$me:drss"), pref.linkedAddressIds())
    }

    @Test
    fun otherPreferencesStayAppData() {
        val other = EventFactory.create<Event>("11".repeat(32), me, 1L, 30078, arrayOf(arrayOf("d", "amethyst-settings")), "{}", "00".repeat(64))
        assertIs<AppSpecificDataEvent>(other)
        val noList = EventFactory.create<Event>("11".repeat(32), me, 1L, 30078, arrayOf(arrayOf("d", "read-write-lists")), "", "00".repeat(64))
        assertIs<AppSpecificDataEvent>(noList)
    }

    @Test
    fun writesTwoListsWithRolesAndOneWithout() {
        val read = ATag(30101, me, "drss")
        val write = ATag(30101, me, "drss-reading")

        val split = DrssReadWriteListsEvent.build(read, write).parse()
        assertEquals(listOf(ListRoleTag(read, ListRoleTag.Role.READ), ListRoleTag(write, ListRoleTag.Role.WRITE)), split.lists())
        assertEquals(read, split.readList())
        assertEquals(write, split.writeList())

        val same = DrssReadWriteListsEvent.build(read).parse()
        assertEquals(listOf(ListRoleTag(read, null)), same.lists())
        assertContentEquals(arrayOf("a", "30101:$me:drss"), same.tags.first { it[0] == "a" })
    }

    @Test
    fun aPlayCountsOnceWhenPlaybackCrossesNinetyPercent() {
        val pass = DrssListenPass()
        assertFalse(pass.update(80.0, 100.0, isPlaying = true), "the first update only starts the pass")
        assertFalse(pass.update(89.0, 100.0, isPlaying = true))
        assertTrue(pass.update(90.5, 100.0, isPlaying = true))
        assertFalse(pass.update(95.0, 100.0, isPlaying = true), "one play per pass")

        pass.update(5.0, 100.0, isPlaying = true)
        assertFalse(pass.update(50.0, 100.0, isPlaying = true))
        assertTrue(pass.update(91.0, 100.0, isPlaying = true), "going back below 10% re-arms it")
    }

    @Test
    fun aSeekOrAPauseDoesNotCountAPlay() {
        val pass = DrssListenPass()
        pass.update(10.0, 100.0, isPlaying = true)
        pass.seeked()
        assertFalse(pass.update(95.0, 100.0, isPlaying = true), "seeking past the mark")

        val paused = DrssListenPass()
        paused.update(85.0, 100.0, isPlaying = false)
        assertFalse(paused.update(92.0, 100.0, isPlaying = false))

        assertFalse(DrssListenPass().apply { update(85.0, 0.0, true) }.update(95.0, 0.0, true), "no duration, no play")
    }

    @Test
    fun progressKeepsTheFirstCompletionAndOnlyGrowsPlays() {
        val first = DrssListenProgressEvent.next(null, episode, position = 300, durationInSeconds = 700, createdAt = 1_000).parse()
        assertEquals(300, first.position())
        assertNull(first.completedAt())
        assertNull(first.plays())
        assertEquals(episode.dTag, first.dTag())

        val played = DrssListenProgressEvent.next(first, episode, position = 640, durationInSeconds = 700, countsAPlay = true, createdAt = 2_000).parse()
        assertEquals(640, played.position())
        assertEquals(2_000, played.completedAt())
        assertEquals(1, played.plays())

        val ended = DrssListenProgressEvent.next(played, episode, position = 700, durationInSeconds = null, ended = true, createdAt = 3_000).parse()
        assertNull(ended.position(), "the end is not a resume point")
        assertEquals(2_000, ended.completedAt(), "the first completion stands")
        assertEquals(1, ended.plays())
        assertEquals(700, ended.durationInSeconds(), "carried from the earlier record")

        val replay = DrssListenProgressEvent.next(ended, episode, position = 20, durationInSeconds = 700, createdAt = 4_000).parse()
        assertEquals(20, replay.position())
        assertEquals(2_000, replay.completedAt())
        assertEquals(1, replay.plays())
    }

    @Test
    fun aCompletionFromBeforePlaysWereCountedIsOnePlay() {
        val old = DrssListenProgressEvent.build(episode, completedAt = 1_500, durationInSeconds = 700, createdAt = 1_500).parse()
        val again = DrssListenProgressEvent.next(old, episode, position = 650, durationInSeconds = 700, countsAPlay = true, createdAt = 5_000).parse()
        assertEquals(2, again.plays())
        assertEquals(1_500, again.completedAt())
    }

    @Test
    fun foldersGainLoseAndRenameChildren() {
        val feeds = ATag(30000, me, "drss-feeds")
        val podcasts = ATag(30004, me, "drss-podcasts")
        val folder = DrssListFolderEvent.build("drss", "DRSS lists", listOf(feeds)).parse()

        val added = DrssListFolderEvent.addChild(folder, podcasts).parse()
        assertEquals(listOf(feeds, podcasts), added.children())
        assertEquals(listOf(feeds, podcasts), DrssListFolderEvent.addChild(added, podcasts).parse().children(), "no duplicate")

        val removed = DrssListFolderEvent.removeChild(added, Address(30000, me, "drss-feeds")).parse()
        assertEquals(listOf(podcasts), removed.children())
        assertEquals("drss", removed.dTag())

        val renamed = DrssListFolderEvent.retitle(removed, "lists").parse()
        assertEquals("lists", renamed.title())
        assertEquals(listOf(podcasts), renamed.children())
    }

    @Test
    fun provenanceFoldersGainAndLoseRecords() {
        val record = ATag(31985, me, "4a6edcf5840db6709d8d82216991031a80416246f25834368adb8de8cf95365d")
        val other = ATag(31985, me, "b31111ad52005903be6c93b858cb9bba355d7bddaac1d614e2e451d44fcbe302")
        val root = DrssProvenanceFolderEvent.build("vault-provenance/_root", "Root", listOf(record)).parse()

        val added = DrssProvenanceFolderEvent.addChild(root, other).parse()
        assertEquals(listOf(record, other), added.children())
        assertEquals(listOf(other), DrssProvenanceFolderEvent.removeChild(added, record.let { Address(it.kind, it.pubKeyHex, it.dTag) }).parse().children())
    }

    @Test
    fun aClipIsAHighlightOfTheEpisodeWithItsRange() {
        val clip = DrssClip.build(episode, episode.pubKeyHex, startMillis = 754_000, endMillis = 784_000, note = "the flags part").parse()
        assertIs<HighlightEvent>(clip)
        assertEquals(754_000L..784_000L, clip.clipRangeMillis())
        assertEquals(episode.toTag(), clip.inPost()?.toTag())
        assertEquals(episode.pubKeyHex, clip.author())
        assertEquals("the flags part", clip.comment())
        assertEquals("", clip.quote())

        val relay = RelayUrlNormalizer.normalizeOrNull("wss://relay.nostr.io")
        val hinted = DrssClip.build(ATag(episode.kind, episode.pubKeyHex, episode.dTag, relay), episode.pubKeyHex, 0, 30_000).parse()
        assertEquals(relay, hinted.tags.firstOrNull { it[0] == "a" }?.getOrNull(2)?.let(RelayUrlNormalizer::normalizeOrNull), "keeps where the episode is")

        val plain = HighlightEvent.build("a quote", address = episode.toTag()).parse()
        assertNull(plain.clipRangeMillis(), "a text highlight is no clip")
    }

    @Test
    fun collectionsGainAndLoseItems() {
        val a = ListItemTag("aa".repeat(32), null, 1_000)
        val b = ListItemTag("bb".repeat(32), null, 2_000)
        val list = DrssEventListEvent.build("papers", listOf(a), title = "papers").parse()

        val added = DrssEventListEvent.addItem(list, b).parse()
        assertEquals(listOf(a.eventId, b.eventId), added.itemIds())
        assertEquals(listOf(a.eventId, b.eventId), DrssEventListEvent.addItem(added, b).parse().itemIds(), "no duplicate")
        assertEquals(listOf(b.eventId), DrssEventListEvent.removeItem(added, a.eventId).parse().itemIds())
    }

    @Test
    fun aPictureReplacesTheOldOneAndItsThumbnail() {
        val folder = DrssListFolderEvent.build("drss", "DRSS lists", listOf(ATag(30000, me, "drss-feeds"))).parse()
        val withPicture = DrssListPicture.set(folder, "https://blossom.example/a.jpg", ThumbTag("https://cdn.example/a-192.jpg", "cc".repeat(32))).parse()
        assertEquals("https://blossom.example/a.jpg", withPicture.image())
        assertEquals("https://cdn.example/a-192.jpg", withPicture.thumb()?.imageUrl)
        assertEquals("https://cdn.example/a-192.jpg", DrssListPicture.preview(withPicture.tags))
        assertEquals(1, withPicture.children().size, "the children stay")

        val replaced = DrssListPicture.set(withPicture, "https://blossom.example/b.jpg").parse()
        assertEquals("https://blossom.example/b.jpg", replaced.image())
        assertNull(replaced.thumb(), "the old thumbnail showed the old picture")

        val cleared = DrssListPicture.set(replaced, null).parse()
        assertNull(cleared.image())
    }
}
