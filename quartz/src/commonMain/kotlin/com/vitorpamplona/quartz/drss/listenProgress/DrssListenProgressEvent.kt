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
package com.vitorpamplona.quartz.drss.listenProgress

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nipXXPodcasting20.episode.Podcasting20EpisodeEvent

/**
 * drss.io's listening progress in one episode (kind 30054, which it shares with the Podcasting-2.0
 * [Podcasting20EpisodeEvent]). drss bridges a podcast's RSS feed to Nostr as one kind-30023 article
 * per episode, its audio in an `imeta`; its Listen player saves where the listener is in each:
 *
 * - `d` = the episode article's own `d`, so each episode has one record per listener;
 * - `a` = the episode article (`30023:<feed bot>:<d>`);
 * - `position` = the playhead in seconds; dropped once the episode is played to the end;
 * - `duration` = the episode's length in seconds, when the player knew it;
 * - `completed` = when the listener first played it through (unix seconds);
 * - `plays` = how many times the playhead crossed 90% of the episode.
 *
 * The player saves at most once a minute while playing, and on pause, end and tab hide. drss's
 * source is private: this follows the events it publishes and its `static/js/listen-player.js`.
 */
@Immutable
class DrssListenProgressEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    AddressHintProvider {
    /** The episode article this record tracks. */
    fun episode(): ATag? = tags.firstNotNullOfOrNull(ATag::parse)

    /** The playhead in seconds, or null once the episode was played to the end. */
    fun position(): Long? = tags.firstNotNullOfOrNull { seconds(it, POSITION) }

    fun durationInSeconds(): Long? = tags.firstNotNullOfOrNull { seconds(it, DURATION) }

    /** When the listener first played the episode through (unix seconds). */
    fun completedAt(): Long? = tags.firstNotNullOfOrNull { seconds(it, COMPLETED) }

    fun plays(): Long? = tags.firstNotNullOfOrNull { seconds(it, PLAYS) }

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    override fun linkedAddressIds(): List<String> = tags.mapNotNull(ATag::parseValidAddress)

    companion object {
        const val KIND = Podcasting20EpisodeEvent.KIND

        const val POSITION = "position"
        const val DURATION = "duration"
        const val COMPLETED = "completed"
        const val PLAYS = "plays"

        private fun seconds(
            tag: Array<String>,
            name: String,
        ): Long? = if (tag.size > 1 && tag[0] == name) tag[1].toLongOrNull()?.takeIf { it >= 0 } else null

        /**
         * True for drss's progress shape: an `a` to the episode and at least one of `position`,
         * `duration`, `completed` or `plays`. Checked after [Podcasting20EpisodeEvent.isPodcastEpisode],
         * so an episode that happens to carry one of these names stays an episode.
         */
        fun isListenProgress(tags: TagArray): Boolean =
            tags.fastAny { it.size > 1 && it[0] == ATag.TAG_NAME && it[1].isNotEmpty() } &&
                tags.fastAny { it.size > 1 && (it[0] == POSITION || it[0] == DURATION || it[0] == COMPLETED || it[0] == PLAYS) }
    }
}
