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
package com.vitorpamplona.quartz.nip73ExternalIds

import com.vitorpamplona.quartz.nip73ExternalIds.books.BookId
import com.vitorpamplona.quartz.nip73ExternalIds.location.GeohashId
import com.vitorpamplona.quartz.nip73ExternalIds.movies.MovieId
import com.vitorpamplona.quartz.nip73ExternalIds.papers.PaperId
import com.vitorpamplona.quartz.nip73ExternalIds.podcasts.PodcastEpisodeId
import com.vitorpamplona.quartz.nip73ExternalIds.podcasts.PodcastFeedId
import com.vitorpamplona.quartz.nip73ExternalIds.podcasts.PodcastPublisherId
import com.vitorpamplona.quartz.nip73ExternalIds.topics.HashtagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalIdMatchTest {
    @Test
    fun podcastEpisodeKindFollowsNip73() {
        assertEquals("podcast:item:guid", PodcastEpisodeId("d98d189b").toKind())
        assertEquals("podcast:item:guid:d98d189b", PodcastEpisodeId("d98d189b").toScope())
    }

    @Test
    fun matchIsEqualityNotSubstring() {
        assertTrue(BookId.match("isbn:9780765382030", "9780765382030"))
        assertTrue(BookId.match("isbn:9780765382030", "978-0765382030"))
        assertFalse(BookId.match("isbn:9780765382030", "382030"))

        assertTrue(PaperId.match("doi:10.1000/182", "10.1000/182"))
        assertFalse(PaperId.match("doi:10.1000/182", "182"))

        assertTrue(MovieId.match("isan:0000-0000-401a-0000-7", "0000-0000-401A-0000-7"))
        assertFalse(MovieId.match("isan:0000-0000-401a-0000-7", "401a"))

        assertTrue(PodcastFeedId.match("podcast:guid:917393e3", "917393e3"))
        assertFalse(PodcastFeedId.match("podcast:guid:917393e3", "93e3"))
        assertTrue(PodcastEpisodeId.match("podcast:item:guid:PC20-229", "PC20-229"))
        assertFalse(PodcastEpisodeId.match("podcast:item:guid:PC20-229", "229"))
        assertTrue(PodcastPublisherId.match("podcast:publisher:guid:18bcbf10", "18bcbf10"))
        assertFalse(PodcastPublisherId.match("podcast:publisher:guid:18bcbf10", "bcbf"))

        assertTrue(HashtagId.match("#bitcoin", "Bitcoin"))
        assertFalse(HashtagId.match("#bitcoin", "coin"))
    }

    @Test
    fun geohashMatchesItsOwnCellAndEnclosingCells() {
        assertTrue(GeohashId.match("geo:drt3n", "drt3n"))
        assertTrue(GeohashId.match("geo:drt3n", "drt3"))
        assertFalse(GeohashId.match("geo:drt3n", "t3n"))
        assertFalse(GeohashId.match("geo:drt3", "drt3n"))
    }
}
