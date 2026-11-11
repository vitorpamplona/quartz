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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * The morning Observer edition as a NIP-FB scheduled task (`quartz/plans/2026-11-11-observer-site.md`,
 * "The morning schedule, synced"): kind 34817 with the fixed `d` = [D_TAG], so two apps setting it
 * up at once edit one event, and `task` = [TASK]. Whether today's edition already exists, the
 * check before a scheduled print, is [ObserverSite.alreadyPrintedToday]. The claim race is
 * NIP-FB's ([com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims]), and so is the
 * missed-run warning ([ScheduledTask.missedRun]).
 */
object ObserverSchedule {
    const val D_TAG = "observer"
    const val TASK = "observer"

    fun address(author: HexKey) = ScheduledTaskEvent.createAddress(author, D_TAG)

    /** The author's morning schedule, for a relay read. */
    fun filter(author: HexKey) = Filter(kinds = listOf(ScheduledTaskEvent.KIND), authors = listOf(author), tags = mapOf("d" to listOf(D_TAG)))

    fun isObserverSchedule(event: Event) = event is ScheduledTaskEvent && event.dTag() == D_TAG

    /** The newest morning schedule of [author] among [events]; null when there is none. */
    fun newest(
        events: List<Event?>,
        author: HexKey,
    ): ScheduledTaskEvent? =
        events
            .filterIsInstance<ScheduledTaskEvent>()
            .filter { it.pubKey == author && it.dTag() == D_TAG }
            .maxByOrNull { it.createdAt }

    /** A new schedule, paused, at the default time in [zone]: what an app writes when none exists. */
    fun new(zone: String): ScheduledTask = ScheduledTask.create(D_TAG, TASK, zone = zone, paused = true)

    /** The unsigned schedule event for [task], encrypted to [signer], replacing [previous]. */
    suspend fun build(
        task: ScheduledTask,
        signer: NostrSigner,
        previous: Event?,
        now: Long = TimeUtils.now(),
    ) = ScheduledTaskEvent.update(task, signer, D_TAG, ScheduledTaskEvent.nextCreatedAt(previous, now))
}
