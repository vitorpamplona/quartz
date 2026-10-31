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
import com.vitorpamplona.quartz.drss.listenProgress.tags.CompletedTag
import com.vitorpamplona.quartz.drss.listenProgress.tags.PlaysTag
import com.vitorpamplona.quartz.drss.listenProgress.tags.PositionTag
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nipXXPodcasting20.episode.tags.DurationTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * drss.io's listening progress in one episode, a NIP-78 record (kind 30078). drss bridges a
 * podcast's RSS feed to Nostr as one kind-30023 article per episode, its audio in an `imeta`; its
 * Listen player saves where the listener is in each:
 *
 * - `d` = [D_TAG_PREFIX] + the episode article's own `d`, so each episode has one record per
 *   listener;
 * - `a` = the episode article (`30023:<feed bot>:<d>`);
 * - `L` = [NAMESPACE] and `l` = [LABEL], NIP-32 labels relays index, so a reader asks for its
 *   records with `#l` instead of fetching every app's preferences;
 * - `position` = the playhead in seconds; dropped once the episode is played to the end;
 * - `duration` = the episode's length in seconds, when the player knew it;
 * - `completed` = when the listener first played it through (unix seconds);
 * - `plays` = how many times the playhead crossed 90% of the episode.
 *
 * drss wrote the same record as kind 30054 ([DrssListenProgressEvent]) until 2026-10, when it moved
 * to 30078 to stop sharing a number with Podcasting-2.0 episodes; its History page lifts a
 * listener's 30054 records into this kind and deletes them. The player saves at most once a minute
 * while playing, and on pause, end and tab hide. This follows the events drss publishes and its
 * `static/js/listen-player.js` and `listen-progress-lift.js`, as seen on 2026-10-31. Nothing to
 * search: it is numbers about another event.
 *
 * **Kind 30078 is every app's preferences.** `EventFactory` builds this class for
 * [isListenProgress] tags and the generic `AppSpecificDataEvent` for every other `d`.
 */
@Immutable
class DrssListenProgressAppDataEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    DrssListenProgress,
    AddressHintProvider {
    override fun episode(): ATag? = tags.episode()

    override fun episodeAddressId(): String? = episode()?.toTag()

    override fun position(): Long? = tags.position()

    override fun durationInSeconds(): Long? = tags.durationInSeconds()

    override fun completedAt(): Long? = tags.completedAt()

    override fun plays(): Long? = tags.plays()

    override fun addressHints(): List<AddressHint> = tags.mapNotNull(ATag::parseAsHint)

    /** `TRACKED`: the episode article (`a`). */
    override fun linkedAddressIds(): List<String> = listOfNotNull(episodeAddressId())

    companion object {
        const val KIND = 30078
        const val D_TAG_PREFIX = "drss-listen-progress:"
        const val NAMESPACE = "drss"
        const val LABEL = "listen-progress"

        /** The `d` of the record about the episode whose own `d` is [episodeDTag]. */
        fun dTagFor(episodeDTag: String) = D_TAG_PREFIX + episodeDTag

        /** Where [pubKey] keeps its progress through [episode]. */
        fun createAddress(
            pubKey: HexKey,
            episode: Address,
        ) = Address(KIND, pubKey, dTagFor(episode.dTag))

        /**
         * True for drss's progress shape: `d` starting with [D_TAG_PREFIX], an `a` to the episode
         * and at least one of `position`, `duration`, `completed` or `plays` holding a number.
         */
        fun isListenProgress(tags: TagArray): Boolean =
            tags.fastAny { it.size > 1 && it[0] == DTag.TAG_NAME && it[1].startsWith(D_TAG_PREFIX) } &&
                tags.fastAny(ATag::isTagged) &&
                tags.fastAny {
                    PositionTag.parse(it) != null || DurationTag.parse(it) != null || CompletedTag.parse(it) != null || PlaysTag.parse(it) != null
                }

        /**
         * A record of where the listener is in [episode]. Give at least one of the numbers, or it
         * parses back as a plain `AppSpecificDataEvent`.
         */
        fun build(
            episode: ATag,
            position: Long? = null,
            durationInSeconds: Long? = null,
            completedAt: Long? = null,
            plays: Long? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssListenProgressAppDataEvent>.() -> Unit = {},
        ) = eventTemplate<DrssListenProgressAppDataEvent>(KIND, "", createdAt) {
            dTag(dTagFor(episode.dTag))
            episode(episode)
            drssLabels()
            position?.let { position(it) }
            durationInSeconds?.let { duration(it) }
            completedAt?.let { completed(it) }
            plays?.let { plays(it) }
            initializer()
        }

        /**
         * The record to save after [earlier], by drss's rules: [position] is the resume point, and
         * goes once the episode [ended]; a completion is never lost and the first one stands; the
         * play count only grows, by one when this save [countsAPlay] (see [DrssListenPass]). Reaching
         * the end or counting a play completes the episode. [earlier] may be a kind-30054 record:
         * its completion and plays carry over, as drss's lift carries them.
         */
        fun next(
            earlier: DrssListenProgress?,
            episode: ATag,
            position: Long?,
            durationInSeconds: Long?,
            countsAPlay: Boolean = false,
            ended: Boolean = false,
            createdAt: Long = TimeUtils.now(),
        ): EventTemplate<DrssListenProgressAppDataEvent> {
            val earlierCompletion = earlier?.completedAt()
            val completedAt = earlierCompletion ?: if (ended || countsAPlay) createdAt else null
            // A completion recorded before drss counted plays stands for one.
            val earlierPlays = earlier?.plays() ?: if (earlierCompletion != null) 1L else 0L
            val plays = earlierPlays + if (countsAPlay) 1 else 0
            return build(
                episode = episode,
                position = if (ended) null else position?.coerceAtLeast(0),
                durationInSeconds = durationInSeconds?.takeIf { it > 0 } ?: earlier?.durationInSeconds(),
                completedAt = completedAt,
                plays = plays.takeIf { it > 0 },
                createdAt = createdAt,
            )
        }
    }
}
