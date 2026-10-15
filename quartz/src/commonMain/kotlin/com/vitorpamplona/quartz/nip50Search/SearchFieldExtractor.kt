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
package com.vitorpamplona.quartz.nip50Search

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags

/**
 * Decomposes every [SearchableEvent] into [IndexableFields] by priority tier:
 * title-like accessors primary, summary/description secondary, body tertiary,
 * with hashtag and location tags carried raw beside the tiers.
 *
 * Each kind splits its own fields in [SearchableEvent.indexableFields], built with the
 * [tiers] funnel here — it splits exactly the accessors that kind's `indexableContent()`
 * concatenates, so keep the two in sync when a kind's parsing changes. Kinds that do not
 * override it get the whole `indexableContent()` in the tertiary tier, so EVERY searchable
 * kind, current or future, is extracted.
 *
 * A kind may also fill the profile roles when it carries that shape: kind
 * 31990 goes through the kind-0 fields wholesale, and any kind with a
 * homepage/site URL fills [IndexableFields.websites]. Hashtags and `location`
 * tags are filled SYSTEMICALLY by the [tiers] funnel every content kind
 * uses, so recall never depends on a kind remembering them.
 *
 * Non-searchable kinds return [IndexableFields.None]. The extraction is
 * derived data baked into the build: stores should re-derive after upgrades
 * (see [com.vitorpamplona.quartz.nip01Core.store.IEventStore.reindexFullTextSearch]).
 */
object SearchFieldExtractor {
    /** Empty extractions always come back as [IndexableFields.None], whatever shape produced them. */
    fun extract(event: Event): IndexableFields {
        val fields = (event as? SearchableEvent)?.indexableFields() ?: return IndexableFields.None
        return if (fields.isEmpty()) IndexableFields.None else fields
    }

    /**
     * Single-value convenience over the list funnel — most kinds carry one
     * title, one summary, one body. Cleans each value straight into its role
     * rather than wrapping it in a list first: extraction runs once per stored
     * event, and the wrappers were three throwaway lists on every one of them.
     */
    fun tiers(
        event: Event,
        primary: String?,
        secondary: String?,
        text: String?,
        website: String? = null,
    ) = build(event, cleanOne(primary), cleanOne(secondary), text, cleanOne(website))

    fun tiers(
        event: Event,
        primary: List<String?>,
        secondary: List<String?>,
        text: String?,
        websites: List<String?> = emptyList(),
    ) = build(event, cleanAll(primary), cleanAll(secondary), text, cleanAll(websites))

    /**
     * The one funnel BOTH [tiers] overloads end in — [IndexableFields.Tiered.hashtags]
     * and [IndexableFields.Tiered.locations] are filled here, so no branch can
     * forget them. Values stay UNJOINED: separator choices belong to the backend.
     */
    private fun build(
        event: Event,
        primary: List<String>,
        secondary: List<String>,
        text: String?,
        websites: List<String>,
    ) = IndexableFields.Tiered(
        primary = primary,
        secondary = secondary,
        text = clean(text),
        hashtags = hashtagValues(event),
        locations = locationValues(event),
        websites = websites,
    )

    /** Trim and drop empties at the single funnel every derived string passes through. */
    fun clean(s: String?): String? = s?.trim()?.ifEmpty { null }

    fun cleanOne(s: String?): List<String> = clean(s)?.let { listOf(it) } ?: emptyList()

    /**
     * Collects lazily: a role whose values are all absent — the common case on
     * most kinds — costs no list at all.
     */
    fun cleanAll(parts: List<String?>): List<String> {
        var values: MutableList<String>? = null
        for (i in parts.indices) {
            val value = clean(parts[i]) ?: continue
            (values ?: ArrayList<String>(parts.size).also { values = it }).add(value)
        }
        return values ?: emptyList()
    }

    /**
     * The hashtag role. [hashtags] allocates unconditionally, so the scan for
     * a `t` tag comes first — most events carry none. The guard is exactly
     * [HashtagTag.parse]'s own acceptance test, so it can never skip a tag the
     * accessor would have returned.
     */
    private fun hashtagValues(event: Event): List<String> = if (!event.tags.fastAny(HashtagTag::isTagged)) emptyList() else cleanAll(event.tags.hashtags())

    /**
     * Every `location` tag value, on ANY kind. Deliberately a raw scan, not a
     * typed accessor: Quartz's LocationTag classes are per-NIP (calendar,
     * picture, classifieds) and only those kinds expose locations(), while
     * this funnel must also catch location tags on kinds whose class doesn't
     * model them. Collects lazily, like [cleanAll]: an event with no location
     * tag — nearly all of them — allocates nothing here.
     */
    private fun locationValues(event: Event): List<String> {
        var values: MutableList<String>? = null
        event.tags.fastForEach { tag ->
            if (tag.size > 1 && tag[0] == LOCATION_TAG) {
                val value = clean(tag[1])
                if (value != null) {
                    (values ?: ArrayList<String>(2).also { values = it }).add(value)
                }
            }
        }
        return values ?: emptyList()
    }

    private const val LOCATION_TAG = "location"
}
