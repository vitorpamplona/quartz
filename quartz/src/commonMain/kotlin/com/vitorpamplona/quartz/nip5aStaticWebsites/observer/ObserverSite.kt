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
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteAggregateHash
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A person's front pages as one public NIP-5A named site (`quartz/plans/2026-11-11-observer-site.md`):
 * kind 35128 with `d` = `observer`, `/index.html` = the newest edition, one `/YYYY-MM-DD.html`
 * per edition, `/archive.html` and `/editions.json`.
 *
 * The rules every producer and reader shares live here: naming, upsert, roll-off and
 * manifest assembly. Uploading the files and signing the manifest are the client's.
 */
object ObserverSite {
    const val D_TAG = "observer"
    const val INDEX_PATH = "/index.html"
    const val ARCHIVE_PATH = "/archive.html"
    const val EDITIONS_PATH = "/editions.json"
    const val FORMAT_VERSION = 1

    /** Editions a site keeps before the byte budget is even considered. */
    const val KEEP = 90

    /** The event-size budget when no write relay publishes a `max_message_length`. */
    const val DEFAULT_BUDGET_BYTES = 60_000

    /** Room left for the `["EVENT", …]` envelope around the signed event. */
    const val ENVELOPE_BYTES = 1_024

    /** The tag-count budget when no write relay publishes a `max_event_tags`. */
    const val DEFAULT_MAX_TAGS = 100

    const val DEFAULT_TITLE = "The Nostr Observer"

    private val dayPattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val dayPathPattern = Regex("^/(\\d{4}-\\d{2}-\\d{2})\\.html$")

    fun address(author: HexKey) = Address(NamedSiteEvent.KIND, author, D_TAG)

    fun title(displayName: String?) = displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it's Observer" } ?: DEFAULT_TITLE

    fun description(displayName: String?) =
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { "A daily front page from $it's web of trust" }
            ?: "A daily front page from a web of trust"

    /** A `YYYY-MM-DD` that is a real calendar date: not `2026-13-45`, not `2026-02-30`. */
    fun isDay(day: String): Boolean {
        if (!dayPattern.matches(day)) return false
        val year = day.substring(0, 4).toInt()
        val month = day.substring(5, 7).toInt()
        val date = day.substring(8, 10).toInt()
        if (month !in 1..12 || date < 1) return false
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val length =
            when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
        return date <= length
    }

    /**
     * The newest day a real edition can carry at [now]: tomorrow in UTC, for a producer whose
     * clock runs ahead. A later day is somebody's bad data and never takes `/index.html`.
     */
    fun latestDay(now: Long = TimeUtils.now()) = utcDay(now + 86_400L)

    fun dayPath(day: String) = "/$day.html"

    /** The day an edition's dated path names, or null for any other path. */
    fun dayOfPath(path: String): String? =
        dayPathPattern
            .matchEntire(path)
            ?.groupValues
            ?.get(1)
            ?.takeIf(::isDay)

    /** The UTC calendar date of [epochSeconds], `YYYY-MM-DD`: an edition's day. */
    fun utcDay(epochSeconds: Long = TimeUtils.now()): String {
        // Howard Hinnant's days-to-civil: exact for the proleptic Gregorian calendar, no date library.
        val z = epochSeconds.floorDiv(86_400L) + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val y = yoe + era * 400L + (if (m <= 2L) 1L else 0L)
        return y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
    }

    /** Newest day first; days are ISO dates, so text order is date order. */
    fun newestFirst(editions: List<ObserverEdition>) = editions.sortedByDescending { it.day }

    /**
     * The editions a manifest lists when its `/editions.json` can't be read: one per dated
     * `path` tag, with only `day`, `path` and `sha256`.
     */
    fun editionsFromPaths(paths: List<PathTag>): List<ObserverEdition> =
        newestFirst(
            paths
                .mapNotNull { tag ->
                    val day = dayOfPath(tag.path) ?: return@mapNotNull null
                    val hash = tag.hash.lowercase()
                    if (Hex.isHex64(hash)) ObserverEdition(day, dayPath(day), hash) else null
                }.distinctBy { it.day },
        )

    /**
     * The editions a site serves: one per dated `path` tag ([fromPaths], see [editionsFromPaths]),
     * described by the `/editions.json` entry ([indexed]) for that day when it names the same
     * bytes. A day the index lists but no path serves is gone (rolled off by a racing producer);
     * a day a path serves that the index misses, or lists under another hash, still shows, with
     * only its day and hash.
     */
    fun mergeEditions(
        indexed: List<ObserverEdition>,
        fromPaths: List<ObserverEdition>,
    ): List<ObserverEdition> {
        val byDay = indexed.associateBy { it.day }
        return newestFirst(
            fromPaths.map { served ->
                val described = byDay[served.day]
                if (described != null && described.sha256.equals(served.sha256, ignoreCase = true)) {
                    described.copy(path = dayPath(served.day), sha256 = served.sha256.lowercase())
                } else {
                    served
                }
            },
        )
    }

    /** [editions] with [edition] in it, replacing any edition of the same day; newest first. */
    fun upsert(
        editions: List<ObserverEdition>,
        edition: ObserverEdition,
    ): List<ObserverEdition> = newestFirst(editions.filter { it.day != edition.day } + edition)

    /** The event-size budget from the write relays' NIP-11 `max_message_length`s (null = unknown). */
    fun budget(maxMessageLengths: List<Int?>): Int =
        maxMessageLengths
            .filterNotNull()
            .filter { it > ENVELOPE_BYTES }
            .minOrNull()
            ?.let { it - ENVELOPE_BYTES } ?: DEFAULT_BUDGET_BYTES

    /** The tag budget from the write relays' NIP-11 `max_event_tags` (null = unknown). */
    fun tagBudget(maxEventTags: List<Int?>): Int = maxEventTags.filterNotNull().filter { it > 0 }.minOrNull() ?: DEFAULT_MAX_TAGS

    /**
     * What the site keeps: the newest [keep], then the oldest dropped one at a time until
     * [fits] says the manifest for the rest fits. The [protect]ed day (today's edition) is never
     * dropped, even when a later-dated edition is listed, and neither is the last one left, so
     * a site always has an `/index.html`.
     */
    fun rollOff(
        editions: List<ObserverEdition>,
        keep: Int = KEEP,
        protect: String? = null,
        fits: (List<ObserverEdition>) -> Boolean,
    ): List<ObserverEdition> {
        val sorted = newestFirst(editions)
        val guarded = protect?.let { day -> sorted.firstOrNull { it.day == day } }
        val others = sorted.filter { it !== guarded }.take(maxOf(0, maxOf(1, keep) - (if (guarded != null) 1 else 0)))
        var kept = newestFirst(listOfNotNull(guarded) + others)
        while (kept.size > 1 && !fits(kept)) {
            val oldest = kept.lastOrNull { it !== guarded } ?: break
            kept = kept.filter { it !== oldest }
        }
        return kept
    }

    /**
     * The manifest's files for [editions] (newest first): `/index.html` serving the newest
     * edition up to [maxDay] (see [latestDay]; every day when null), every dated file, the
     * archive and the index. Dated paths are named from the day, never from the entry.
     */
    fun paths(
        editions: List<ObserverEdition>,
        archiveSha256: HexKey,
        editionsSha256: HexKey,
        maxDay: String? = null,
    ): List<PathTag> {
        val sorted = newestFirst(editions)
        return buildList {
            sorted.firstOrNull { maxDay == null || it.day <= maxDay }?.let { add(PathTag(INDEX_PATH, it.sha256)) }
            sorted.forEach { add(PathTag(dayPath(it.day), it.sha256)) }
            add(PathTag(ARCHIVE_PATH, archiveSha256))
            add(PathTag(EDITIONS_PATH, editionsSha256))
        }
    }

    /**
     * The unsigned manifest. [createdAt] should be `max(now, previous.createdAt + 1)` so a
     * manifest built from an older read never loses to it by clock skew; see [nextCreatedAt].
     */
    fun build(
        editions: List<ObserverEdition>,
        archiveSha256: HexKey,
        editionsSha256: HexKey,
        displayName: String?,
        servers: List<String>,
        createdAt: Long = TimeUtils.now(),
        maxDay: String? = latestDay(createdAt),
    ): EventTemplate<NamedSiteEvent> {
        val paths = paths(editions, archiveSha256, editionsSha256, maxDay)
        return NamedSiteEvent.build(
            identifier = D_TAG,
            paths = paths,
            servers = servers,
            title = title(displayName),
            description = description(displayName),
            createdAt = createdAt,
        ) {
            siteAggregateHash(paths)
        }
    }

    fun nextCreatedAt(
        previous: NamedSiteEvent?,
        now: Long = TimeUtils.now(),
    ) = if (previous == null) now else maxOf(now, previous.createdAt + 1)

    /**
     * The size in bytes of [template] once signed by [author]: what a relay's
     * `max_message_length` measures, less the envelope. Hashes and signatures have fixed
     * lengths, so placeholders measure exactly.
     */
    fun signedSize(
        template: EventTemplate<NamedSiteEvent>,
        author: HexKey,
    ): Int =
        NamedSiteEvent(PLACEHOLDER_ID, author, template.createdAt, template.tags, template.content, PLACEHOLDER_SIG)
            .toJson()
            .encodeToByteArray()
            .size

    /**
     * [rollOff] against [budgetBytes] and [maxTags], measuring each candidate manifest as
     * signed. The archive and index hashes don't change the size (all hashes are 64 hex), so
     * placeholders stand in for files not built yet.
     */
    fun keepWithinBudget(
        editions: List<ObserverEdition>,
        author: HexKey,
        displayName: String?,
        servers: List<String>,
        budgetBytes: Int,
        maxTags: Int = DEFAULT_MAX_TAGS,
        keep: Int = KEEP,
        protect: String? = null,
        createdAt: Long = TimeUtils.now(),
    ): List<ObserverEdition> =
        rollOff(editions, keep, protect) { candidate ->
            val template = build(candidate, PLACEHOLDER_HASH, PLACEHOLDER_HASH, displayName, servers, createdAt)
            template.tags.size <= maxTags && signedSize(template, author) <= budgetBytes
        }

    private val PLACEHOLDER_HASH = "0".repeat(64)
    private val PLACEHOLDER_ID = "0".repeat(64)
    private val PLACEHOLDER_SIG = "0".repeat(128)
}
